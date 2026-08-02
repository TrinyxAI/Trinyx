package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.BackEdgeSpec;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;

import java.util.*;

/**
 * Resolves the SPAN of a loop-back: the set of nodes that re-execute on each iteration.
 *
 * <p>The span is walked forward from the loop's body entry along plan edges, and walled at the
 * back-edge source. Back-edges themselves are excluded from the adjacency, so the walk terminates
 * even though the plan's edge list is cyclic.
 *
 * <p><b>Why this is one component and not two helpers.</b> The span answers two questions that
 * MUST agree:
 * <ol>
 *   <li><b>What to reset</b> before re-entering the loop ({@code BackEdgeHandler}).</li>
 *   <li><b>What to stamp</b> with the current iteration ({@code NodeCompletionService}).</li>
 * </ol>
 * If the two ever disagree, a node is reset but not stamped, so every pass after the first
 * collides on the {@code workflow_step_data} unique key and is dropped by
 * {@code ON CONFLICT DO NOTHING}. The run stays GREEN while showing pass-1 data. There is no
 * error anywhere to trace, which is why the two callers share this single implementation.
 *
 * <p>Stateless by design (static, no dependencies): both callers already exist as Spring beans
 * with their own constructors, and threading a new dependency through them buys nothing.
 */
public final class BackEdgeSpanResolver {

    private BackEdgeSpanResolver() {}

    /**
     * Nodes that re-execute on each iteration of this loop-back.
     */
    public static Set<String> resolveSpan(WorkflowPlan plan, BackEdgeSpec spec) {
        if (plan == null || spec == null) return Set.of();
        Set<String> span = computeSubgraphBetween(spec.bodyEntryKey(), spec.sourceKey(), plan);

        // A loop node's body can end on a branching node whose branches live INSIDE the body;
        // the forward walk stops at that node, so its branches need pulling in explicitly or the
        // next iteration finds them already completed.
        //
        // A hub-less loop-back is the opposite case: it hangs off one port of the closing node,
        // and the node's OTHER ports are how the workflow leaves the loop. Their descendants run
        // after the loop, so pulling them in would reset the whole tail of the workflow on every
        // iteration - including nodes a parallel branch may already have completed.
        if (spec.hasHub()) {
            addPortedBranchDescendants(span, spec.sourceKey(), plan);
        }
        return span;
    }

    /**
     * True when the node re-executes as part of this loop-back's body.
     */
    public static boolean isInSpan(WorkflowPlan plan, BackEdgeSpec spec, String nodeId) {
        if (nodeId == null) return false;
        return resolveSpan(plan, spec).contains(nodeId);
    }

    /**
     * Compute the set of node IDs in the subgraph between targetNode and sourceNode.
     * BFS from target following forward edges (from plan), collecting all nodes until reaching
     * source (inclusive).
     *
     * <p>Uses plan edges instead of the ExecutionNode tree because branching targets
     * (decision/option ports) are not in {@code getSuccessors()} - only the plan captures them.
     */
    public static Set<String> computeSubgraphBetween(String targetNodeId, String sourceNodeId, WorkflowPlan plan) {
        Map<String, Set<String>> successors = buildForwardAdjacency(plan);

        Set<String> subgraph = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(targetNodeId);
        visited.add(targetNodeId);
        subgraph.add(targetNodeId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();

            // Don't traverse beyond source node (boundary of loop body)
            if (currentId.equals(sourceNodeId)) {
                subgraph.add(currentId);
                continue;
            }

            for (String successorId : successors.getOrDefault(currentId, Set.of())) {
                if (visited.add(successorId)) {
                    subgraph.add(successorId);
                    queue.add(successorId);
                }
            }
        }

        return subgraph;
    }

    /**
     * Overload that uses an ExecutionNode map for subgraph computation.
     * Used when a node map is already available.
     */
    public static Set<String> computeSubgraphBetween(String targetNodeId, String sourceNodeId,
                                              Map<String, ExecutionNode> nodeMap) {
        Set<String> subgraph = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(targetNodeId);
        visited.add(targetNodeId);
        subgraph.add(targetNodeId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();

            if (currentId.equals(sourceNodeId)) {
                subgraph.add(currentId);
                continue;
            }

            ExecutionNode currentNode = nodeMap.get(currentId);
            if (currentNode == null) continue;

            for (ExecutionNode successor : currentNode.getSuccessors()) {
                String successorId = successor.getNodeId();
                if (visited.add(successorId)) {
                    subgraph.add(successorId);
                    queue.add(successorId);
                }
            }
        }

        return subgraph;
    }

    /**
     * Add ported branch descendants of sourceNode to the subgraph.
     * When sourceNode is a decision/fork, its ported branch targets (e.g., decision:if -> stop)
     * are inside the loop body but not reached by computeSubgraphBetween (which stops at source).
     * Only includes targets reachable via ported edges (with a port on the from-side),
     * not simple forward edges (which typically go outside the loop).
     */
    public static void addPortedBranchDescendants(Set<String> subgraph, String sourceId, WorkflowPlan plan) {
        Map<String, Set<String>> successors = buildForwardAdjacency(plan);

        // Find ported edges FROM sourceId (decision/fork branches)
        Set<String> portedTargets = new HashSet<>();
        for (Edge edge : plan.getEdges()) {
            if (WorkflowPlan.isBackEdge(edge)) continue;
            String fromKey = EdgeRefParser.getNodeKey(edge.from());
            String fromPort = EdgeRefParser.getPort(edge.from());
            if (sourceId.equals(fromKey) && fromPort != null && !fromPort.isEmpty()) {
                String toKey = EdgeRefParser.getNodeKey(edge.to());
                if (toKey != null && !subgraph.contains(toKey)) {
                    portedTargets.add(toKey);
                }
            }
        }

        // BFS from ported targets to include all their descendants
        Queue<String> queue = new LinkedList<>(portedTargets);
        Set<String> visited = new HashSet<>(portedTargets);
        subgraph.addAll(portedTargets);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String succ : successors.getOrDefault(current, Set.of())) {
                if (!visited.contains(succ) && !subgraph.contains(succ)) {
                    visited.add(succ);
                    subgraph.add(succ);
                    queue.add(succ);
                }
            }
        }
    }

    /**
     * Adjacency over FORWARD edges only. Back-edges are excluded, which is what makes every walk
     * here terminate on a plan whose edge list contains cycles.
     */
    private static Map<String, Set<String>> buildForwardAdjacency(WorkflowPlan plan) {
        Map<String, Set<String>> successors = new HashMap<>();
        for (Edge edge : plan.getEdges()) {
            if (WorkflowPlan.isBackEdge(edge)) continue;
            String fromKey = EdgeRefParser.getNodeKey(edge.from());
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (fromKey != null && toKey != null) {
                successors.computeIfAbsent(fromKey, k -> new HashSet<>()).add(toKey);
            }
        }
        return successors;
    }
}
