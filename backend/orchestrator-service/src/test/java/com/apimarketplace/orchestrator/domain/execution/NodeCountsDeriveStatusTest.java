package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The NODE-level accumulation rule, which every producer of a node status now derives from
 * instead of writing out for itself.
 *
 * <p>A node holds items and runs repeatedly, so it can genuinely be half-done. That is the whole
 * difference with a run or a cycle, which is binary. The reason it lives here: the streaming path
 * and the REST path each had their own copy, they drifted, and the same node came out red live and
 * amber after a reload.
 */
@DisplayName("NodeCounts.deriveStatus - the status a node reports from its own tally")
class NodeCountsDeriveStatusTest {

    private static StateSnapshot.NodeCounts counts(int running, int completed, int failed, int skipped) {
        return new StateSnapshot.NodeCounts(running, completed, failed, skipped, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("Some completed AND some failed is partial_success, not failed")
    void mixedTallyIsPartialSuccess() {
        // The case the whole change exists for: spawn 1 failed, spawn 2 succeeded. Answering
        // "failed" here paints a red border beside the green count in the node's own badge.
        assertThat(counts(0, 1, 1, 0).deriveStatus()).isEqualTo("partial_success");
        assertThat(counts(0, 7, 3, 2).deriveStatus()).isEqualTo("partial_success");
    }

    @Test
    @DisplayName("Failures with nothing completed stay plainly failed")
    void allFailedIsFailed() {
        assertThat(counts(0, 0, 2, 0).deriveStatus()).isEqualTo("failed");
        // Skipped siblings do not turn a pure failure into a partial one.
        assertThat(counts(0, 0, 1, 4).deriveStatus()).isEqualTo("failed");
    }

    @Test
    @DisplayName("A clean tally stays completed")
    void allCompletedIsCompleted() {
        assertThat(counts(0, 3, 0, 0).deriveStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("A live execution outranks whatever the tally accumulated")
    void runningWinsOverAccumulation() {
        // Otherwise a node re-running after an earlier failure would show its old verdict
        // instead of the fact that it is working right now.
        assertThat(counts(1, 1, 1, 0).deriveStatus()).isEqualTo("running");
        assertThat(counts(1, 0, 0, 0).deriveStatus()).isEqualTo("running");
    }

    @Test
    @DisplayName("Skipped only wins when nothing completed")
    void skippedOnlyWithoutCompletions() {
        assertThat(counts(0, 0, 0, 3).deriveStatus()).isEqualTo("skipped");
        // A node that both ran and was skipped on other items did do work: completed wins.
        assertThat(counts(0, 2, 0, 3).deriveStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("An empty tally is pending, so an untouched node is not called done")
    void emptyTallyIsPending() {
        assertThat(StateSnapshot.NodeCounts.zero().deriveStatus()).isEqualTo("pending");
    }
}
