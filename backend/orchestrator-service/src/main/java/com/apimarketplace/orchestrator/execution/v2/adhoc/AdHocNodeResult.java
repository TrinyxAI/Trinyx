package com.apimarketplace.orchestrator.execution.v2.adhoc;

import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of one standalone node run, in the shape the agent reads it.
 *
 * <p>Failures are values here, not exceptions: an agent that asked "does this config work?"
 * needs the answer either way, and a stack trace turned into an error string is how host names,
 * DSNs and credentials leak into a transcript.
 *
 * @param nodeType   the canonical type that ran
 * @param status     COMPLETED / FAILED / AWAITING_SIGNAL / TIMED_OUT / NOT_STARTED
 * @param output     the node output, mapped to the shape a workflow would persist
 * @param error      human-readable reason when it did not complete, else null
 * @param durationMs wall-clock time of the whole call, build included
 * @param note       what the agent must know about this run beyond success/failure, else null
 */
public record AdHocNodeResult(
        String nodeType,
        String status,
        Map<String, Object> output,
        String error,
        long durationMs,
        String note
) {

    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String AWAITING_SIGNAL = "AWAITING_SIGNAL";
    public static final String TIMED_OUT = "TIMED_OUT";
    /**
     * The batch ran out of time before this entry started.
     *
     * <p>Its own value rather than {@link #TIMED_OUT} because the two call for opposite actions: a
     * timed-out entry was running and may already have had its effect, so resending it can double
     * that effect, while a never-started one did nothing and is safe to resend.
     */
    public static final String NOT_STARTED = "NOT_STARTED";

    public AdHocNodeResult {
        output = output != null ? new LinkedHashMap<>(output) : new LinkedHashMap<>();
    }

    public boolean completed() {
        return COMPLETED.equals(status);
    }

    /**
     * Map an engine result onto the agent-facing shape.
     *
     * <p>{@code AWAITING_SIGNAL} gets its own status rather than being folded into failure: the
     * node did not go wrong, it asked to be resumed, and no resume is possible without a run.
     * Reporting that as a failure would send the agent looking for a configuration mistake that
     * does not exist.
     */
    static AdHocNodeResult of(String nodeType, NodeExecutionResult result,
                              Map<String, Object> mappedOutput, long durationMs) {
        if (result == null) {
            return executionFailure(nodeType, "The node returned no result.", durationMs);
        }
        if (result.isAwaitingSignal()) {
            return new AdHocNodeResult(nodeType, AWAITING_SIGNAL, mappedOutput, null, durationMs,
                    "This node suspends on a signal (a timer, an approval, an interface interaction) "
                            + "that only a real run can resolve. It was built and started correctly; "
                            + "nothing further will happen here. Use workflow(action='execute') to run "
                            + "it inside a workflow.");
        }
        if (result.isFailure()) {
            String message = result.errorMessage() != null
                    ? result.errorMessage().orElse("The node failed without a message.")
                    : "The node failed without a message.";
            return new AdHocNodeResult(nodeType, FAILED, mappedOutput, message, durationMs, null);
        }
        if (result.isSkipped()) {
            return new AdHocNodeResult(nodeType, COMPLETED, mappedOutput, null, durationMs,
                    "The node decided to skip itself. Standalone it has no predecessors, so a skip "
                            + "usually means it was waiting for inputs that only a DAG provides.");
        }
        return new AdHocNodeResult(nodeType, COMPLETED, mappedOutput, null, durationMs, null);
    }

    static AdHocNodeResult buildFailure(String nodeType, String message, long durationMs) {
        return new AdHocNodeResult(nodeType, FAILED, Map.of(),
                "The node could not be built from this configuration: " + message, durationMs, null);
    }

    static AdHocNodeResult executionFailure(String nodeType, String message, long durationMs) {
        return new AdHocNodeResult(nodeType, FAILED, Map.of(), message, durationMs, null);
    }

    /**
     * A batch entry cut off by the whole-call deadline.
     *
     * <p>Quotes the milliseconds it actually consumed rather than a seconds figure: the message
     * sits beside {@code duration_ms} in the report, and a rounded number there disagrees with the
     * one next to it, which is the confusion the per-entry timing exists to remove. The
     * single-entry {@link #timeout} quotes seconds instead because the figure it reports is the
     * configured ceiling, not a measurement - the same sentence, one measured, one declared, which
     * is why both render through {@link #stoppedAfter} rather than being written out twice.
     */
    static AdHocNodeResult timedOutAfter(String nodeType, long elapsedMs) {
        return new AdHocNodeResult(nodeType, TIMED_OUT, Map.of(),
                stoppedAfter(elapsedMs + "ms"), elapsedMs, null);
    }

    /**
     * An item the batch never got to start before its budget ran out.
     *
     * <p>Distinct from {@link #timedOutAfter} on purpose: that one says the node was running and was
     * stopped, which for an entry that never started is false in both halves and would send the
     * agent looking for side effects that cannot exist. Its duration is 0 for the same reason: it
     * consumed none, and the batch total is the sum of the entries.
     */
    static AdHocNodeResult neverStarted(String nodeType, String reason, String remedy) {
        // The remedy goes in `note`, not appended to `error`: both agent-facing surfaces say
        // `note` is where an entry says what to do about itself, and NOT_STARTED is the status
        // whose whole point is that there IS something to do. It also travels with the reason
        // rather than being a fixed suffix, because "send a smaller batch" is wrong for a pool
        // refusal and for an interrupt, neither of which has to do with how many entries were sent.
        return new AdHocNodeResult(nodeType, NOT_STARTED, Map.of(),
                reason + ", so it never ran and had no effect.", 0L, remedy);
    }

    static AdHocNodeResult timeout(String nodeType, long seconds, long durationMs) {
        return new AdHocNodeResult(nodeType, TIMED_OUT, Map.of(),
                stoppedAfter(seconds + " seconds"), durationMs, null);
    }

    /**
     * The one sentence a stopped node gets, whoever stopped it. The warning about effects already
     * had is the part that must never drift between the two callers, since acting on it is what
     * keeps an agent from resending work that may already have landed.
     */
    private static String stoppedAfter(String howLong) {
        return "The node was still running after " + howLong + " and was stopped. "
                + "Whatever it had already done outside the platform (a request sent, a row "
                + "written) is NOT undone.";
    }
}
