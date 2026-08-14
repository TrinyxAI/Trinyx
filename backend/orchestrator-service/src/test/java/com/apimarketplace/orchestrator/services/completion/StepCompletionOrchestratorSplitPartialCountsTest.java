package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import com.apimarketplace.orchestrator.services.persistence.StepPersistenceResult;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A split that continued past failed items must leave a FAILURE IN THE NODE'S TALLY, not only a
 * per-epoch marker.
 *
 * <p>The sync inline split records ONE aggregate COMPLETED for the node and signals the partial
 * failure through {@code split_partial_failure}. The marker alone demotes the node to
 * PARTIAL_SUCCESS on the REST path, so the node used to render an amber border next to a badge
 * reading zero failures - and the streaming path, which derives purely from the counts, still
 * called the same node completed. Three surfaces, two answers, and the amber was unexplainable to
 * anyone reading the badge underneath it.
 *
 * <p>The counted failure is what makes the border legible, and it is deliberately ONE (node
 * granularity, symmetric with the single aggregate COMPLETED) because the orchestrator's own
 * invariant requires every NodeCounts increment to be mirrored one-for-one into
 * {@code workflow_epochs}, and {@code recordNodeCount} has no multiplicity.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepCompletionOrchestrator - a continue-anyway split counts its failure")
class StepCompletionOrchestratorSplitPartialCountsTest {

    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private MergeIntegrationService mergeIntegrationService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private WorkflowEntityResolverService entityResolverService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private WorkflowMetrics workflowMetrics;
    @Mock private WorkflowExecution execution;

    private StepCompletionOrchestrator orchestrator;

    private static final String RUN_ID = "run-split-partial";
    private static final String NODE_ID = "core:fan_out";
    private static final String NODE_LABEL = "fan_out";
    private static final String TRIGGER_ID = "trigger:start";
    private static final int EPOCH = 2;

    @BeforeEach
    void setUp() {
        orchestrator = new StepCompletionOrchestrator(
                persistenceService, eventPublisher, mergeIntegrationService, stateSnapshotService,
                workflowEpochService, entityResolverService, creditClient, workflowMetrics);
        lenient().when(execution.getRunId()).thenReturn(RUN_ID);
        lenient().when(execution.getWorkflowRunId()).thenReturn(UUID.randomUUID());
        lenient().when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
        lenient().when(stateSnapshotService.recordNodeCompletionAndGetCounts(
                        any(), any(), any(), any(), anyInt(), anyLong()))
                .thenReturn(new StateSnapshot.NodeCounts(0, 1, 0, 0, 0L, 0L, 0L));
        lenient().when(stateSnapshotService.incrementNodeCountsOnly(any(), any(), any(), anyInt()))
                .thenReturn(new StateSnapshot.NodeCounts(0, 1, 1, 0, 0L, 0L, 0L));
    }

    /** The summary a continue-anyway split produces: globally COMPLETED, carrying the marker. */
    private StepCompletionContext splitSummary(boolean partial) {
        Map<String, Object> output = partial
                ? Map.of(
                        "split_item_count", 3,
                        ExecutionMetadataKeys.SPLIT_PARTIAL_FAILURE, true,
                        ExecutionMetadataKeys.SPLIT_FAILED_ITEM_INDICES, List.of(1),
                        ExecutionMetadataKeys.SPLIT_COMPLETED_ITEM_INDICES, List.of(0, 2))
                : Map.of("split_item_count", 3);
        StepExecutionResult result = StepExecutionResult.success(NODE_ID, output, 100);
        return StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, result, 0, 0, EPOCH);
    }

    @Test
    @DisplayName("The failure lands in NodeCounts, so the badge shows the red tally the amber border claims")
    void partialSplitCountsTheFailure() {
        orchestrator.complete(splitSummary(true), TRIGGER_ID);

        verify(stateSnapshotService).incrementNodeCountsOnly(RUN_ID, NODE_ID, "FAILED", 1);
        verify(stateSnapshotService).markNodePartialFailure(RUN_ID, TRIGGER_ID, EPOCH, NODE_ID);
    }

    @Test
    @DisplayName("The per-epoch store is incremented too, as the orchestrator's mirroring invariant requires")
    void partialSplitMirrorsIntoWorkflowEpochs() {
        // The two counters are parallel and additive; the per-epoch inspector reads only the
        // second one. Incrementing NodeCounts alone would make the two stores disagree.
        orchestrator.complete(splitSummary(true), TRIGGER_ID);

        verify(workflowEpochService).recordNodeCount(RUN_ID, EPOCH, NODE_ID, "FAILED", TRIGGER_ID);
        verify(workflowEpochService).recordNodeCount(RUN_ID, EPOCH, NODE_ID, "COMPLETED", TRIGGER_ID);
    }

    @Test
    @DisplayName("A split where everything succeeded counts no failure and gets no marker")
    void cleanSplitStaysClean() {
        // The counterexample that keeps the rule honest: the marker is what distinguishes the two,
        // and a clean split must stay green on every surface.
        orchestrator.complete(splitSummary(false), TRIGGER_ID);

        verify(stateSnapshotService, never()).incrementNodeCountsOnly(any(), any(), eq("FAILED"), anyInt());
        verify(stateSnapshotService, never()).markNodePartialFailure(any(), any(), anyInt(), any());
        verify(workflowEpochService, never()).recordNodeCount(RUN_ID, EPOCH, NODE_ID, "FAILED", TRIGGER_ID);
    }
}
