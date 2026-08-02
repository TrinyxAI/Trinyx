package com.apimarketplace.orchestrator.domain.workflow;

/**
 * Iteration budget applied to a loop-back.
 *
 * <p>Resolution order for the cap actually applied:
 * <ol>
 *   <li>the loop-back's own declared cap (edge marker, or the loop Core's maxIterations)</li>
 *   <li>{@link #defaultMax()} - {@code workflow.execution.default-max-iterations}</li>
 * </ol>
 * then clamped to {@link #hardMax()} ({@code workflow.execution.max-allowed-iterations}).
 *
 * <p>Built from configuration by {@code WorkflowExecutionConfig.resolveLoopIterationLimits}. The
 * {@code workflowOverride} parameter there is a seam for a future per-workflow setting; no such
 * setting exists yet, so every call site passes null and the global default applies.
 */
public record LoopIterationLimits(int defaultMax, int hardMax) {

    /** Matches the historical hardcoded default (10) and the builder's authoring ceiling (10000). */
    public static final LoopIterationLimits FALLBACK = new LoopIterationLimits(10, 10_000);

    public LoopIterationLimits {
        if (defaultMax < 1) defaultMax = 1;
        if (hardMax < 1) hardMax = 1;
    }

    /**
     * Apply this budget to a declared cap.
     *
     * @param declared the cap authored on the loop-back, or null when none was authored
     */
    public int resolve(Integer declared) {
        int requested = (declared != null && declared > 0) ? declared : defaultMax;
        return Math.min(requested, hardMax);
    }
}
