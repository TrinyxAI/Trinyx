package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A loop declared WITHOUT a loop node: an edge that points back at a node which already ran.
 *
 * <p>Two behaviours have no equivalent in the loop-node shape and are covered here:
 * <ul>
 *   <li><b>Port awareness</b> - a loop-back hanging off a Decision's {@code else} must fire only
 *       when {@code else} was actually selected. The dispatch happens whenever the source
 *       produced no wired successors, which is also true when a DIFFERENT port was chosen, so
 *       without this check the workflow silently iterates on a branch its condition rejected.</li>
 *   <li><b>Termination</b> - there is no exit port to fall through to, so reaching the iteration
 *       cap while the loop still wants to run must FAIL the run rather than stop mid-graph with
 *       a green result.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackEdgeHandler - loop declared without a loop node")
class BackEdgeHandlerHublessTest {

    @Mock private TemplateEngine templateEngine;
    @Mock private EdgeStatusService edgeStatusService;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private V2ExecutionEventService eventService;
    @Mock private WorkflowExecution execution;

    private BackEdgeHandler handler;

    /** from Decision's else back to an earlier step, capped at 3. */
    private static final Edge LOOP_BACK = new Edge(
        "core:check:else", "mcp:fetch", null, new Edge.BackEdge(null, 3));

    @BeforeEach
    void setUp() {
        handler = new BackEdgeHandler(templateEngine, edgeStatusService, eventPublisher, workflowEpochService);
        when(execution.getRunId()).thenReturn("run-" + System.identityHashCode(this));
        when(edgeStatusService.flushEdgeBatch(anyString())).thenReturn(Map.of());
    }

