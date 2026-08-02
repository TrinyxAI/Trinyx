package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.BackEdgeSpec;
import com.apimarketplace.orchestrator.domain.workflow.BackEdgeSpecs;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The span: which nodes re-run on each iteration.
 *
 * <p>This set answers two questions that MUST give the same answer - what to reset before
 * re-entering, and what to stamp with the current iteration. When they disagree, a node is reset
 * but not stamped, its second row collides with the first on the storage unique key and is
 * dropped, and the run finishes GREEN showing the first pass's data with no error anywhere.
 */
@DisplayName("BackEdgeSpanResolver")
class BackEdgeSpanResolverTest {

    private static WorkflowPlan plan(List<Map<String, Object>> edges) {
        return WorkflowPlan.fromMap(Map.of("edges", edges), "tenant");
    }

    private static Map<String, Object> forward(String from, String to) {
        return Map.of("from", from, "to", to);
    }

    private static Map<String, Object> backEdge(String from, String to) {
        return Map.of("from", from, "to", to, "backEdge", Map.of("maxIterations", 5));
    }

    private static BackEdgeSpec onlySpec(WorkflowPlan p) {
        List<BackEdgeSpec> specs = BackEdgeSpecs.all(p);
        assertEquals(1, specs.size(), "fixture should declare exactly one loop-back");
        return specs.get(0);
    }

    @Test
    @DisplayName("the span is everything between the re-entry point and the node that closes it")
    void spanCoversTheLoopBody() {
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "mcp:parse"),
            forward("mcp:parse", "core:check"),
            backEdge("core:check:else", "mcp:fetch")
        ));

        Set<String> span = BackEdgeSpanResolver.resolveSpan(p, onlySpec(p));

        assertEquals(Set.of("mcp:fetch", "mcp:parse", "core:check"), span);
    }

    @Test
    @DisplayName("nodes before the re-entry point are NOT re-run")
    void spanExcludesNodesBeforeTheLoop() {
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "mcp:setup"),
            forward("mcp:setup", "mcp:fetch"),
            forward("mcp:fetch", "core:check"),
            backEdge("core:check:else", "mcp:fetch")
        ));

        Set<String> span = BackEdgeSpanResolver.resolveSpan(p, onlySpec(p));

        assertFalse(span.contains("mcp:setup"), "Setup ran once and must not be redone");
        assertFalse(span.contains("trigger:start"));
    }

    @Test
    @DisplayName("the branch that exits the loop is NOT reset on every iteration")
    void spanExcludesTheExitBranch() {
        // A Decision closing a hub-less loop has exactly two ports: the one looping back, and the
        // one leaving. Everything downstream of the leaving port runs AFTER the loop, so pulling
        // it into the span would reset the whole tail of the workflow on every single iteration.
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "core:check"),
            forward("core:check:if", "mcp:done"),
            forward("mcp:done", "mcp:notify"),
            backEdge("core:check:else", "mcp:fetch")
        ));

        Set<String> span = BackEdgeSpanResolver.resolveSpan(p, onlySpec(p));

        assertEquals(Set.of("mcp:fetch", "core:check"), span);
        assertFalse(span.contains("mcp:done"), "The exit branch runs after the loop, not inside it");
        assertFalse(span.contains("mcp:notify"));
    }

    @Test
    @DisplayName("a hub loop still resets a branch that lives inside its body")
    void hubLoopSpanIncludesPortedBranchesInsideTheBody() {
        // The opposite case, and the reason addPortedBranchDescendants exists: with a loop node,
        // the body's last step can be a Decision whose branches are INSIDE the body. Those must
        // be reset with the rest of the iteration, or the next pass finds them already completed.
        WorkflowPlan p = WorkflowPlan.fromMap(Map.of(
            "edges", List.of(
                forward("trigger:start", "core:retry"),
                forward("core:retry:body", "core:check"),
                forward("core:check:if", "mcp:handled"),
                forward("core:check:else", "mcp:skipped"),
                forward("core:retry:exit", "mcp:done"),
                forward("core:check", "core:retry:iterate")
            ),
            "cores", List.of(Map.of("id", "retry", "type", "loop", "label", "retry",
                "loopCondition", "{{x}}", "maxIterations", 3))
        ), "tenant");

        Set<String> span = BackEdgeSpanResolver.resolveSpan(p, onlySpec(p));

        assertTrue(span.contains("mcp:handled"));
        assertTrue(span.contains("mcp:skipped"));
        assertFalse(span.contains("mcp:done"), "The loop's exit path is not part of an iteration");
    }

    @Test
    @DisplayName("isInSpan agrees with resolveSpan for every node")
    void isInSpanAgreesWithResolveSpan() {
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "mcp:parse"),
            forward("mcp:parse", "core:check"),
            backEdge("core:check:else", "mcp:fetch")
        ));
        BackEdgeSpec spec = onlySpec(p);
        Set<String> span = BackEdgeSpanResolver.resolveSpan(p, spec);

        for (String node : List.of("trigger:start", "mcp:setup", "mcp:fetch", "mcp:parse", "core:check")) {
            assertEquals(span.contains(node), BackEdgeSpanResolver.isInSpan(p, spec, node),
                "reset span and iteration-stamp span must never disagree for " + node);
        }
    }

    @Test
    @DisplayName("the walk terminates on a plan whose edge list is cyclic")
    void walkTerminatesOnCyclicPlan() {
        // Two loop-backs closing over the same nodes. Following either during the walk would
        // never terminate; they are excluded from the adjacency for exactly this reason.
        WorkflowPlan p = plan(List.of(
            forward("trigger:start", "mcp:a"),
            forward("mcp:a", "mcp:b"),
            forward("mcp:b", "mcp:c"),
            backEdge("mcp:c", "mcp:a"),
            backEdge("mcp:b", "mcp:a")
        ));

        List<BackEdgeSpec> specs = BackEdgeSpecs.all(p);
        assertEquals(2, specs.size());

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (BackEdgeSpec spec : specs) {
                assertFalse(BackEdgeSpanResolver.resolveSpan(p, spec).isEmpty());
            }
        });
    }

    @Test
    @DisplayName("a hub loop spans from the loop's body entry, not from the loop node")
    void hubLoopSpansFromBodyEntry() {
        WorkflowPlan p = WorkflowPlan.fromMap(Map.of(
            "edges", List.of(
                forward("trigger:start", "core:retry"),
                forward("core:retry:body", "mcp:attempt"),
                forward("core:retry:exit", "mcp:done"),
                forward("mcp:attempt", "core:retry:iterate")
            ),
            "cores", List.of(Map.of("id", "retry", "type", "loop", "label", "retry",
                "loopCondition", "{{x}}", "maxIterations", 3))
        ), "tenant");

        Set<String> span = BackEdgeSpanResolver.resolveSpan(p, onlySpec(p));

        assertTrue(span.contains("mcp:attempt"), "The body re-runs");
        assertFalse(span.contains("mcp:done"), "The exit path is downstream of the loop, not in it");
    }
}
