package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cycle's outcome is BINARY, and deliberately so.
 *
 * <p>PARTIAL_SUCCESS describes a NODE that finished with some of its items failed - a real state
 * you can read off the node's own tally of green and red. A run or an epoch is not a collection
 * of items: it either did what it was asked or it did not, and "partially succeeded" gives the
 * user nothing to act on. Any failure makes the cycle FAILED, and the failing nodes carry the
 * detail.
 *
 * <p>This test used to assert the opposite (a mix answered PARTIAL_SUCCESS). It was rewritten
 * with the rule, not adapted to it.
 */
@DisplayName("StateSnapshotService.deriveCycleStatus")
class StateSnapshotCycleResultTest {

    @Test
    @DisplayName("A cycle with any failure is FAILED, even when other nodes succeeded")
    void anyFailureIsFailed() {
        assertThat(StateSnapshotService.deriveCycleStatus(true)).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("A cycle with no failure is COMPLETED")
    void noFailureIsCompleted() {
        assertThat(StateSnapshotService.deriveCycleStatus(false)).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("A cycle is NEVER reported as partially successful")
    void neverPartial() {
        // The regression guard: PARTIAL_SUCCESS is a node-level verdict and must not leak up to
        // a run or an epoch, whichever mode produced it.
        assertThat(StateSnapshotService.deriveCycleStatus(true)).isNotEqualTo(RunStatus.PARTIAL_SUCCESS);
        assertThat(StateSnapshotService.deriveCycleStatus(false)).isNotEqualTo(RunStatus.PARTIAL_SUCCESS);
    }

    @Test
    @DisplayName("Both outcomes are terminal, so a finished run always reads as finished")
    void bothOutcomesAreTerminal() {
        assertThat(StateSnapshotService.deriveCycleStatus(true).isTerminal()).isTrue();
        assertThat(StateSnapshotService.deriveCycleStatus(false).isTerminal()).isTrue();
    }
}
