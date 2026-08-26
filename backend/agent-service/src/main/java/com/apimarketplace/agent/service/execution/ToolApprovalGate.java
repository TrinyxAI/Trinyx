package com.apimarketplace.agent.service.execution;

import com.apimarketplace.conversation.client.StreamRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Parks ONE tool call until the user answers its approval card, so an authorization or a
 * missing connection is resolved INSIDE the call instead of ending the assistant turn.
 *
 * <p><b>Why this exists.</b> Both approval gates used to return a synthetic
 * "not executed, do not retry" result. The model then wrapped up its turn, the user
 * clicked the card, and the frontend injected a synthetic USER message to start a fresh
 * turn in which the model re-planned the call from scratch. That is what made the flow
 * read as machinery rather than conversation. Parking here removes the extra turn: the
 * real tool result lands in the same assistant message that asked for permission.
 *
 * <p><b>Cross-pod by construction.</b> The verdict travels through Redis
 * ({@link StreamRedisKeys#approvalDecisionKey}), never through process memory: the
 * approval POST is served by an arbitrary conversation-service replica while the parked
 * call is held by one specific agent-service replica. Polling rather than pub/sub is
 * deliberate - a verdict written before the gate starts watching is still readable,
 * whereas a published message with no subscriber is gone. At user timescales the poll
 * cost is irrelevant.
 *
 * <p><b>Every failure mode returns the CALLER to the pre-existing behaviour.</b>
 * Disabled by config, Redis unreachable, malformed verdict, deadline reached: all report
 * a non-approval and the caller returns exactly the result it would have returned before
 * this class existed. The gate can only ever turn a "no" into a "yes"; it can never
 * invent a failure.
 *
 * <p>The ANSWER side is not equally safe, and this is where to look when a click seems to
 * do nothing. If Redis is unreachable the pending marker cannot be deleted either, so
 * until {@link StreamRedisKeys#APPROVAL_DECISION_TTL} expires the answer side still reads
 * "somebody is holding": the approve call reports a release that never happened and the
 * frontend skips the resume it would otherwise have sent. It fails closed (nothing runs,
 * nothing is charged) and heals itself when the key expires, but for those minutes the
 * card is dead. See {@link StreamRedisKeys#approvalDecisionKey}.
 */
@Slf4j
@Component
public class ToolApprovalGate {

    /** Verdict of a park. Only {@link #APPROVED} lets the caller run the tool for real. */
    public enum Decision {
        /** The user allowed it. Execute (or re-execute) the call for real. */
        APPROVED,
        /** The user refused. Keep the synthetic "not executed" result. */
        DENIED,
        /** Nobody answered before the deadline. Keep the synthetic result. */
        EXPIRED,
        /**
         * The user stopped the turn while the call was parked. Settled like a refusal, and
         * never a licence to run: an approval clicked on a card left over from a stopped
         * turn must not spend credit or reach an external service.
         */
        STOPPED,
        /** The gate could not run at all (disabled / no Redis / no conversation). */
        UNAVAILABLE
    }

    static final String VERDICT_APPROVED = "approved";
    static final String VERDICT_DENIED = "denied";

    /**
     * Written by {@link #beginPark} to advertise that a call is waiting on this key, and
     * removed when the park ends. It is what makes the answer side able to tell "a call is
     * holding for this card" from "nobody is holding any more", which decides whether the
     * user's click resumes the agent in place or has to restart a turn the old way.
     */
    static final String VERDICT_PENDING = "pending";

    /**
     * Marks a tool result whose approval card the gate already painted before parking.
     * Both result consumers ({@code ConversationRedisStreamingCallback} on the Java loop,
     * {@code redis-publisher.mjs} on the CLI-bridge path) skip their own emission when it
     * is set, otherwise the user would see the same card twice: once from the gate, once
     * from the consumer reacting to the very result the gate produced on the way out.
     */
    public static final String META_CARD_EMITTED = "approvalCardEmitted";

    /**
     * How the park ended, lowercased: {@code denied}, {@code stopped}, {@code expired} or
     * {@code unavailable}.
     *
     * <p>Consumed by conversation-service's end-of-turn persistence, which must tell apart
     * "settled, nothing is pending" from "nobody answered, the card is still on screen".
     * {@code denied} and {@code stopped} are settled: persisting either would resurrect, on
     * the next page load, a card the user dismissed or a turn they cancelled.
     */
    public static final String META_DECISION = "approvalGateDecision";

    /**
     * Safety margin subtracted from the caller's hard deadline. The gate must wake up
     * with enough budget left to actually RUN the tool it was holding, otherwise
     * approving would only trade a clean "not authorized" for a timeout.
     */
    static final long DEADLINE_MARGIN_MS = 5_000;

    /** Consecutive failed polls after which the gate stops waiting on an unreachable Redis. */
    static final int MAX_CONSECUTIVE_READ_FAILURES = 3;

    /**
     * The longest a call may be held when the answer has to survive a CLI sitting on the
     * other end of the tool call (the CLI bridge route - see {@code BridgeProviders}).
     *
     * <p>Those CLIs each stop waiting inline on one MCP call for their own reason and after
     * their own delay - codex errors out on a per-call timeout documented at 60 s, the
     * others background or time out on values that vary by version. None of it is ours: the
     * binaries are installed unpinned, and a CLI that stops waiting does NOT cancel the
     * request, so the call stays held and later runs for real with nobody to read it.
     *
     * <p>Rather than configure four binaries this codebase cannot test (claude-code, codex,
     * gemini-cli and mistral-vibe, all installed unpinned), this bounds the silence the
     * GATE ADDS. Running out of time is not a failure: it lands on the flow that shipped
     * before the gate existed (the card stays, the turn ends, the user's answer starts a
     * new one), so a slow answer costs the improvement, not correctness. The direct route,
     * where we control the caller, keeps the full budget.
     *
     * <p><b>What it does NOT do.</b> The CLI's timer covers the whole call - this wait AND
     * the tool run that follows an approval. A tool that on its own outruns the CLI was
     * already abandoned before this gate existed, and still is; the gate cannot fix that,
     * it can only avoid making it much worse. Sized so that a park run to the very end
     * still leaves the larger share of the shortest known floor (codex publishes 60 s) to
     * the tool: 35 s of 60, against 15 s if the wait took 45.
     *
     * <p><b>What this costs, plainly.</b> 25 s is enough for "allow this action?", where the
     * user is present and clicks in seconds. It is NOT enough to connect a service: that
     * card waits on an OAuth round trip in another tab, so on the bridge it will normally
     * expire and fall back to the old two-turn flow. Half the feature, on that route, by
     * choice - the half that would otherwise cost a killed run.
     *
     * <p>The floor itself is documentation, not something this repo can verify - codex
     * publishes 60 s; the others vary by version. If a CLI is ever observed giving up while
     * agent-service is still logged as parking, this is the number to lower.
     */
    static final long BRIDGE_MAX_PARK_MS = 25_000;

    private final StringRedisTemplate redisTemplate;

    /** Master switch. Off = the pre-existing non-blocking behaviour, everywhere. */
    @Value("${agent.tool.approval-gate.enabled:true}")
    private boolean enabled;

    /**
     * How long a call may stay parked when the caller imposes no shorter deadline.
     *
     * <p>Read it as a floor on ambition, not as the wait a user actually gets: it is the
     * STARTING value of {@link #resolveDeadline}, and every other bound there can only pull
     * it down: the caller's own tool deadline, half the run's inactivity window, and on the
     * bridge {@link #BRIDGE_MAX_PARK_MS}. Which of them binds is a question about the call,
     * not about the route (a bridge run with a 30 s watchdog is cut by the half-window, well
     * under the cap). A caller whose own bounds all sit ABOVE this value gets this value,
     * which is the common case on the direct route for a long-running tool.
     *
     * <p>The value sits under the bridge's 5-minute inactivity watchdog on purpose. That
     * watchdog is reset only by the CLI's own output, and a parked call is silent, so a hold
     * as long as the watchdog would always lose the race: the run gets killed instead of the
     * park expiring, and a kill is not recoverable while an expiry is. Running out of time
     * here degrades to the pre-gate behaviour: the card stays, the turn ends, and the user's
     * answer starts a new one.
     */
    @Value("${agent.tool.approval-gate.timeout-ms:240000}")
    private long defaultTimeoutMs;

    /** Poll period. User-scale waits make anything under a second pointless churn. */
    @Value("${agent.tool.approval-gate.poll-interval-ms:750}")
    private long pollIntervalMs;

    /**
     * How many calls may be parked at once in this process.
     *
     * <p>A park blocks the thread it runs on. On the CLI-bridge route that thread belongs to
     * the servlet connector (the tool call is a synchronous request), whose pool is finite:
     * enough simultaneous parks and every other request queues behind people who are out
     * getting coffee. Beyond this many, the gate declines to park and the caller falls back
     * to the flow that shipped before it existed - a worse experience for that one call,
     * against an unresponsive service for everyone.
     */
    @Value("${agent.tool.approval-gate.max-concurrent:32}")
    private int maxConcurrentParks;

    /** Parks currently holding a thread; the ceiling above applies to this. */
    private final java.util.concurrent.atomic.AtomicInteger activeParks =
            new java.util.concurrent.atomic.AtomicInteger();

    public ToolApprovalGate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Test seam: exercise the park deterministically without a Spring context. */
    void configureForTest(boolean enabled, long defaultTimeoutMs, long pollIntervalMs) {
        configureForTest(enabled, defaultTimeoutMs, pollIntervalMs, 32);
    }

    void configureForTest(boolean enabled, long defaultTimeoutMs, long pollIntervalMs, int maxConcurrentParks) {
        this.enabled = enabled;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.pollIntervalMs = pollIntervalMs;
        this.maxConcurrentParks = maxConcurrentParks;
    }

    /**
     * Advertise that a call is about to park on {@code gateKey}, BEFORE its card is shown.
     *
     * <p>Order matters. The card tells the frontend the agent is holding, and the frontend
     * then answers by releasing instead of restarting a turn. If the card went up first and
     * this write failed (or arrived late), the user could answer a card that no park is
     * listening to: nothing releases, no turn restarts, and the conversation is simply
     * stuck. So a caller that gets {@code false} here must NOT show a blocking card - it
     * falls back to the flow that shipped before the gate existed.
     *
     * @return true when the park is advertised and {@link #awaitDecision} may be called
     */
    public boolean beginPark(ParkRequest park) {
        String conversationId = park.conversationId();
        String gateKey = park.gateKey();
        if (!enabled || conversationId == null || conversationId.isBlank()
                || gateKey == null || gateKey.isBlank()) {
            return false;
        }
        // RESERVE the slot, do not merely look at it. A plain read lets a burst of calls all
        // see room, all paint a card that says the assistant is waiting, and all but a few
        // then not wait - leaving cards that lie, and that keep lying on every reload
        // because only an ANSWERED card leaves the replay buffer.
        if (activeParks.incrementAndGet() > maxConcurrentParks) {
            activeParks.decrementAndGet();
            log.warn("Approval gate declined to park {} - {} calls are already parked", gateKey, maxConcurrentParks);
            return false;
        }
        // And refuse a park with no time left, for the same reason: on a call that already
        // parked once, the shared window can be spent before the second card is even drawn.
        if (resolveDeadline(park) <= System.currentTimeMillis()) {
            activeParks.decrementAndGet();
            log.info("Approval gate declined to park {} - no time left in the call's budget", gateKey);
            return false;
        }
        String key = StreamRedisKeys.approvalDecisionKey(conversationId, gateKey);
        try {
            redisTemplate.opsForValue().set(key, VERDICT_PENDING, StreamRedisKeys.APPROVAL_DECISION_TTL);
            return true;
        } catch (Exception e) {
            activeParks.decrementAndGet();
            log.warn("Approval gate could not advertise the park for {}: {}", gateKey, e.getMessage());
            return false;
        }
    }

    /**
     * Undo a {@link #beginPark} that will never be waited on.
     *
     * <p>Between advertising a park and actually waiting on it, the caller still has to get
     * a card in front of the user. If that fails there is nothing to answer, so the marker
     * has to go: left behind, it would tell the answer side "somebody is holding" for its
     * whole lifetime, and any click on a later card would be reported as released and
     * silently swallowed.
     */
    public void abandonPark(String conversationId, String gateKey) {
        if (conversationId == null || conversationId.isBlank() || gateKey == null || gateKey.isBlank()) {
            return;
        }
        // Gives back the slot beginPark reserved, as well as the marker: a park that is
        // never waited on must not count against everyone else's ceiling either.
        activeParks.decrementAndGet();
        clear(StreamRedisKeys.approvalDecisionKey(conversationId, gateKey));
    }

    /**
     * Block until this call's card is answered, the deadline passes, or the gate proves
     * unusable.
     *
     * @param park what is being parked and for how long; see {@link ParkRequest}
     */
    public Decision awaitDecision(ParkRequest park) {
        String conversationId = park.conversationId();
        String gateKey = park.gateKey();
        if (!enabled || conversationId == null || conversationId.isBlank()
                || gateKey == null || gateKey.isBlank()) {
            return Decision.UNAVAILABLE;
        }

        String key = StreamRedisKeys.approvalDecisionKey(conversationId, gateKey);
        try {
            // Inside the try, not before it. beginPark ran the same check and let this park
            // through, but the budget is measured against absolute instants and time passes
            // in between: the marker write and the card publish sit between the two checks,
            // so a park with only a moment left can lapse right here. Outside the try that
            // return kept the slot forever, and enough of them stop the process parking at
            // all - a failure that reads as "the gate quietly stopped working".
            long deadline = resolveDeadline(park);
            if (deadline <= System.currentTimeMillis()) {
                // The caller's own budget is already spent - parking would guarantee a timeout.
                log.info("Approval gate skipped for {} - no time budget left before the tool deadline", gateKey);
                return endPark(key, park.streamId(), Decision.EXPIRED);
            }
            return pollUntilAnswered(park, key, deadline);
        } finally {
            // Releases the slot beginPark reserved. Every exit from a started park goes
            // through here; the paths that never start release it themselves.
            activeParks.decrementAndGet();
        }
    }

    private Decision pollUntilAnswered(ParkRequest park, String key, long deadline) {
        String gateKey = park.gateKey();
        log.info("Parking tool call {} on approval gate (up to {}ms)", gateKey, deadline - System.currentTimeMillis());

        int consecutiveReadFailures = 0;
        while (System.currentTimeMillis() < deadline) {
            if (wasStopped(park.streamId())) {
                // The user pressed Stop. Their earlier card may still be on screen, and
                // clicking it would otherwise release this park and run the action for real
                // on a turn they just cancelled - paying for it and, for an external call,
                // doing it in the world. Stop has to win here, not merely be recorded.
                log.info("Approval gate for {} abandoned - the user stopped the turn", gateKey);
                // Discard whatever is in the key instead of taking one last look. A Stop can
                // land just after an Approve, and honouring that late approval would run the
                // action on a turn the user cancelled - the one outcome this class promises
                // never happens. Every OTHER way of giving up wants the last look.
                clear(key);
                return Decision.STOPPED;
            }
            VerdictRead read = readVerdict(key);
            if (read.failed()) {
                // A Redis outage must not hold every gated call for the full deadline: give
                // up quickly so the caller falls back to the non-blocking behaviour instead
                // of turning a broken cache into minutes of dead air in the chat.
                if (++consecutiveReadFailures >= MAX_CONSECUTIVE_READ_FAILURES) {
                    log.warn("Approval gate for {} abandoned - {} consecutive Redis read failures",
                            gateKey, consecutiveReadFailures);
                    return endPark(key, park.streamId(), Decision.UNAVAILABLE);
                }
            } else {
                consecutiveReadFailures = 0;
                if (read.decision() != null) {
                    log.info("Approval gate for {} released as {}", gateKey, read.decision());
                    clear(key);
                    return read.decision();
                }
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(pollIntervalMs, remaining));
            } catch (InterruptedException e) {
                // A pod draining interrupts its workers; abandon rather than hold the turn
                // hostage for the full deadline. (A user Stop does NOT interrupt this
                // thread - that is what the cancel-flag check at the top of the loop is
                // for; relying on the interrupt alone let a stopped turn keep parking.)
                Thread.currentThread().interrupt();
                log.info("Approval gate for {} interrupted - abandoning the park", gateKey);
                return endPark(key, park.streamId(), Decision.UNAVAILABLE);
            }
        }

        log.info("Approval gate for {} expired with no answer", gateKey);
        return endPark(key, park.streamId(), Decision.EXPIRED);
    }

    /**
     * Stop advertising the park and take one last look, atomically.
     *
     * <p>{@code GETDEL} closes the only race the two-sided handoff has: an answer written
     * in the instant between the last poll and giving up would otherwise be dropped, and
     * the user would watch their approval do nothing. Reading and deleting in one operation
     * means the verdict is either honoured here, or the key is gone before the answer side
     * can write to it - and that side then reports "nothing was released", which is what
     * tells the frontend to fall back to restarting a turn.
     *
     * <p><b>A Stop still wins over that last look.</b> The loop checks the cancel flag on
     * every pass, but the ways a park ENDS do not go through the loop: the budget can be
     * spent before the first poll, Redis can fail, the thread can be interrupted, the
     * deadline can pass. On any of those, honouring an {@code approved} that arrived a
     * moment earlier would run the action for real on a turn the user cancelled - charged,
     * and for an external call, done in the world. So the flag is read here too, and a
     * stopped turn discards the verdict instead of taking it. This is the promise
     * {@link Decision#STOPPED} makes in absolute terms; the loop alone did not keep it.
     */
    private Decision endPark(String key, String streamId, Decision fallback) {
        try {
            Decision late = parseVerdict(redisTemplate.opsForValue().getAndDelete(key));
            if (late != null) {
                if (wasStopped(streamId)) {
                    log.info("Approval gate for {} read {} on its last look, but the turn was "
                            + "stopped - discarding it", key, late);
                    return Decision.STOPPED;
                }
                log.info("Approval gate for {} released as {} on its last look", key, late);
                return late;
            }
        } catch (Exception e) {
            // WARN, not debug: the marker outliving the park is the one failure here that
            // the user feels. Until it expires, the answer side still reads "somebody is
            // holding", so their click reports a release that never happened and the resume
            // is skipped - a card that does nothing, with no other trace anywhere.
            log.warn("Approval gate could not delete the park marker at {} ({}) - the card is dead "
                    + "until the key expires", key, e.getMessage());
        }
        return fallback;
    }

    /** One poll: either a read failure, or a verdict (possibly "nobody answered yet"). */
    private record VerdictRead(boolean failed, Decision decision) {
        static VerdictRead failure() {
            return new VerdictRead(true, null);
        }
        static VerdictRead of(Decision decision) {
            return new VerdictRead(false, decision);
        }
    }

    /**
     * @return {@link VerdictRead#failed()} when Redis could not be reached, otherwise the
     *         verdict, with a {@code null} decision meaning nobody has answered yet.
     */
    private VerdictRead readVerdict(String key) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Approval gate could not read {}: {}", key, e.getMessage());
            return VerdictRead.failure();
        }
        return VerdictRead.of(parseVerdict(raw));
    }

    /**
     * @return the answer this stored value carries, or {@code null} when it carries none:
     *         the key is gone, or it still holds the {@link #VERDICT_PENDING} marker this
     *         park wrote for itself.
     */
    private Decision parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String verdict = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (VERDICT_PENDING.equals(verdict)) {
            return null;
        }
        if (VERDICT_APPROVED.equals(verdict)) {
            return Decision.APPROVED;
        }
        if (VERDICT_DENIED.equals(verdict)) {
            return Decision.DENIED;
        }
        // An unrecognised value is not a licence to run a sensitive action.
        log.warn("Approval gate read an unrecognised verdict '{}' - treating as denied", raw);
        return Decision.DENIED;
    }

    /** Consume the verdict so a later call reusing the key cannot inherit this answer. */
    private void clear(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            // WARN like the delete in endPark, and for a sharper reason: this is the delete
            // the STOP path uses. A failure here leaves an "approved" alive for the whole
            // TTL, so the next call reusing this key could inherit an answer given for a
            // turn the user cancelled - the one thing the Stop path promises cannot happen.
            log.warn("Approval gate could not clear {} ({}) - a verdict may outlive its park "
                    + "until the key expires", key, e.getMessage());
        }
    }

    /**
     * True when this run has been stopped. Read on every poll rather than relying on a
     * thread interrupt, because nothing interrupts a parked tool call: Stop writes a flag,
     * and the only code that used to read it is the streaming loop, which is not running
     * while this thread blocks.
     *
     * <p>Fails OPEN (keeps parking) on a Redis error: the verdict poll beside it already
     * gives up after a few consecutive failures, and treating an unreadable flag as "the
     * user stopped" would cancel healthy turns during a blip.
     */
    private boolean wasStopped(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(StreamRedisKeys.cancelKey(streamId)));
        } catch (Exception e) {
            log.debug("Approval gate could not read the stop flag for {}: {}", streamId, e.getMessage());
            return false;
        }
    }

    /**
     * When this park must be over, as an absolute instant.
     *
     * <p>Four ceilings, lowest wins, and each exists because something else gives up at
     * that moment:
     * <ul>
     *   <li>the gate's own budget, so an unanswered card cannot hold a turn forever;</li>
     *   <li>the caller's tool deadline (minus a margin to actually RUN the tool), so the
     *       gate cannot cause the very timeout it exists to avoid;</li>
     *   <li>HALF the run's inactivity window, measured from the START of the call, when one
     *       is known. A parked call is silent, and the watchdog that kills a silent run
     *       started counting before the call even began. This bounds the silence the WAIT
     *       adds, leaving the rest of the window for the tool to run - it does not, and
     *       cannot, make a gated tool that runs longer than the window safe: that hazard
     *       predates the gate (a four-minute {@code workflow:execute} produces no CLI output
     *       either). Fixing THAT means teaching the watchdog about pending cards, which is a
     *       change to the bridge, not to this budget.</li>
     *   <li>{@link #BRIDGE_MAX_PARK_MS}, when a CLI is sitting on this very call. It is its
     *       own ceiling rather than part of the one above because the two answer different
     *       questions: the window says when the RUN is declared dead and can legally be
     *       switched off, this says when the CLI stops waiting on ONE call, which it does
     *       either way. Deriving it from the window made it vanish exactly where it was
     *       needed most.</li>
     * </ul>
     */
    private long resolveDeadline(ParkRequest park) {
        long now = System.currentTimeMillis();
        long deadline = now + defaultTimeoutMs;
        if (park.hardDeadlineEpochMs() > 0) {
            // Reserve what the tool actually needs to RUN, not a token margin. One call can
            // park twice (authorize the action, then connect the service it needs), and the
            // second park inherits whatever the first left: with only a few seconds held
            // back, approving it starts a call that is charged and then discarded as a
            // timeout - the very outcome the budget exists to prevent.
            long reserve = Math.max(DEADLINE_MARGIN_MS, park.executionReserveMs());
            deadline = Math.min(deadline, park.hardDeadlineEpochMs() - reserve);
        }
        // Both remaining ceilings are measured from when the CALL began rather than from
        // now: one call can park twice (authorize the action, then connect the service it
        // needs), and giving the second a fresh allowance would let the pair outlast limits
        // that started counting before the first one did.
        long callStarted = park.callStartedEpochMs() > 0 ? park.callStartedEpochMs() : now;
        if (park.inactivityWindowMs() > 0) {
            // Half the run's silence budget, so the tool still has time to run afterwards.
            deadline = Math.min(deadline, callStarted + park.inactivityWindowMs() / 2);
        }
        if (park.cliBridgeSession()) {
            // A CLI is sitting on this call. Short enough that it does not stop waiting on
            // us first - independent of the watchdog, which can legitimately be off.
            deadline = Math.min(deadline, callStarted + BRIDGE_MAX_PARK_MS);
        }
        return deadline;
    }

    /**
     * What one park is: which call, on whose screen, and every ceiling that applies to it.
     *
     * @param conversationId conversation owning the card; null/blank ⇒ {@link Decision#UNAVAILABLE}
     * @param gateKey        identifies the individual parked call (provider tool-call id)
     * @param streamId       the run's stream, used to notice a user Stop; null ⇒ no Stop check
     * @param hardDeadlineEpochMs absolute wall-clock ceiling from the caller's own tool
     *                       timeout, or {@code 0} when the caller imposes none
     * @param inactivityWindowMs the run's inactivity watchdog window, or {@code 0} when
     *                       unknown (no watchdog, or a route that does not use one). NOTE
     *                       this bounds the silence THIS change adds; a gated tool that
     *                       itself runs longer than the window was already at risk from the
     *                       watchdog before the gate existed, and still is.
     * @param callStartedEpochMs when the tool call began, so two parks within one call share
     *                       one window instead of each taking a fresh one
     * @param executionReserveMs how long the tool needs to RUN once released, held back from
     *                       the caller's deadline; {@code 0} falls back to a token margin
     * @param cliBridgeSession whether a CLI is holding this call open at the other end of an
     *                       MCP request, which caps how long it may be held. Stated by the
     *                       session, never inferred: the watchdog window is absent when the
     *                       watchdog is off and present on the direct route when configured,
     *                       so it identifies neither route.
     */
    public record ParkRequest(String conversationId, String gateKey, String streamId,
                              long hardDeadlineEpochMs, long inactivityWindowMs,
                              long callStartedEpochMs, long executionReserveMs,
                              boolean cliBridgeSession) {
    }
}
