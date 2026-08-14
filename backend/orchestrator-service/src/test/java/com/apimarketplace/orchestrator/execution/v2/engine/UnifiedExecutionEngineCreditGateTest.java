package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.TriggerNode;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.credit.CreditExhaustion;
import com.apimarketplace.orchestrator.services.credit.NodeCreditGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring of {@link NodeCreditGate} into the step-by-step / trigger-fire dispatch
 * ({@code UnifiedExecutionEngine.executeSingleNode}).
 *
 * <p>This is the seam that makes an out-of-credit run VISIBLE. Every trigger fire
 * executes its trigger node through this method, so denying here produces a FAILED
 * trigger node and hands the engine's ordinary failure cascade the job of marking
 * the downstream nodes SKIPPED. Pre-fix the fire was refused before the epoch even
 * opened and none of that existed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UnifiedExecutionEngine - out-of-credit node gate")
class UnifiedExecutionEngineCreditGateTest {

    private static final String RUN_ID = "run-broke";
    private static final String TENANT = "tenant-broke";
    private static final String TRIGGER_ID = "trigger:daily";

    @Mock private V2WorkflowFinalizer workflowFinalizer;
    @Mock private V2AutoScheduler autoScheduler;
    @Mock private V2StepByStepScheduler stepByStepScheduler;
    @Mock private ReadyNodeCalculator readyNodeCalculator;
    @Mock private BackEdgeHandler backEdgeHandler;
    @Mock private SplitNodeExecutor splitNodeExecutor;
    @Mock private SplitAwareNodeExecutor splitAwareExecutor;
    @Mock private SplitMergeHandler splitMergeHandler;
    @Mock private SplitAggregateHandler splitAggregateHandler;
    @Mock private SplitContextManager splitContextManager;
    @Mock private NodeSearchService nodeSearchService;
    @Mock private V2SkipPropagationService skipPropagationService;
    @Mock private CreditBudgetService creditBudgetService;

    @Mock private NodeCreditGate nodeCreditGate;
    @Mock private ExecutionTree tree;
    @Mock private WorkflowExecution execution;
    @Mock private V2ExecutionEventService eventService;
    @Mock private WorkflowPlan plan;

    private UnifiedExecutionEngine engine;
    private TriggerNode triggerNode;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        engine = new UnifiedExecutionEngine(
                workflowFinalizer, autoScheduler, stepByStepScheduler, readyNodeCalculator,
                backEdgeHandler, splitNodeExecutor, splitAwareExecutor, splitMergeHandler,
                splitAggregateHandler, splitContextManager, nodeSearchService,
                skipPropagationService, creditBudgetService);
        engine.setNodeCreditGate(nodeCreditGate);

        triggerNode = new TriggerNode(TRIGGER_ID,
                new Trigger(TRIGGER_ID, "daily", "single", "schedule", Map.of()));

        context = ExecutionContext.create(
                RUN_ID, "wf-run-1", TENANT, "0", 0, TRIGGER_ID, 4, 0, Map.of(), plan);

        when(tree.getRunId()).thenReturn(RUN_ID);
        when(nodeSearchService.findNodeFromAllRoots(tree, TRIGGER_ID)).thenReturn(triggerNode);
        when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of(TRIGGER_ID, triggerNode));
        when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(Set.of());
        when(backEdgeHandler.hasBackEdge(any(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("Out of credits: the trigger node is FAILED with the credit message and its body never runs")
    void outOfCreditsFailsTheTriggerNodeWithoutExecutingIt() {
        when(nodeCreditGate.denyOrNull(TRIGGER_ID, TENANT))
                .thenReturn(NodeCreditGate.exhaustedResult(TRIGGER_ID));

        StepByStepExecutionResult result = engine.executeSingleNode(
                TRIGGER_ID, tree, context, execution, eventService, null);

        assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.FAILED);
        assertThat(result.nodeResult().errorMessage()).contains(CreditExhaustion.MESSAGE);
        assertThat(CreditExhaustion.isCreditExhausted(result.getErrorMessage())).isTrue();
        // The node body is bypassed entirely: no dispatch to the split-aware executor.
        verify(splitAwareExecutor, never())
                .execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Out of credits: the failure cascades SKIPPED to the downstream nodes")
    void outOfCreditsCascadesSkipToSuccessors() {
        when(nodeCreditGate.denyOrNull(TRIGGER_ID, TENANT))
                .thenReturn(NodeCreditGate.exhaustedResult(TRIGGER_ID));

        engine.executeSingleNode(TRIGGER_ID, tree, context, execution, eventService, null);

        // Same cascade any other node failure runs - this is what turns "the trigger
        // failed" into "the rest of the workflow is SKIPPED" on the canvas.
        verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(triggerNode), eq(0), eq(4), eq(TRIGGER_ID),
                eq(false), eq(V2SkipPropagationService.SOURCE_SYNC));
    }

    @Test
    @DisplayName("Out of credits: the node still emits start then complete, so the canvas shows it turn red")
    void outOfCreditsStillEmitsTheNodeLifecycle() {
        when(nodeCreditGate.denyOrNull(TRIGGER_ID, TENANT))
                .thenReturn(NodeCreditGate.exhaustedResult(TRIGGER_ID));

        engine.executeSingleNode(TRIGGER_ID, tree, context, execution, eventService, null);

        verify(eventService).emitNodeStart(eq(execution), eq(triggerNode), any(), eq(0), eq(4));
        verify(eventService).emitNodeComplete(
                eq(execution), eq(triggerNode), any(NodeExecutionResult.class), any(), eq(0), any());
    }

    @Test
    @DisplayName("Credits available: the node executes normally through the split-aware executor")
    void creditsAvailableExecutesTheNode() {
        when(nodeCreditGate.denyOrNull(TRIGGER_ID, TENANT)).thenReturn(null);
        when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(NodeExecutionResult.success(TRIGGER_ID, Map.of("ok", true)));

        StepByStepExecutionResult result = engine.executeSingleNode(
                TRIGGER_ID, tree, context, execution, eventService, null);

        assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.COMPLETED);
        verify(splitAwareExecutor).execute(
                eq((ExecutionNode) triggerNode), any(), eq(RUN_ID), any(), eq(execution), any(), eq(0), eq(null));
        verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
    }
}
