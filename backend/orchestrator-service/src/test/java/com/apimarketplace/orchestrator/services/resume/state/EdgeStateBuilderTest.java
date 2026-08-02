package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.execution.StatusCounts;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EdgeStateBuilder")
class EdgeStateBuilderTest {

    @Mock
    private StateReconstructorHelper helper;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private WorkflowPlan plan;

    private EdgeStateBuilder builder;

    private static final String RUN_ID = "run-abc-123";

    @BeforeEach
    void setUp() {
        builder = new EdgeStateBuilder(helper, stateSnapshotService);
    }

    @Nested
    @DisplayName("buildEdgeStates() - without snapshot")
    class BuildEdgeStatesWithoutSnapshot {

        @Test
        @DisplayName("Builds edge states from plan edges with zero counts when no snapshot data")
        void buildsEdgeStatesFromPlanEdges() {
            Edge edge = new Edge("trigger:start", "mcp:step1");
            when(plan.getEdges()).thenReturn(List.of(edge));
            when(helper.determineEdgeStatus(any(), any(), any(), any(), any()))
                .thenReturn(RunStatus.COMPLETED);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                plan, Set.of("mcp:step1"), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(1, result.size());
            WorkflowRunState.EdgeState es = result.get(0);
            assertEquals(RunStatus.COMPLETED, es.status());
            assertEquals(0, es.completedCount(), "no snapshot data → zero counts");
            assertEquals(0, es.skippedCount());
            assertEquals(0, es.totalCount());
        }

