package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Remembers WHY the last node claim on a run was refused, so the 409 can name the situation.
 *
 * <p>A refused claim used to answer "Node is not in READY state (already executing or not yet
 * ready)" for four situations that need opposite fixes: a node already executing, one that
 * already finished, one parked on a signal, and one whose predecessors never settled. Naming the
 * absence of a state instead of the state that is actually there is what made the step-by-step
 * e2e failures unattributable.
 *
 * <h2>Where each half of the diagnosis is computed, and why it is split</h2>
 *
 * <p>{@link #refuse} runs INSIDE the claim's transaction, under the {@code PESSIMISTIC_WRITE}
 * row lock on {@code workflow_runs}. It therefore touches nothing but the snapshot it was handed:
 * no Redis, no I/O. Paying for a network round trip while holding an exclusive lock on the
 * hottest row in the system would let a diagnostic degrade execution itself. The running lookup
 * happens in {@link #lastRefusal}, which the controllers call after the claim has returned and
 * the lock is gone, and it uses the epoch-scoped
 * {@link RunningNodeTracker#getRunningCounts(String, int)} - a direct hash read - rather than the
 * cross-epoch reader, which can degrade to a blocking {@code KEYS} scan.
 *
 * <p>Three properties this class exists to guarantee, each of which was got wrong in an earlier
 * attempt and caught by audit:
 *
 * <ol>
 *   <li><b>Running is resolved from Redis, per epoch, and it wins across epochs.</b>
 *       {@code runningNodeIds} is elided from the persisted JSONB (default-ON for every tenant),
 *       so {@link StateSnapshot#getRunningNodeIds()} is EMPTY in production and a node that is
 *       plainly executing would otherwise fall through to {@code unknown} - the one answer that
 *       sends the reader to the DAG wiring. {@link RunningNodeTracker} fills exactly that gap,
 *       and only that gap: see {@link #resolve} for why a recorded outcome beats Redis WITHIN an
 *       epoch while a live execution beats a terminal state ACROSS epochs and DAGs.</li>
 *   <li><b>A refusal is only returned for the node it describes.</b> Entries are keyed per
 *       (run, node). Keyed by run alone, two concurrent refused claims - exactly what the
 *       double-execution guard exists for - had the later one evict the earlier, so the caller
 *       that motivated this class was the one left with the generic sentence. Per-node keys mean
 *       both callers keep their own reason, and a success on one node no longer wipes another's.</li>
 *   <li><b>Entries expire on their own.</b> Bounding this by run lifecycle is not enough:
 *       {@code RunCacheRegistry.cleanupRun} is only reached on rerun / refire / cancel
 *       ({@code WorkflowResumeService}), NOT when a run simply ends, so the ordinary close of a
 *       step-by-step session (a refused click, then the tab closes) would leak the entry and its
 *       copy of the ready set for the life of the JVM. A reason is read milliseconds after it is
 *       written, so it is held in a Caffeine cache with a short TTL and a hard size cap, the
 *       pattern the sibling {@code ReadinessContextCache} and {@code WorkflowVariableBundleCache}
 *       already use. {@link RunScopedCache} is still implemented so a rerun drops it eagerly.</li>
 * </ol>
 */
@Component
public class ClaimRefusalRegistry implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(ClaimRefusalRegistry.class);

    /** A refusal is read by the controller that caused it, in the same request. Seconds suffice. */
    static final Duration TTL = Duration.ofMinutes(2);
    static final long MAX_SIZE = 1000L;

    private static final String UNKNOWN = "unknown";
    private static final String RUNNING = "running";

    /**
     * NUL cannot occur inside a run id or a node label, so composite keys cannot collide. A
     * printable separator could: node ids are {@code prefix:label}, so with {@code ':'} the pairs
     * (run {@code a}, node {@code b:c}) and (run {@code a:b}, node {@code c}) would share a key
     * and one refusal would answer for the other.
     */
    private static final char KEY_SEPARATOR = 0;

    /**
     * Authority order, lowest wins. Fixed rather than positional so the answer does not depend on
     * {@code Map} iteration order: a node reachable from two triggers can hold a different state
     * in each DAG's current epoch, and {@code getDags()} is an immutable map whose iteration order
     * is randomised per JVM.
     */
    static final List<String> PRECEDENCE =
            List.of("completed", "failed", "skipped", "awaiting_signal", RUNNING, UNKNOWN);

    private final RunningNodeTracker runningNodeTracker;
    private final Cache<String, Entry> lastRefusals;

    /**
     * What one DAG's current epoch says about the node, and which epoch that was.
     *
     * <p>Kept per DAG rather than collapsed at refusal time so the Redis lookup can be scoped to
     * the SAME epoch the snapshot verdict came from. That scoping is what lets a stale counter
     * lose locally while a live execution still wins globally (see {@link #resolve}).
     */
    private record DagVerdict(int epoch, String snapshotState) { }

    /** The node and ready set, plus one verdict per DAG, all captured under the claim's lock. */
    private record Entry(String nodeId, Set<String> readyNow, List<DagVerdict> verdicts) { }

    /**
     * {@code @Autowired} is REQUIRED here, not decoration. The test-seam constructor below makes
     * this class have two, and Spring only infers the injection point when there is exactly one:
     * with two it looks for a no-arg constructor, finds none, and the whole context fails to
     * start. That is not a test failure, it is a pod that never becomes ready.
     */
    @Autowired
    public ClaimRefusalRegistry(RunningNodeTracker runningNodeTracker) {
        this(runningNodeTracker, Ticker.systemTicker());
    }

    /** Test seam: a fake ticker is the only way to prove the TTL half of the bound. */
    ClaimRefusalRegistry(RunningNodeTracker runningNodeTracker, Ticker ticker) {
        this.runningNodeTracker = runningNodeTracker;
        this.lastRefusals = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_SIZE)
                .ticker(ticker)
                .build();
    }

    /**
     * Why one claim was refused, in terms the caller can act on.
     *
     * @param nodeId   the node the caller asked to execute
     * @param state    one of {@code running}, {@code completed}, {@code failed}, {@code skipped},
     *                 {@code awaiting_signal}, {@code unknown}
     * @param readyNow the nodes that COULD be claimed instead, at the moment of the refusal
     */
    public record ClaimRefusal(String nodeId, String state, Set<String> readyNow) {

        public String explain() {
            String head = switch (state) {
                case RUNNING -> "it is already executing";
                case "completed", "failed", "skipped" ->
                        "it already finished (" + state + "); re-run it instead of executing it";
                case "awaiting_signal" -> "it is parked waiting for a signal (approval, interface or timer)";
                // Deliberately hedged. This bucket is everything that is not one of the states
                // above, which includes a step id that no longer exists in the plan (both execute
                // paths accept a plan edit immediately before the claim) and a node whose running
                // marker was lost. Asserting one cause here is what the whole change exists to
                // stop doing.
                case UNKNOWN -> "it is not runnable right now: its predecessors may not have all "
                        + "settled, or the id may not be in the current plan";
                default -> "it is " + state;
            };
            // Sorted: these lines get diffed across runs, and a HashSet would reorder them.
            String claimable = readyNow.isEmpty() ? "nothing" : String.join(", ", new TreeSet<>(readyNow));
            return "Node " + nodeId + " cannot be executed: " + head + ". Claimable right now: " + claimable + ".";
        }
    }

    /**
     * Record a refusal and log it. Always returns {@code false} so callers can
     * {@code return registry.refuse(...)} at the rejection site.
     *
     * <p>Snapshot-only by design: this runs under the claim's row lock. The log line can
     * therefore say {@code unknown} for a node that {@link #lastRefusal} will resolve as running;
     * the body the caller receives is the authoritative one.
     *
     */
    public boolean refuse(String runId, StateSnapshot current, String nodeId) {
        Entry entry = new Entry(nodeId, Set.copyOf(current.getReadyNodeIds()),
                verdictsFromSnapshot(current, nodeId));
        lastRefusals.put(key(runId, nodeId), entry);
        // DEBUG, not WARN: nothing here has consulted Redis yet, so under the elide this says
        // "not runnable right now" for a node that is plainly executing. The controllers log the
        // RESOLVED message at WARN once the lock is released. Two WARN lines per refusal, the
        // first of them wrong in the commonest case, would re-create in the log channel exactly
        // the misattribution this class removes from the response.
        if (log.isDebugEnabled()) {
            log.debug("[StateSnapshot] Claim rejected for run {} (pre-resolution): {}",
                    runId, toRefusal(entry, bestOf(entry.verdicts().stream()
                            .map(DagVerdict::snapshotState).toList())).explain());
        }
        return false;
    }

    private static String key(String runId, String nodeId) {
        return runId + KEY_SEPARATOR + nodeId;
    }

    /**
     * The reason the claim for THIS node was refused, or empty when the last refusal on the run
     * concerned another node (or the last claim succeeded). Callers fall back to the generic
     * message on empty.
     *
     * <p>Resolves {@code running} here rather than at refusal time: see the class javadoc.
     */
    public Optional<ClaimRefusal> lastRefusal(String runId, String nodeId) {
        return Optional.ofNullable(lastRefusals.getIfPresent(key(runId, nodeId)))
                .map(e -> toRefusal(e, resolve(runId, e)));
    }

    /**
     * Combine the per-DAG verdicts into one answer, consulting Redis only where the snapshot had
     * nothing to say.
     *
     * <p>Two rules, and they are deliberately different because the mistakes they prevent are
     * not symmetric:
     *
     * <ul>
     *   <li><b>Within one epoch, a recorded outcome beats Redis.</b> A pod killed mid-execution
     *       never decrements the counter, so the entry survives the hash's TTL. If Redis won
     *       here, a node that has since finished would report "already executing" for an hour.
     *       So Redis is asked only about epochs whose snapshot verdict is {@code unknown} - the
     *       gap the elide leaves.</li>
     *   <li><b>Across epochs and DAGs, running beats a terminal state.</b> A node reachable from
     *       two triggers can be finished under one and executing under another; the same holds
     *       for two epochs in flight. Reporting "it already finished, re-run it instead" for a
     *       node that is executing right now is the only wrong answer here that pushes the user
     *       toward a DESTRUCTIVE action, so it must not be reachable. Reporting "already
     *       executing" for something finished only makes them wait.</li>
     * </ul>
     *
     * <p><b>Known imprecision, in the safe direction.</b> The tracker hash is keyed
     * {@code (run, epoch)} and is SHARED by every DAG sitting at that epoch number - it carries no
     * trigger. So a counter left behind by DAG A (pod killed before {@code markCompleted}, 1h TTL)
     * is visible when resolving DAG B's {@code unknown} verdict at the same number, and the answer
     * becomes "already executing" for a node that has finished. That over-reports {@code running},
     * which is the direction that only costs the user a wait; suppressing it would mean ignoring
     * genuinely live executions and re-opening the destructive answer above. Fixing it properly
     * needs a trigger-scoped tracker key, which is a change to the tracker, not to this class.
     */
    private String resolve(String runId, Entry entry) {
        List<String> states = new java.util.ArrayList<>(entry.verdicts().size());
        Set<Integer> asked = new java.util.HashSet<>();
        for (DagVerdict verdict : entry.verdicts()) {
            if (!UNKNOWN.equals(verdict.snapshotState())) {
                states.add(verdict.snapshotState());
                continue;
            }
            // One read per epoch NUMBER, not per DAG: the tracker hash is keyed
            // (run, epoch) and shared by every DAG sitting at that number, so asking twice
            // would be the same HGETALL twice on a user-click path.
            if (!asked.add(verdict.epoch())) {
                continue;
            }
            if (isRunning(runId, entry.nodeId(), verdict.epoch())) {
                return RUNNING;
            }
            states.add(UNKNOWN);
        }
        // A running verdict read straight from a legacy (pre-elide) snapshot gets the same
        // priority as one resolved from Redis above: across DAGs, live beats finished.
        return states.contains(RUNNING) ? RUNNING : bestOf(states);
    }

    /**
     * Highest-authority state among those given. A state outside {@link #PRECEDENCE} would score
     * -1 from {@code indexOf} and silently outrank everything, so it is treated as the weakest.
     */
    private static String bestOf(List<String> states) {
        String best = UNKNOWN;
        for (String state : states) {
            if (rank(state) < rank(best)) {
                best = state;
            }
        }
        return best;
    }

    private static int rank(String state) {
        int index = PRECEDENCE.indexOf(state);
        return index < 0 ? PRECEDENCE.size() : index;
    }

    private static ClaimRefusal toRefusal(Entry entry, String state) {
        return new ClaimRefusal(entry.nodeId(), state, entry.readyNow());
    }

    /**
     * Drop this node's refusal - called when a claim on it succeeds. Scoped to the node: a
     * success on one node says nothing about why a DIFFERENT node of the same run is refused.
     */
    public void clear(String runId, String nodeId) {
        lastRefusals.invalidate(key(runId, nodeId));
    }

    /**
     * Write the diagnosis into a 409 body and return the same sentence for the log line, so the
     * two can never disagree and {@code explain()} is built once. Both the REST and the
     * WS-internal refusal paths share this; only their envelope keys differ.
     *
     * <p>With no recorded reason the message still names the node and lists the situations it
     * could be in, which is as much as is knowable at that point.
     */
    public static String describeInto(Map<String, Object> body, Optional<ClaimRefusal> refusal, String nodeId) {
        String message = refusal.map(ClaimRefusal::explain)
                .orElseGet(() -> "Node " + nodeId + " cannot be executed: it is not in READY state "
                        + "(already executing, already finished, or not yet runnable).");
        body.put("message", message);
        refusal.ifPresent(r -> {
            body.put("nodeState", r.state());
            body.put("readyNow", new TreeSet<>(r.readyNow()));
        });
        return message;
    }

    /**
     * Persisted states only, read from the CURRENT epoch of each DAG.
     *
     * <p>The flat getters ({@code getCompletedNodeIds()} and friends) union every ACTIVE epoch,
     * and several epochs are in flight at once by design. A node COMPLETED in epoch 1 and
     * executing again in epoch 2 would therefore read back as "completed", and the caller would
     * be told "it already finished, re-run it instead" about the very epoch it is trying to step:
     * a confidently wrong answer, which is the failure class this whole class exists to remove.
     *
     * <p>A node found in no current epoch stays {@code unknown} even if an older epoch records an
     * outcome for it. That is deliberate: it is not actionable in the epoch the caller is on, and
     * a hedged answer beats a precise answer about the wrong epoch.
     *
     * <p>Within an epoch the order is by authority. A legacy (pre-elide) snapshot that still
     * carries running ids is honoured, but AFTER the terminal states, for the same reason Redis
     * is: a stale running marker must never outrank a recorded outcome.
     */
    private List<DagVerdict> verdictsFromSnapshot(StateSnapshot s, String nodeId) {
        List<DagVerdict> verdicts = new java.util.ArrayList<>(s.getDags().size());
        for (DagState dag : s.getDags().values()) {
            EpochState epoch = dag.currentEpochState();
            verdicts.add(new DagVerdict(dag.getCurrentEpoch(), classifyIn(
                    epoch.getCompletedNodeIds(), epoch.getFailedNodeIds(), epoch.getSkippedNodeIds(),
                    epoch.getAwaitingSignalNodeIds(), epoch.getRunningNodeIds(), nodeId)));
        }
        return verdicts;
    }

    /**
     * One epoch's verdict, in order of authority. A legacy (pre-elide) snapshot that still carries
     * running ids is honoured, but AFTER the terminal states: within a single epoch a stale
     * running marker must never outrank a recorded outcome.
     */
    static String classifyIn(Set<String> completed, Set<String> failed, Set<String> skipped,
                              Set<String> awaiting, Set<String> running, String nodeId) {
        if (completed.contains(nodeId)) return "completed";
        if (failed.contains(nodeId)) return "failed";
        if (skipped.contains(nodeId)) return "skipped";
        if (awaiting.contains(nodeId)) return "awaiting_signal";
        if (running.contains(nodeId)) return RUNNING;
        return UNKNOWN;
    }

    /**
     * Redis view of the running set for ONE epoch. Reads the per-epoch hash directly by key: no
     * tracker enumeration, so this cannot degrade to the blocking {@code KEYS} scan that the
     * cross-epoch reader falls back to. A refusal answers a user's click; it must not pay for
     * that. Fail-OPEN by contract, and guarded here as well: a diagnostic must never be able to
     * throw out of a refusal path.
     */
    private boolean isRunning(String runId, String nodeId, int epoch) {
        try {
            Integer count = runningNodeTracker.getRunningCounts(runId, epoch).get(nodeId);
            return count != null && count > 0;
        } catch (RuntimeException e) {
            log.debug("[ClaimRefusal] running lookup failed for runId={}, nodeId={}, epoch={}: {}",
                    runId, nodeId, epoch, e.getMessage());
            return false;
        }
    }

    @Override
    public void cleanupRun(String runId) {
        // Keys are composite, so this scans rather than invalidating one entry. Bounded by
        // MAX_SIZE and only reached on rerun / refire / cancel, never on a hot path.
        String prefix = runId + KEY_SEPARATOR;
        lastRefusals.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public String getCacheName() {
        return "ClaimRefusalRegistry";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STATE;
    }

    @Override
    public int getCacheSize() {
        // Drain pending evictions first: estimatedSize() otherwise counts entries that are
        // already expired or invalidated, which is the opposite of what a size report is for.
        lastRefusals.cleanUp();
        return (int) lastRefusals.estimatedSize();
    }
}
