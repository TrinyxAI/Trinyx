package com.apimarketplace.orchestrator.persistence;

import java.time.Instant;

/**
 * The window an epoch spent EXECUTING: from its first node starting to its last
 * node finishing, aggregated over {@code workflow_step_data}.
 *
 * <p>This exists because the epoch header's {@code duration_ms} does not answer
 * that question. {@code StateSnapshotService.closeEpoch} stamps it as
 * {@code now - startedAt} at CLOSE time, and an epoch is closed only when it is
 * reconciled: the next fire's {@code ReusableTriggerService.resetForNextCycle}
 * (itself skipped while a blocking signal or in-flight agent remains), a resume, or
 * a restart recovery sweep ({@code closeAllActiveEpochs}). All of those can land
 * hours or days after the last node finished, so the column measures the epoch's
 * LIFETIME. On prod it produced 6h01m and 32h42m for epochs whose step rows span 5
 * to 35 seconds.
 *
 * <p>What this measure does NOT shorten is a wait that happens INSIDE the graph: an
 * approval's step row is written spanning the whole wait ({@code startTime} = when
 * the signal was created), so a workflow that really sat for an hour on an approval
 * still reports the hour. That is the honest answer. What is excluded is only the
 * idle tail AFTER the last node finished, which is the entire aberration.
 */
public record EpochWorkWindowProjection(String runId, Integer epoch, Instant firstStart, Instant lastEnd) {

    /**
     * The executed window in milliseconds, or null when it cannot be trusted:
     * a missing endpoint, or a negative span (writers disagreeing, clock skew).
     * Callers render nothing rather than a wrong figure.
     */
    public Long durationMs() {
        if (firstStart == null || lastEnd == null) return null;
        long span = lastEnd.toEpochMilli() - firstStart.toEpochMilli();
        return span >= 0 ? span : null;
    }
}
