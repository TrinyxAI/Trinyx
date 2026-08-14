package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node status the LIVE canvas receives over the socket.
 *
 * <p>This is the path that actually runs: {@code SnapshotService.buildSteps} feeds the WS
 * snapshot. It had its own copy of the accumulation rule which tested {@code failed} before
 * {@code completed}, so a node holding both came out as a plain "failed" while the REST payload
 * called the same node partial. Measured end to end against a build with that copy still broken,
 * the node renders emerald on the live canvas and only turns amber after a reload.
 *
 * <p>The rule alone is not enough to pin the behaviour, which is why this test goes through
 * {@code buildSteps} rather than the derivation: that method deliberately OVERRIDES the derived
 * status for a node that is ready, awaiting a signal, or carries only historical counts from
 * closed epochs. The partial verdict must survive the overrides in the one case that matters (the
 * node is terminal in the current epoch) and must still lose to them elsewhere.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotService - the node status pushed to the live canvas")
class SnapshotServicePartialStatusTest {

    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    private SnapshotService snapshotService;

    private static final String NODE = "core:boom";
    private static final String TRIGGER = "trigger:start";

    @BeforeEach
    void setUp() {
        snapshotService = new SnapshotService(
                stateSnapshotService, streamingService, runningNodeTracker, workflowEpochService,
                runRepository, 60L, 1800L);
    }

    /**
     * The real sequence rather than a hand-built tally: the node fails, the user fixes it and
     * re-runs, it succeeds. Both marks go through the production API, so the counts accumulate
     * the way they do at runtime (failed=1, completed=1) and the node ends terminal in the epoch.
     */
    private StateSnapshot snapshotWithMixedNode() {
        return StateSnapshot.empty()
                .withDagState(TRIGGER, DagState.initial().advanceEpoch(1))
                .markNodeFailed(TRIGGER, NODE, 1)
                .markNodeCompleted(TRIGGER, NODE, 1);
    }

    @SuppressWarnings("unchecked")
    private String statusOf(StateSnapshot snapshot, Set<String> awaitingSignalNodeIds) throws Exception {
        Method buildSteps = SnapshotService.class.getDeclaredMethod(
                "buildSteps", StateSnapshot.class, Set.class, Map.class, Map.class, Map.class, Set.class);
        buildSteps.setAccessible(true);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) buildSteps.invoke(
                snapshotService, snapshot, awaitingSignalNodeIds, Map.of(), Map.of(), Map.of(), Set.of(NODE));
        Map<String, Object> step = steps.stream()
                .filter(s -> NODE.equals(s.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("node missing from the pushed steps: " + steps));
        return String.valueOf(step.get("status"));
    }

    @Test
    @DisplayName("A node holding both a success and a failure is pushed as partial_success, not failed")
    void mixedNodeIsPushedAsPartial() throws Exception {
        // The rerun case: spawn 1 failed, spawn 2 succeeded. Pushing "failed" here is what made
        // the live canvas disagree with the same node after a reload.
        assertThat(statusOf(snapshotWithMixedNode(), Set.of())).isEqualTo("partial_success");
    }

    @Test
    @DisplayName("The pushed statusCounts carry both tallies, so the badge and the border agree")
    void mixedNodeShipsBothCounts() throws Exception {
        // The border is only defensible because these two numbers travel with it.
        Method buildSteps = SnapshotService.class.getDeclaredMethod(
                "buildSteps", StateSnapshot.class, Set.class, Map.class, Map.class, Map.class, Set.class);
        buildSteps.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) buildSteps.invoke(
                snapshotService, snapshotWithMixedNode(), Set.of(), Map.of(), Map.of(), Map.of(), Set.of(NODE));
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) steps.stream()
                .filter(s -> NODE.equals(s.get("id"))).findFirst().orElseThrow().get("statusCounts");

        assertThat(counts).containsEntry("completed", 1).containsEntry("failed", 1);
    }

    @Test
    @DisplayName("Awaiting a signal still outranks the accumulated partial verdict")
    void awaitingSignalStillWins() throws Exception {
        // The counterexample that keeps the override order honest: what the node is doing NOW
        // beats what it accumulated, otherwise a blocked node reads as finished.
        assertThat(statusOf(snapshotWithMixedNode(), Set.of(NODE))).isEqualTo("awaiting_signal");
    }
}
