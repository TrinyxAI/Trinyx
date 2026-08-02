package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.resume.MergeNodeAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE invariant that makes loops safe: a back-edge is never wired as a dependency.
 *
 * <p>The plan's edge list is cyclic, but the graph the engine executes must stay acyclic. If a
 * loop-back were wired like an ordinary edge, three things would break at once, all silently:
 * <ol>
 *   <li>its target would gain a second incoming edge and become an implicit merge, waiting for a
 *       node that only runs AFTER it - a deadlock with no error;</li>
 *   <li>the node graph would contain a real cycle, so tree traversal would recurse without
 *       bound;</li>
 *   <li>readiness would never resolve, and the run would sit there looking healthy.</li>
 * </ol>
 * These tests pin the exclusion at each place that computes dependencies, for BOTH authored
 * shapes (a loop node's iterate port, and a declared back-edge with no loop node at all).
 */
@DisplayName("Back-edge wiring invariant: a loop-back is never a dependency")
class BackEdgeWiringInvariantTest {

    private final MergeNodeAnalyzer mergeNodeAnalyzer = new MergeNodeAnalyzer();

    private static WorkflowPlan plan(List<Map<String, Object>> edges) {
        return WorkflowPlan.fromMap(Map.of("edges", edges), "tenant");
    }

    private static Map<String, Object> forward(String from, String to) {
        return Map.of("from", from, "to", to);
    }

    private static Map<String, Object> declaredBackEdge(String from, String to) {
        return Map.of("from", from, "to", to, "backEdge", Map.of("maxIterations", 5));
    }

    @Test
    @DisplayName("a declared back-edge does not make its target a merge node")
    void declaredBackEdgeDoesNotCreateMerge() {
        // fetch -> check, and check's else loops back to fetch. fetch now has two incoming
        // edges in the plan, but only ONE of them is a dependency.
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "core:check"),
            declaredBackEdge("core:check:else", "mcp:fetch")
        ));

        assertFalse(mergeNodeAnalyzer.isMergeNode(p, "mcp:fetch"),
            "The loop's entry point must not wait for the node that closes the loop");
        assertEquals(List.of("trigger:start"), mergeNodeAnalyzer.findPredecessorsFromEdges(p, "mcp:fetch"),
            "Only the forward edge is a predecessor");
    }

    @Test
    @DisplayName("an iterate-port edge does not make the loop node a merge node")
    void iterateEdgeDoesNotCreateMerge() {
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "core:retry"),
            forward("core:retry:body", "mcp:attempt"),
            forward("mcp:attempt", "core:retry:iterate")
        ));

        assertFalse(mergeNodeAnalyzer.isMergeNode(p, "core:retry"));
        assertEquals(List.of("trigger:start"), mergeNodeAnalyzer.findPredecessorsFromEdges(p, "core:retry"));
    }

    @Test
    @DisplayName("a genuine merge is still detected - the exclusion must not swallow real ones")
    void realMergeIsStillDetected() {
        WorkflowPlan p = plan(List.of(
            forward("mcp:a", "core:join"),
            forward("mcp:b", "core:join")
        ));

        assertTrue(mergeNodeAnalyzer.isMergeNode(p, "core:join"));
        assertEquals(2, mergeNodeAnalyzer.findPredecessorsFromEdges(p, "core:join").size());
    }

    @Test
    @DisplayName("a back-edge creates no dependency in the execution graph")
    void backEdgeCreatesNoDependencyInExecutionGraph() {
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "core:check"),
            declaredBackEdge("core:check:else", "mcp:fetch")
        ));

        var graph = p.getExecutionGraph();

        assertFalse(graph.getDependencies("mcp:fetch").contains("core:check"),
            "fetch must not depend on the node it loops back from, or nothing can ever start");
        assertEquals(java.util.Set.of("trigger:start"), graph.getDependencies("mcp:fetch"));
    }

    @Test
    @DisplayName("a back-edge does not merge two independent triggers into one DAG")
    void backEdgeDoesNotMergeIndependentTriggerDags() {
        // Two unrelated workflows in one plan. The loop in the first must not make the second's
        // nodes look like shared descendants.
        WorkflowPlan p = plan(List.of(
            forward("trigger:one", "mcp:fetch"),
            forward("mcp:fetch", "core:check"),
            declaredBackEdge("core:check:else", "mcp:fetch"),
            forward("trigger:two", "mcp:other")
        ));

        assertFalse(p.areTriggersInSameDagGroup("trigger:one", "trigger:two"));
    }

    @Test
    @DisplayName("the last node of a loop body is still reported as terminal to the agent")
    void loopBodyLastNodeIsStillTerminal() {
        // Its only outgoing edge is the loop-back. Counting that as a successor hides the node
        // from the agent's `outputs` entirely - it would run a workflow and see nothing come out
        // of the step that did the work.
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "core:check"),
            declaredBackEdge("core:check:else", "mcp:fetch")
        ));

        // "Terminal" is "has no dependents in the executed graph" - asserted through the graph
        // the engine actually builds, not through a re-implementation of its edge filter.
        var graph = p.getExecutionGraph();

        assertTrue(graph.getDependents("core:check").isEmpty(),
            "core:check only leads back into the loop, so it is where this run ends");
        assertFalse(graph.getDependents("mcp:fetch").isEmpty(),
            "sanity: a node with a real forward successor is not terminal");
    }
}
