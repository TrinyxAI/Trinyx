package com.apimarketplace.orchestrator.services.credit;

/**
 * Single vocabulary for "the tenant ran out of credits" across the execution engine
 * and the HTTP layer.
 *
 * <p>Before this existed, a zero-balance tenant was refused BEFORE any epoch opened
 * (pre-queue gate in {@code ReusableTriggerService} plus one gate per dispatch
 * service/controller). Nothing was persisted, so a scheduled or webhook-driven
 * workflow left no trace at all: the only evidence was an orchestrator log line.
 * The gate now lives inside node execution ({@link NodeCreditGate}), so the trigger
 * node itself fails with {@link #MESSAGE} and the ordinary failure machinery marks
 * every downstream node SKIPPED.
 *
 * <p>{@link #isCreditExhausted(String)} lets the HTTP layer map that node failure
 * back to 402 for interactive callers (the frontend "Insufficient credits" modal
 * keys on the status code), without every controller re-deriving the wording.
 */
public final class CreditExhaustion {

    private CreditExhaustion() {
    }

    /**
     * Machine-readable marker written to the failed node's {@code output.error_code}.
     * Mirrors the {@code error_code} convention already read by the StopOnError path.
     */
    public static final String ERROR_CODE = "CREDIT_EXHAUSTED";

    /**
     * The node-level error message. Kept as a single literal because
     * {@link #isCreditExhausted(String)} matches on it: a failed trigger node reports
     * this message up through {@code TriggerExecutionResult.message()}, which is the
     * only channel the HTTP callers see.
     */
    public static final String MESSAGE =
        "Out of credits: this workflow cannot run. Add credits to run it again.";

    /**
     * True when {@code message} was produced by the credit gate. Matches on the
     * {@link #ERROR_CODE} token as well so a caller that wraps or prefixes the node
     * error (e.g. "V2 execution failed: ...") is still recognised.
     */
    public static boolean isCreditExhausted(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains(ERROR_CODE) || message.contains(MESSAGE);
    }
}
