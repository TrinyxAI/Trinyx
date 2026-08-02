package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Single entry point for an AGENT-initiated stop of a workflow run.
 *
 * <p><b>Why this exists.</b> Stopping a run was, until now, a human-only action:
 * the UI stop button and the REST endpoints on {@code WorkflowRunController}. An
 * agent could start an execution ({@code workflow/application(action='execute')})
 * but had no way to end one, so a run that had gone wrong (a browser agent stuck
 * on a login wall, a loop burning credits, a wrong input) kept going until it
 * finished on its own. This service is the shared core behind the agent-facing
 * {@code stop_run} action on every tool that can execute something, so the two
 * tools cannot drift apart.
 *
 * <p><b>What it adds on top of {@link WorkflowResumeService}.</b> Nothing about
 * the stop mechanics: {@code cancelWorkflow} / {@code stopWorkflow} already set
 * the Redis cancel signal the engine and the agent loops poll, tear down blocking
 * signals, abort in-flight browser-agent sessions and close the open epochs. What
 * this service adds is the <b>cause</b>: the agent's reason is recorded on the run
 * before the stop, so anyone reading the run afterwards (the agent that was
 * blocked on {@code wait_run}, the user, a later {@code get_run}) sees WHY it
 * stopped instead of a bare CANCELLED.
 *
 * <p>Authorization is deliberately NOT done here: each tool module resolves and
 * scope-checks the run exactly like its own {@code get_run} does, then hands the
 * already-authorized run over.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunStopService {

    /** Free-text cause the agent gave for the stop. Read back by the run reports. */
    public static final String META_STOP_REASON = "__stopReason__";
    /**
     * Who asked for the stop: {@code "agent"} on this path, {@code "user"} when it came
     * from the Stop/Cancel buttons (written by {@code WorkflowRunController}). Both write
     * it, so a run report can tell a deliberate stop from a crash, and by whom.
     */
    public static final String META_STOPPED_BY = "__stoppedBy__";
    /** When the stop was requested (ISO-8601). */
    public static final String META_STOPPED_AT = "__stoppedAt__";

    /** Longest reason we persist. A reason is a sentence, not a report. */
    static final int MAX_REASON_LENGTH = 500;

    private final WorkflowRunRepository runRepository;
    private final WorkflowResumeService resumeService;

    /**
     * How hard to stop.
     *
     * <ul>
     *   <li>{@link #CANCEL} - terminal. The run ends CANCELLED and no trigger can
     *       fire on it again. This is the default: it is what "stop this, it is
     *       going wrong" means.</li>
     *   <li>{@link #GRACEFUL} - non-terminal. The in-flight epoch is closed and the
     *       run returns to WAITING_TRIGGER, so a reusable trigger (webhook,
     *       schedule, chat) can fire it again later.</li>
     * </ul>
     */
    public enum Mode {
        CANCEL, GRACEFUL;

        /** Wire value used by the tool surface ("cancel" / "graceful"). */
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Parse the agent-supplied {@code mode} parameter.
         *
         * @return empty when the value is not a known mode (the caller turns that
         *         into an explicit error rather than silently defaulting).
         */
        public static Optional<Mode> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.of(CANCEL);
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "cancel" -> Optional.of(CANCEL);
                case "graceful" -> Optional.of(GRACEFUL);
                default -> Optional.empty();
            };
        }
    }

    /**
     * Outcome of a stop request.
     *
     * @param runId           public run id that was stopped
     * @param previousStatus  status the run had when the stop was requested
     * @param status          status the run has after the stop
     * @param mode            mode that was applied
     * @param reason          the agent's reason, already trimmed/capped (may be null)
     * @param stoppedBy       who requested it (always "agent" on this path)
     * @param alreadyTerminal true when the run had already ended, so nothing was stopped
     */
    public record StopOutcome(String runId, String previousStatus, String status, Mode mode,
                              String reason, String stoppedBy, boolean alreadyTerminal) {}

    /**
     * Stop {@code runIdPublic} on behalf of an agent.
     *
     * <p>Idempotent for a run that has already ended: nothing is written, nothing is
     * cancelled, and the outcome carries {@code alreadyTerminal=true} so the caller
     * can tell the agent "there was nothing left to stop" instead of failing.
     *
     * @throws IllegalArgumentException when the run does not exist
     * @throws IllegalStateException    when the run is alive but not stoppable in the
     *                                  requested mode (propagated from
     *                                  {@link WorkflowResumeService})
     */
    public StopOutcome stop(String runIdPublic, Mode mode, String reason, String stoppedBy) {
        WorkflowRunEntity run = runRepository.findByRunIdPublic(runIdPublic)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runIdPublic));

        Mode effectiveMode = mode != null ? mode : Mode.CANCEL;
        String cleanReason = sanitizeReason(reason);
        RunStatus previousStatus = run.getStatus();

        if (previousStatus != null && previousStatus.isTerminal()) {
            log.info("[AgentStop] Run {} already terminal ({}) - nothing to stop", runIdPublic, previousStatus);
            return nothingToStop(runIdPublic, previousStatus, effectiveMode);
        }

        // The cause travels WITH the stop, not as a separate write: the run read above is
        // detached (no open session on this path), so saving it here would push a metadata
        // map read OUTSIDE the run's lock and clobber whatever the stop itself writes.
        // The stop already holds the row under a pessimistic lock, so it merges the cause
        // into the same write. One locked write, no lost update, no ordering race.
        Map<String, Object> stopMetadata = buildStopMetadata(cleanReason, stoppedBy);

        try {
            switch (effectiveMode) {
                case CANCEL -> resumeService.cancelWorkflow(runIdPublic, stopMetadata);
                case GRACEFUL -> resumeService.stopWorkflow(runIdPublic, stopMetadata);
            }
        } catch (IllegalStateException notStoppable) {
            // The status was read a moment ago, outside the lock. A run an agent is racing
            // to stop very often ENDS on its own in that window, and the stop then refuses
            // a now-terminal run. That is not an error for the caller: there was simply
            // nothing left to stop. Re-read and report it as such; a genuinely alive but
            // non-stoppable run (PENDING, or graceful on a parked run) still propagates.
            RunStatus current = runRepository.findByRunIdPublic(runIdPublic)
                    .map(WorkflowRunEntity::getStatus)
                    .orElse(null);
            if (current != null && current.isTerminal()) {
                log.info("[AgentStop] Run {} reached {} before the stop landed - nothing to stop",
                        runIdPublic, current);
                return nothingToStop(runIdPublic, current, effectiveMode);
            }
            throw notStoppable;
        }

        WorkflowRunEntity after = runRepository.findByRunIdPublic(runIdPublic).orElse(null);
        RunStatus finalStatus = after != null ? after.getStatus() : previousStatus;

        // Did the stop actually land? BOTH modes have a silent early-return for a run that
        // reached a terminal state between the status read above and the locked write:
        // cancel skips an already-CANCELLED run, graceful skips any terminal run. Neither
        // throws, and neither merges the cause. Answering "stopped, reason: <your sentence>"
        // then contradicts a row that carries someone else's cause, or none.
        // The stamp we sent is the evidence: it is present on the row only if the merge ran.
        if (!stopLanded(after, stopMetadata)) {
            log.info("[AgentStop] Run {} was already {} when the stop landed - nothing to stop",
                    runIdPublic, finalStatus);
            return nothingToStop(runIdPublic, finalStatus, effectiveMode);
        }

        log.info("[AgentStop] Run {} stopped by {} (mode={}, {} -> {}), reason={}",
                runIdPublic, stoppedBy, effectiveMode.wireValue(),
                statusName(previousStatus), statusName(finalStatus), cleanReason);

        return new StopOutcome(runIdPublic, statusName(previousStatus), statusName(finalStatus),
                effectiveMode, cleanReason, stoppedBy, false);
    }

    /**
     * True when the stop we just asked for actually wrote its cause on the run.
     *
     * <p>The timestamp we generated for THIS stop is the evidence: the stop merges the whole
     * map under the run's lock, so the stamp is on the row if and only if the merge ran.
     * Comparing it also settles the concurrent case, where another stopper wrote its own
     * cause a moment earlier and the engine then skipped ours.
     */
    private static boolean stopLanded(WorkflowRunEntity run, Map<String, Object> stopMetadata) {
        if (run == null || run.getMetadata() == null) {
            return false;
        }
        Object expected = stopMetadata.get(META_STOPPED_AT);
        return expected != null && expected.equals(run.getMetadata().get(META_STOPPED_AT));
    }

    /**
     * Outcome for the paths where NOTHING was written: the run had already ended, so no
     * cause was persisted.
     *
     * <p>The reason and the author are deliberately dropped here. Echoing back what the
     * caller asked for would make the response claim a cause that {@code get_run} will
     * never show, and the two would contradict each other for the same run.
     */
    private static StopOutcome nothingToStop(String runIdPublic, RunStatus status, Mode mode) {
        return new StopOutcome(runIdPublic, statusName(status), statusName(status),
                mode, null, null, true);
    }

    /**
     * The run-metadata entries describing this stop, merged into the run by the stop itself.
     *
     * <p>The three keys are always written AS A SET, with {@code null} meaning "remove this
     * key" (the merge honours that). They describe one single stop, so they must never be
     * mixed: a run gracefully stopped with a reason, restarted, then stopped again with no
     * reason would otherwise keep the first sentence and attribute it to the second stopper.
     */
    /**
     * The stop-cause entries for a stop with no explanation, for callers outside this
     * service (the user's Stop/Cancel buttons). Shares ONE builder with the agent path so
     * the "three keys, always written as a set" rule cannot rot into two divergent copies.
     */
    public static Map<String, Object> stopMetadata(String stoppedBy) {
        return buildStopMetadata(null, stoppedBy);
    }

    private static Map<String, Object> buildStopMetadata(String reason, String stoppedBy) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_STOP_REASON, reason);
        metadata.put(META_STOPPED_BY, stoppedBy != null && !stoppedBy.isBlank() ? stoppedBy : null);
        metadata.put(META_STOPPED_AT, Instant.now().toString());
        return metadata;
    }

    /**
     * Drop the "why this run was stopped" metadata, because the run is alive again and the
     * cause no longer describes it.
     *
     * <p>Called from every path that revives a stopped run: the next trigger fire
     * ({@code ReusableTriggerService}), an explicit reactivate and a step rerun. Without
     * it the cause sticks to the run forever and later reports of a FRESH epoch show it
     * beside a COMPLETED status, which is the opposite of what it is meant to say.
     *
     * <p>No-op, and no allocation, for the common case of a run nobody ever stopped.
     */
    public static void clearStopMetadata(WorkflowRunEntity run) {
        Map<String, Object> metadata = run != null ? run.getMetadata() : null;
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (!metadata.containsKey(META_STOP_REASON)
                && !metadata.containsKey(META_STOPPED_BY)
                && !metadata.containsKey(META_STOPPED_AT)) {
            return;
        }
        Map<String, Object> cleaned = new HashMap<>(metadata);
        cleaned.remove(META_STOP_REASON);
        cleaned.remove(META_STOPPED_BY);
        cleaned.remove(META_STOPPED_AT);
        run.setMetadata(cleaned);
    }

    /** Trim and cap the agent-supplied reason; blank becomes null (no key written). */
    private static String sanitizeReason(String reason) {
        if (reason == null) return null;
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= MAX_REASON_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_REASON_LENGTH);
    }

    private static String statusName(RunStatus status) {
        return status != null ? status.name() : null;
    }
}
