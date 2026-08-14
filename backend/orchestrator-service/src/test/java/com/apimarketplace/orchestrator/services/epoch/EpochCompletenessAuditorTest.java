package com.apimarketplace.orchestrator.services.epoch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for the epoch-close completeness audit.
 *
 * <p>The audit exists because a truncated epoch is silent: the run status comes from
 * {@code failedNodeIds}, so an epoch that simply stops finding work closes as COMPLETED.
 * These tests pin the two things that make the WARN worth having - it names the nodes that
 * never ran, and it stays quiet on every shape of healthy epoch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EpochCompletenessAuditor")
class EpochCompletenessAuditorTest {

    @Mock
    private StateSnapshotService stateSnapshotService;

    private EpochCompletenessAuditor auditor;
    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger auditLogger;

    @BeforeEach
    void setUp() {
        auditor = new EpochCompletenessAuditor(stateSnapshotService);
        auditLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(EpochCompletenessAuditor.class);
        logs = new ListAppender<>();
        logs.start();
        auditLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(logs);
    }

    /** trigger:start -> core:poll -> {body: core:wait -> mcp:get, exit: mcp:download} */
    private WorkflowPlan loopPlan() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        lenient().when(plan.getEdges()).thenReturn(List.of(
            edge("trigger:start", "core:poll"),
            edge("core:poll:body", "core:wait"),
            edge("core:wait", "mcp:get"),
            edge("mcp:get", "core:poll:iterate"),
            edge("core:poll:exit", "mcp:download")
        ));
        return plan;
    }

    private Edge edge(String from, String to) {
        return new Edge(from, to, null, null);
    }

    private void givenEpochState(String runId, String triggerId, int epoch, EpochState state) {
        StateSnapshot snapshot = mock(StateSnapshot.class);
        when(snapshot.getEpochState(triggerId, epoch)).thenReturn(state);
        when(stateSnapshotService.getSnapshot(runId)).thenReturn(snapshot);
    }

    private EpochState epochWith(Set<String> completed, Set<String> failed, Set<String> skipped) {
        EpochState state = mock(EpochState.class);
        lenient().when(state.getCompletedNodeIds()).thenReturn(completed);
        lenient().when(state.getFailedNodeIds()).thenReturn(failed);
        lenient().when(state.getSkippedNodeIds()).thenReturn(skipped);
        return state;
    }

    private List<String> warnings() {
        return logs.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    @Test
    @DisplayName("Names the nodes an epoch never reached when it closes without failures")
    void warnsAndNamesUnreachedNodesOnTruncatedEpoch() {
        // The production shape: the loop ran its body but the back-edge was dropped, so the
        // loop's exit target never executed. No node failed, so the epoch closes COMPLETED.
        givenEpochState("run-1", "trigger:start", 2,
            epochWith(Set.of("trigger:start", "core:poll", "core:wait", "mcp:get"), Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 2, loopPlan(), false);

        List<String> warns = warnings();
        assertEquals(1, warns.size(), "A truncated epoch must produce exactly one WARN");
        assertTrue(warns.get(0).contains("mcp:download"),
            "The WARN must NAME the unreached node - that is the whole point, an investigation "
          + "otherwise has to compare node counts between epochs. Got: " + warns.get(0));
        assertTrue(warns.get(0).contains("epoch=2"), "The WARN must carry the epoch: " + warns.get(0));
    }

    @Test
    @DisplayName("Stays quiet when every node of the DAG reached a terminal state")
    void silentWhenEpochIsComplete() {
        givenEpochState("run-1", "trigger:start", 1, epochWith(
            Set.of("trigger:start", "core:poll", "core:wait", "mcp:get", "mcp:download"),
            Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 1, loopPlan(), false);

        assertTrue(warnings().isEmpty(), "A complete epoch must not warn: " + warnings());
    }

    @Test
    @DisplayName("Counts a SKIPPED branch as reached, so an untaken branch never warns")
    void silentWhenBranchWasSkipped() {
        // core:poll's body was skipped (loop exited on entry) - legitimate, not a truncation.
        givenEpochState("run-1", "trigger:start", 3, epochWith(
            Set.of("trigger:start", "core:poll", "mcp:download"),
            Set.of(),
            Set.of("core:wait", "mcp:get")));

        auditor.auditEpochClose("run-1", "trigger:start", 3, loopPlan(), false);

        assertTrue(warnings().isEmpty(),
            "A skipped branch is a reached branch - warning here would redden healthy runs: " + warnings());
    }

    @Test
    @DisplayName("Stays quiet when the epoch already has failures")
    void silentWhenEpochHasFailures() {
        // Nodes downstream of a failure are unreached by design, and the run is already
        // reported FAILED - the audit only exists for the epoch that LOOKS clean.
        auditor.auditEpochClose("run-1", "trigger:start", 2, loopPlan(), true);

        assertTrue(warnings().isEmpty(), "No WARN when the epoch is already failing: " + warnings());
        verify(stateSnapshotService, never()).getSnapshot(anyString());
    }

    @Test
    @DisplayName("Ignores nodes of OTHER DAGs - only what this trigger can reach counts")
    void ignoresNodesOfOtherDags() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getEdges()).thenReturn(List.of(
            edge("trigger:start", "mcp:a"),
            edge("trigger:other", "mcp:b")   // a second, independent DAG
        ));
        givenEpochState("run-1", "trigger:start", 1,
            epochWith(Set.of("trigger:start", "mcp:a"), Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 1, plan, false);

        assertTrue(warnings().isEmpty(),
            "mcp:b belongs to trigger:other and is not this DAG's business: " + warnings());
    }

    @Test
    @DisplayName("Does not count triggers or notes as unreached")
    void ignoresNonExecutingNodes() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getEdges()).thenReturn(List.of(
            edge("trigger:start", "mcp:a"),
            edge("mcp:a", "note:reminder"),
            edge("mcp:a", "trigger:downstream_never_fires")
        ));
        givenEpochState("run-1", "trigger:start", 1,
            epochWith(Set.of("trigger:start", "mcp:a"), Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 1, plan, false);

        assertTrue(warnings().isEmpty(),
            "Notes and triggers leave no execution record and must not be reported: " + warnings());
    }

    @Test
    @DisplayName("A node the flow went AROUND is not a truncation")
    void silentWhenTheFlowContinuedPastTheUnreachedNode() {
        // Real shape from SplitLoopEdgeCasesE2ETest: a loop with maxIterations=0 never runs its
        // body, yet the exit target downstream does run. mcp:get sits below the unreached body
        // and mcp:download below the loop - the flow reached the far side, so nothing stopped.
        givenEpochState("run-1", "trigger:start", 1, epochWith(
            Set.of("trigger:start", "core:poll", "mcp:download"),
            Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 1, loopPlan(), false);

        assertTrue(warnings().isEmpty(),
            "core:wait/mcp:get were bypassed, not stranded: mcp:download below them was reached. "
          + "Warning here is noise on a legitimate workflow: " + warnings());
    }

    @Test
    @DisplayName("A fork branch is masked by the merge below it - documented limitation")
    void forkBranchIsMaskedByTheMergeBelowIt() {
        // Second blind spot of the frontier rule, pinned for the same reason as the loop body:
        // branch_a never ran and was never SKIPPED, yet the merge one hop below it did. The
        // branch therefore "has a reached descendant" and is not reported. This shape only
        // arises when merge readiness is ITSELF wrong (a correct merge waits for every
        // predecessor to be COMPLETED or SKIPPED), so the audit stays silent on a class of
        // silent-wrong-result. Recorded as a decision, not left to be rediscovered.
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getEdges()).thenReturn(List.of(
            edge("trigger:start", "core:fork"),
            edge("core:fork:branch_0", "mcp:branch_a"),
            edge("core:fork:branch_1", "mcp:branch_b"),
            edge("mcp:branch_a", "mcp:merge"),
            edge("mcp:branch_b", "mcp:merge"),
            edge("mcp:merge", "mcp:tail")
        ));
        givenEpochState("run-1", "trigger:start", 1, epochWith(
            Set.of("trigger:start", "core:fork", "mcp:branch_b", "mcp:merge", "mcp:tail"),
            Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 1, plan, false);

        assertTrue(warnings().isEmpty(),
            "mcp:branch_a never ran and was never skipped, but the merge below it did - the "
          + "frontier rule masks it. If this ever starts warning, the rule changed: " + warnings());
    }

    @Test
    @DisplayName("A coordinate the snapshot does not know is not reported as a truncation")
    void unknownEpochCoordinateDoesNotWarn() {
        // StateSnapshot.getEpochState returns EpochState.fresh() - never null - for an epoch it
        // does not know. Without a guard, an empty terminal set reads as "the whole DAG was
        // skipped" and the audit invents the incident it is meant to detect.
        givenEpochState("run-1", "trigger:start", 9, epochWith(Set.of(), Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 9, loopPlan(), false);

        assertTrue(warnings().isEmpty(),
            "An unknown (trigger, epoch) must be silent, not report every node: " + warnings());
    }

    @Test
    @DisplayName("Caps the printed node ids while keeping the count exact")
    void capsTheLoggedListButNotTheCount() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        List<Edge> edges = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            edges.add(edge("trigger:start", "mcp:n" + i));
        }
        when(plan.getEdges()).thenReturn(edges);
        givenEpochState("run-1", "trigger:start", 1,
            epochWith(Set.of("trigger:start", "mcp:n0"), Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 1, plan, false);

        String warn = warnings().get(0);
        assertTrue(warn.contains("39 node(s)"), "The count must stay exact: " + warn);
        assertTrue(warn.contains("and 14 more"),
            "The list must be capped so one truncated epoch cannot flood a log line: " + warn);
    }

    @Test
    @DisplayName("Never throws, and never warns, when the snapshot cannot be read")
    void survivesSnapshotFailure() {
        when(stateSnapshotService.getSnapshot(anyString())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() ->
            auditor.auditEpochClose("run-1", "trigger:start", 1, loopPlan(), false));
        assertTrue(warnings().isEmpty(), "A failed audit must stay silent, not cry wolf: " + warnings());
    }

    @Test
    @DisplayName("Skips the audit without DAG coordinates or a plan")
    void skipsWithoutCoordinates() {
        auditor.auditEpochClose("run-1", null, 1, loopPlan(), false);
        auditor.auditEpochClose("run-1", "trigger:start", 1, null, false);
        auditor.auditEpochClose(null, "trigger:start", 1, loopPlan(), false);

        assertTrue(warnings().isEmpty(), "No coordinates, no verdict: " + warnings());
        verify(stateSnapshotService, never()).getSnapshot(anyString());
    }

    @Test
    @DisplayName("Reports every stranded node, not just the first")
    void reportsAllStrandedNodes() {
        // Two independent tails below a completed step: both stopped, both reported.
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getEdges()).thenReturn(List.of(
            edge("trigger:start", "mcp:head"),
            edge("mcp:head", "mcp:tail_a"),
            edge("mcp:head", "mcp:tail_b")
        ));
        givenEpochState("run-1", "trigger:start", 2,
            epochWith(Set.of("trigger:start", "mcp:head"), Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 2, plan, false);

        String warn = warnings().get(0);
        assertTrue(warn.contains("mcp:tail_a") && warn.contains("mcp:tail_b"),
            "Both stranded tails must be listed: " + warn);
        assertTrue(warn.contains("2 node(s)"), "The count must match the list: " + warn);
    }

    @Test
    @DisplayName("A loop body is masked by its own back-edge - documented limitation")
    void loopBodyIsMaskedByTheBackEdge() {
        // core:wait -> mcp:get -> core:poll (iterate). The loop head always runs on entry, so it
        // is terminal, so every body node has a reached descendant. A node stranded INSIDE a body
        // is therefore not reported - but the loop's EXIT target still is, which is where the
        // production truncation surfaced. Pinned so the trade-off is a decision, not a surprise.
        givenEpochState("run-1", "trigger:start", 2,
            epochWith(Set.of("trigger:start", "core:poll"), Set.of(), Set.of()));

        auditor.auditEpochClose("run-1", "trigger:start", 2, loopPlan(), false);

        String warn = warnings().get(0);
        assertTrue(warn.contains("mcp:download"), "The loop's exit target is stranded: " + warn);
        assertTrue(warn.contains("1 node(s)"),
            "Only the exit target: core:wait and mcp:get sit above the reached loop head: " + warn);
    }

    @Test
    @DisplayName("Uses the epoch it was given, not the run's latest")
    void auditsTheGivenEpoch() {
        StateSnapshot snapshot = mock(StateSnapshot.class);
        when(stateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
        when(snapshot.getEpochState(eq("trigger:start"), anyInt())).thenAnswer(inv -> {
            int epoch = inv.getArgument(1);
            // Epoch 1 was complete; epoch 2 stopped after the loop body.
            return epoch == 1
                ? epochWith(Set.of("trigger:start", "core:poll", "core:wait", "mcp:get", "mcp:download"),
                    Set.of(), Set.of())
                : epochWith(Set.of("trigger:start", "core:poll", "core:wait", "mcp:get"), Set.of(), Set.of());
        });

        auditor.auditEpochClose("run-1", "trigger:start", 1, loopPlan(), false);
        assertTrue(warnings().isEmpty(), "Epoch 1 was complete: " + warnings());

        auditor.auditEpochClose("run-1", "trigger:start", 2, loopPlan(), false);
        assertEquals(1, warnings().size(), "Epoch 2 was truncated and must warn");
    }
}