    private WorkflowPlan planWithLoopBack() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getIterateEdgesForSource(anyString())).thenReturn(List.of());
        when(plan.getIterateEdges()).thenReturn(List.of());
        when(plan.getEdges()).thenReturn(List.of(
            new Edge("mcp:fetch", "core:check"),
            LOOP_BACK
        ));
        when(plan.getCores()).thenReturn(List.of());
        return plan;
    }

    private ExecutionContext contextFor(WorkflowPlan plan, BackEdgeState seeded) {
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.plan()).thenReturn(plan);
        when(ctx.itemIndex()).thenReturn(0);
        when(ctx.runId()).thenReturn("run-x");
        when(ctx.triggerId()).thenReturn("trigger:start");
        when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());
        when(ctx.getGlobalData("back_edge_state:" + LOOP_BACK.getEdgeId()))
            .thenReturn(Optional.ofNullable(seeded));
        when(ctx.getGlobalDataKeys()).thenReturn(java.util.Set.of());
        when(ctx.getAllStepOutputs()).thenReturn(Map.of());
        when(ctx.triggerData()).thenReturn(null);
        when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
        when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);
        when(ctx.withoutNodes(anySet())).thenReturn(ctx);
        return ctx;
    }

    /** A Decision that reports which port it selected, the way the real node does. */
    private ExecutionNode decisionSelecting(String selectedPort) {
        ExecutionNode node = mock(ExecutionNode.class);
        when(node.getNodeId()).thenReturn("core:check");
        when(node.isBranchingNode()).thenReturn(true);
        when(node.getSelectedPort(any())).thenReturn(selectedPort);
        return node;
    }

    @Nested
    @DisplayName("port awareness")
    class PortAwarenessTests {

        @Test
        @DisplayName("iterates when the branch carrying the loop-back was selected")
        void iteratesWhenItsOwnBranchWasSelected() {
            WorkflowPlan plan = planWithLoopBack();
            ExecutionContext ctx = contextFor(plan, null);
            ExecutionNode source = decisionSelecting("else");
            ExecutionNode reEntry = mock(ExecutionNode.class);

            boolean[] traversed = { false };
            handler.handleBackEdge(source, ctx, execution, eventService, null,
                id -> "mcp:fetch".equals(id) ? reEntry : null,
                (n, c, e, es, i) -> { traversed[0] = true; return c; });

            assertTrue(traversed[0], "The loop must re-enter its target when its branch was taken");
        }

        @Test
        @DisplayName("does NOT iterate when a different branch was selected")
        void doesNotIterateWhenAnotherBranchWasSelected() {
            WorkflowPlan plan = planWithLoopBack();
            ExecutionContext ctx = contextFor(plan, null);
            ExecutionNode source = decisionSelecting("if");
            ExecutionNode reEntry = mock(ExecutionNode.class);

            boolean[] traversed = { false };
            handler.handleBackEdge(source, ctx, execution, eventService, null,
                id -> "mcp:fetch".equals(id) ? reEntry : null,
                (n, c, e, es, i) -> { traversed[0] = true; return c; });

            assertFalse(traversed[0],
                "Looping here would run a branch the decision rejected, on the wrong data");
        }

        @Test
        @DisplayName("does NOT iterate when no branch matched at all")
        void doesNotIterateWhenNoBranchMatched() {
            // getNextNodes is empty here too, which is exactly the state that reaches this
            // handler - the selected port being null is what tells the two cases apart.
            WorkflowPlan plan = planWithLoopBack();
            ExecutionContext ctx = contextFor(plan, null);
            ExecutionNode source = decisionSelecting(null);
            ExecutionNode reEntry = mock(ExecutionNode.class);

            boolean[] traversed = { false };
            handler.handleBackEdge(source, ctx, execution, eventService, null,
                id -> "mcp:fetch".equals(id) ? reEntry : null,
                (n, c, e, es, i) -> { traversed[0] = true; return c; });

            assertFalse(traversed[0]);
        }
    }

    @Nested
    @DisplayName("termination")
    class TerminationTests {

        @Test
        @DisplayName("reaching the cap while the loop still wants to run FAILS the run")
        void capReachedWithLoopStillWantingToRunFails() {
            WorkflowPlan plan = planWithLoopBack();
            // iteration=2 with max=3: no room for another pass.
            BackEdgeState atCap = BackEdgeState.create(LOOP_BACK.getEdgeId(), 3, null)
                .incrementIteration()
                .incrementIteration();
            ExecutionContext ctx = contextFor(plan, atCap);
            ExecutionNode source = decisionSelecting("else");

            handler.handleBackEdge(source, ctx, execution, eventService, null,
                id -> null, (n, c, e, es, i) -> c);

            ArgumentCaptor<NodeExecutionResult> published = ArgumentCaptor.forClass(NodeExecutionResult.class);
            verify(eventService).rePublishNodeOutput(eq(execution), same(source), published.capture(),
                any(), anyInt(), any(), anyInt());

            NodeExecutionResult result = published.getValue();
            assertTrue(result.isFailure(),
                "A budget overflow must not finish green in the middle of the graph");
            assertEquals("max_iterations_reached", result.output().get("reason"));
            assertEquals(3, result.output().get("maxIterations"));
        }

        @Test
        @DisplayName("the failure is recorded one iteration past the last body run")
        void failureIsRecordedAtADistinctIteration() {
            // The source already owns a row at the last body iteration; a colliding row would be
            // dropped by the storage unique key and the failure would vanish.
            WorkflowPlan plan = planWithLoopBack();
            BackEdgeState atCap = BackEdgeState.create(LOOP_BACK.getEdgeId(), 3, null)
                .incrementIteration()
                .incrementIteration();
            ExecutionContext ctx = contextFor(plan, atCap);
            ExecutionNode source = decisionSelecting("else");

            handler.handleBackEdge(source, ctx, execution, eventService, null,
                id -> null, (n, c, e, es, i) -> c);

            verify(eventService).rePublishNodeOutput(eq(execution), same(source), any(),
                any(), anyInt(), any(), eq(3));
        }

        @Test
        @DisplayName("a loop that stops on its own condition does NOT fail the run")
        void conditionFalseDoesNotFail() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource(anyString())).thenReturn(List.of());
            when(plan.getIterateEdges()).thenReturn(List.of());
            Edge conditional = new Edge("core:check:else", "mcp:fetch", null,
                new Edge.BackEdge("{{keep_going}}", 5));
            when(plan.getEdges()).thenReturn(List.of(conditional));
            when(plan.getCores()).thenReturn(List.of());

            when(templateEngine.evaluateConditionWithDetailsWithMap(eq("{{keep_going}}"), anyMap()))
                .thenReturn(new TemplateEngine.ConditionEvaluationResult("false", "false", false, null));

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.itemIndex()).thenReturn(0);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());
            when(ctx.getGlobalDataKeys()).thenReturn(java.util.Set.of());
            when(ctx.getAllStepOutputs()).thenReturn(Map.of());
            when(ctx.triggerData()).thenReturn(null);
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);

            ExecutionNode source = decisionSelecting("else");

            handler.handleBackEdge(source, ctx, execution, eventService, null,
                id -> null, (n, c, e, es, i) -> c);

            verify(eventService, never()).rePublishNodeOutput(any(), any(), argThat(
                r -> r != null && r.isFailure()), any(), anyInt(), any(), anyInt());
        }
    }

    /**
     * The streaming events a hub-less back-edge emits must satisfy the REAL publisher's
     * contract. {@code LoopEvent} rejects a null {@code loopId}, and a hub-less spec has no
     * loop Core to name - so passing {@code spec.hubKey()} straight through threw
     * IllegalArgumentException out of the middle of the iteration, aborting the span reset
     * and making the loop run exactly once with no error surfaced anywhere.
     *
     * <p>The mock publisher cannot enforce that invariant, so these tests capture the
     * argument and assert it directly.
     */
    @Nested
    @DisplayName("streaming events")
    class StreamingEventTests {

        @Test
        @DisplayName("iteration event carries a non-null loopId even without a loop node")
        void iterationEventHasNonNullLoopId() {
            WorkflowPlan plan = planWithLoopBack();
            ExecutionContext ctx = contextFor(plan, null);
            ExecutionNode source = decisionSelecting("else");

            handler.executeBackEdgeIteration(source, "core:check",
                NodeExecutionResult.success("core:check", Map.of()),
                ctx, execution, eventService, null, 0, Map.of());

            ArgumentCaptor<String> loopId = ArgumentCaptor.forClass(String.class);
            verify(eventPublisher, atLeastOnce())
                .emitLoopEvent(anyString(), loopId.capture(), any(), anyMap());
            assertTrue(loopId.getAllValues().stream().allMatch(java.util.Objects::nonNull),
                "LoopEvent requires a non-null loopId - a hub-less back-edge must fall back "
                    + "to its edge id, not pass the null hubKey through");
            assertEquals(LOOP_BACK.getEdgeId(), loopId.getValue(),
                "a hub-less back-edge identifies itself by its edge id");
        }

        @Test
        @DisplayName("termination event carries a non-null loopId even without a loop node")
        void terminationEventHasNonNullLoopId() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource(anyString())).thenReturn(List.of());
            when(plan.getIterateEdges()).thenReturn(List.of());
            Edge conditional = new Edge("core:check:else", "mcp:fetch", null,
                new Edge.BackEdge("{{keep_going}}", 5));
            when(plan.getEdges()).thenReturn(List.of(conditional));
            when(plan.getCores()).thenReturn(List.of());
            when(templateEngine.evaluateConditionWithDetailsWithMap(eq("{{keep_going}}"), anyMap()))
                .thenReturn(new TemplateEngine.ConditionEvaluationResult("false", "false", false, null));

            ExecutionContext ctx = contextFor(plan, null);
            ExecutionNode source = decisionSelecting("else");

            handler.executeBackEdgeIteration(source, "core:check",
                NodeExecutionResult.success("core:check", Map.of()),
                ctx, execution, eventService, null, 0, Map.of());

            ArgumentCaptor<String> loopId = ArgumentCaptor.forClass(String.class);
            verify(eventPublisher, atLeastOnce())
                .emitLoopEvent(anyString(), loopId.capture(), any(), anyMap());
            assertTrue(loopId.getAllValues().stream().allMatch(java.util.Objects::nonNull),
                "termination must not emit a null loopId either");
        }
    }
}