        @Test
        @DisplayName("Skips edges with null from or to")
        void skipsEdgesWithNullFromOrTo() {
            Edge edge1 = new Edge(null, "mcp:step1");
            Edge edge2 = new Edge("trigger:start", null);
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
            );

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Preserves port-qualified edges as distinct entries")
        void preservesPortQualifiedEdges() {
            Edge edge1 = new Edge("core:check:if", "mcp:step1");
            Edge edge2 = new Edge("core:check:else", "mcp:step1");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));
            when(helper.determineEdgeStatus(any(), any(), any(), any(), any()))
                .thenReturn(RunStatus.PENDING);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(2, result.size());
            assertEquals("core:check:if", result.get(0).from());
            assertEquals("core:check:else", result.get(1).from());
        }

        @Test
        @DisplayName("Deduplicates truly duplicate edges without ports")
        void deduplicatesDuplicateEdges() {
            Edge edge1 = new Edge("trigger:start", "mcp:step1");
            Edge edge2 = new Edge("trigger:start", "mcp:step1");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));
            when(helper.determineEdgeStatus(any(), any(), any(), any(), any()))
                .thenReturn(RunStatus.PENDING);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("buildEdgeStates() - multi-trigger fan-in")
    class MultiTriggerFanIn {

        @Test
        @DisplayName("Unfired trigger's edge stays at zero when sibling trigger fires through shared merge")
        void unfiredTriggerEdgeStaysAtZero() {
            // Plan: two triggers fan into a shared merge.
            //   trigger:manuala  ─┐
            //                     ├──► core:sharedmerge
            //   trigger:scheduler ┘
            Edge manualEdge = new Edge("trigger:manuala", "core:sharedmerge");
            Edge schedEdge = new Edge("trigger:scheduler", "core:sharedmerge");
            when(plan.getEdges()).thenReturn(List.of(manualEdge, schedEdge));

            // Snapshot has only the scheduler→merge edge recorded (5 fires).
            // The manual→merge edge is absent because manuala never fired.
            StateSnapshot snapshot = mock(StateSnapshot.class);
            StateSnapshot.EdgeCounts schedCounts = new StateSnapshot.EdgeCounts(0, 5, 0);
            when(snapshot.getEdges()).thenReturn(Map.of("trigger:scheduler->core:sharedmerge", schedCounts));
            when(snapshot.getEdgeCounts("trigger:scheduler", "core:sharedmerge")).thenReturn(schedCounts);
            when(snapshot.getEdgeCounts("trigger:manuala", "core:sharedmerge")).thenReturn(null);

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            // Helper returns PENDING for the unfired trigger's edge.
            when(helper.determineEdgeStatus(eq("trigger:manuala"), eq("core:sharedmerge"), any(), any(), any()))
                .thenReturn(RunStatus.PENDING);

            // Target step has accumulated counts from the 5 scheduler fires.
            // CRITICAL: these MUST NOT be attributed to the manuala edge.
            StatusCounts mergeCounts = new StatusCounts();
            for (int i = 0; i < 5; i++) {
                mergeCounts.incrementTotal();
                mergeCounts.incrementCompleted();
            }
            Map<String, StatusCounts> stepStatusCounts = Map.of("core:sharedmerge", mergeCounts);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of("core:sharedmerge"), Set.of(), Set.of(), stepStatusCounts
            );

            WorkflowRunState.EdgeState manualEs = result.stream()
                .filter(e -> "trigger:manuala".equals(e.from()))
                .findFirst().orElseThrow();
            assertEquals(0, manualEs.completedCount(),
                "Unfired trigger edge must NOT inherit target merge node's completed count");
            assertEquals(0, manualEs.totalCount());
            assertEquals(RunStatus.PENDING, manualEs.status());

            WorkflowRunState.EdgeState schedEs = result.stream()
                .filter(e -> "trigger:scheduler".equals(e.from()))
                .findFirst().orElseThrow();
            assertEquals(5, schedEs.completedCount());
            assertEquals(5, schedEs.totalCount());
            assertEquals(RunStatus.COMPLETED, schedEs.status());
        }
    }

    @Nested
    @DisplayName("buildEdgeStates() - with snapshot data")
    class BuildEdgeStatesWithSnapshot {

        @Test
        @DisplayName("Uses StateSnapshot edge counts when present")
        void usesSnapshotCountsWhenPresent() {
            Edge edge = new Edge("trigger:start", "mcp:step1");
            when(plan.getEdges()).thenReturn(List.of(edge));

            StateSnapshot snapshot = mock(StateSnapshot.class);
            StateSnapshot.EdgeCounts counts = new StateSnapshot.EdgeCounts(0, 3, 0);
            when(snapshot.getEdges()).thenReturn(Map.of("trigger:start->mcp:step1", counts));
            when(snapshot.getEdgeCounts("trigger:start", "mcp:step1")).thenReturn(counts);
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(1, result.size());
            WorkflowRunState.EdgeState es = result.get(0);
            assertEquals(3, es.completedCount());
            assertEquals(3, es.totalCount());
            assertEquals(RunStatus.COMPLETED, es.status());
        }

        @Test
        @DisplayName("createEdgeStateFromSnapshot logs at DEBUG, not INFO, on the hot path")
        void createEdgeStateLogsAtDebugNotInfo() {
            // Regression guard for OOM diagnosis 2026-05-06: createEdgeStateFromSnapshot used
            // to log "[createEdgeStateFromSnapshot] Edge ... snapshotEdges=..." at INFO on
            // every call (1292×/20min observed in prod), each materialising
            // snapshot.getEdges().keySet() - one of the top Jackson allocators in the heap
            // histogram. Demoting to DEBUG (with isDebugEnabled() guard around the
            // keySet() call) cuts both the log volume and the eager allocation.
            Logger edgeLogger = (Logger) LoggerFactory.getLogger(EdgeStateBuilder.class);
            Level previous = edgeLogger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            edgeLogger.addAppender(appender);
            edgeLogger.setLevel(Level.INFO); // INFO threshold: DEBUG must NOT pass through
            try {
                Edge edge = new Edge("trigger:start", "mcp:step1");
                when(plan.getEdges()).thenReturn(List.of(edge));

                StateSnapshot snapshot = mock(StateSnapshot.class);
                StateSnapshot.EdgeCounts counts = new StateSnapshot.EdgeCounts(0, 3, 0);
                when(snapshot.getEdges()).thenReturn(Map.of("trigger:start->mcp:step1", counts));
                when(snapshot.getEdgeCounts("trigger:start", "mcp:step1")).thenReturn(counts);
                when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

                builder.buildEdgeStates(
                    RUN_ID, plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
                );

                boolean spam = appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains("[createEdgeStateFromSnapshot]"));
                assertFalse(spam,
                    "createEdgeStateFromSnapshot must not log at INFO (hot path, OOM 2026-05-06)");
            } finally {
                edgeLogger.detachAppender(appender);
                edgeLogger.setLevel(previous);
            }
        }
    }

    @Nested
    @DisplayName("buildEdgeStates() - untraversed port-qualified branches")
    class UntraversedPortedBranches {

        /**
         * Prod bug 2026-07-30: a while loop's `core:loop:exit -> next` edge rendered BLUE
         * (RUNNING) in the builder for the whole run while the loop was still cycling in its
         * body. The loop CONTROLLER node completes once on entry, and
         * {@code determineEdgeStatus} answers RUNNING for "source completed, target not yet" -
         * a heuristic that is only sound for an unconditional edge. The exit was never taken,
         * so it must read PENDING.
         */
        @Test
        @DisplayName("regression: untraversed loop exit edge is PENDING, not RUNNING, while the body iterates")
        void untraversedLoopExitEdgeStaysPending() {
            Edge bodyEdge = new Edge("core:wait_render:body", "core:pause");
            Edge exitEdge = new Edge("core:wait_render:exit", "core:step_result");
            when(plan.getEdges()).thenReturn(List.of(bodyEdge, exitEdge));

            // Only the body edge was ever traversed (74 body iterations recorded un-ported).
            StateSnapshot snapshot = mock(StateSnapshot.class);
            StateSnapshot.EdgeCounts bodyCounts = new StateSnapshot.EdgeCounts(0, 74, 0);
            when(snapshot.getEdges()).thenReturn(Map.of("core:wait_render->core:pause", bodyCounts));
            // The builder probes the port-qualified key first, then falls back to the un-ported
            // one that BackEdgeHandler actually records under.
            lenient().when(snapshot.getEdgeCounts(anyString(), anyString())).thenReturn(null);
            lenient().when(snapshot.getEdgeCounts("core:wait_render", "core:pause")).thenReturn(bodyCounts);
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            // The loop controller node IS completed -> the old heuristic returned RUNNING.
            when(helper.determineEdgeStatus(eq("core:wait_render"), eq("core:step_result"), any(), any(), any()))
                .thenReturn(RunStatus.RUNNING);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of("core:wait_render"), Set.of(), Set.of(), new HashMap<>()
            );

            WorkflowRunState.EdgeState exitState = result.stream()
                .filter(es -> "core:wait_render:exit".equals(es.from()))
                .findFirst()
                .orElseThrow();
            assertEquals(RunStatus.PENDING, exitState.status(),
                "the loop exit branch was never taken - reporting RUNNING paints the edge blue "
                + "in the builder for the whole run");
            assertEquals(0, exitState.totalCount());
        }

        @Test
        @DisplayName("regression: an untaken decision branch is PENDING, not RUNNING")
        void untraversedDecisionBranchStaysPending() {
            Edge elseEdge = new Edge("core:check:else", "mcp:fallback");
            when(plan.getEdges()).thenReturn(List.of(elseEdge));
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(null);
            when(helper.determineEdgeStatus(eq("core:check"), eq("mcp:fallback"), any(), any(), any()))
                .thenReturn(RunStatus.RUNNING);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of("core:check"), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(RunStatus.PENDING, result.get(0).status(),
                "a port IS the branch condition: zero counts means the branch was not taken");
        }

        @Test
        @DisplayName("An unconditional edge keeps the RUNNING hand-off status")
        void unportedEdgeKeepsRunningHandoff() {
            Edge plainEdge = new Edge("mcp:fetch", "mcp:transform");
            when(plan.getEdges()).thenReturn(List.of(plainEdge));
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(null);
            when(helper.determineEdgeStatus(eq("mcp:fetch"), eq("mcp:transform"), any(), any(), any()))
                .thenReturn(RunStatus.RUNNING);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of("mcp:fetch"), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(RunStatus.RUNNING, result.get(0).status(),
                "the source-completed hand-off is still meaningful on an edge with no branch port");
        }

        @Test
        @DisplayName("A ported branch that WAS traversed still reports its recorded status")
        void traversedPortedBranchKeepsSnapshotStatus() {
            Edge ifEdge = new Edge("core:check:if", "mcp:happy");
            when(plan.getEdges()).thenReturn(List.of(ifEdge));

            StateSnapshot snapshot = mock(StateSnapshot.class);
            StateSnapshot.EdgeCounts counts = new StateSnapshot.EdgeCounts(0, 1, 0);
            when(snapshot.getEdges()).thenReturn(Map.of("core:check:if->mcp:happy", counts));
            when(snapshot.getEdgeCounts("core:check:if", "mcp:happy")).thenReturn(counts);
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of("core:check"), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(RunStatus.COMPLETED, result.get(0).status(),
                "the PENDING downgrade must only apply to branches with NO recorded counts");
            assertEquals(1, result.get(0).completedCount());
        }

        @Test
        @DisplayName("A fork branch recorded UN-ported still resolves through the existing fallback")
        void forkBranchResolvesThroughUnportedFallback() {
            // Fork takes ALL branches, and EdgeStatusEmitter records them under the un-ported
            // source key - the same shape as the loop body edge. This is the family most at risk
            // of being wrongly downgraded to PENDING, so pin it.
            Edge branchEdge = new Edge("core:fanout:branch_0", "mcp:worker");
            when(plan.getEdges()).thenReturn(List.of(branchEdge));

            StateSnapshot snapshot = mock(StateSnapshot.class);
            StateSnapshot.EdgeCounts counts = new StateSnapshot.EdgeCounts(0, 5, 0);
            when(snapshot.getEdges()).thenReturn(Map.of("core:fanout->mcp:worker", counts));
            lenient().when(snapshot.getEdgeCounts(anyString(), anyString())).thenReturn(null);
            lenient().when(snapshot.getEdgeCounts("core:fanout", "mcp:worker")).thenReturn(counts);
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of("core:fanout"), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(RunStatus.COMPLETED, result.get(0).status(),
                "a traversed fork branch must keep its recorded status - the downgrade only "
                + "applies when NO counts exist under any key variant");
            assertEquals(5, result.get(0).completedCount());
        }

        @Test
        @DisplayName("Only RUNNING is downgraded: SKIPPED/FAILED/COMPLETED pass through untouched")
        void onlyRunningIsDowngraded() {
            for (RunStatus fallback : List.of(RunStatus.SKIPPED, RunStatus.FAILED, RunStatus.COMPLETED)) {
                reset(plan, helper, stateSnapshotService);
                Edge elseEdge = new Edge("core:check:else", "mcp:fallback");
                when(plan.getEdges()).thenReturn(List.of(elseEdge));
                when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(null);
                when(helper.determineEdgeStatus(eq("core:check"), eq("mcp:fallback"), any(), any(), any()))
                    .thenReturn(fallback);

                List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                    RUN_ID, plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
                );

                assertEquals(fallback, result.get(0).status(),
                    fallback + " carries real information (skip cascade, failure) and must not be "
                    + "rewritten by the branch-not-taken downgrade");
            }
        }
    }
}
