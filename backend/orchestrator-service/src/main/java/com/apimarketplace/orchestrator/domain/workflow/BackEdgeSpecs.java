package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.EdgeRefParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves plan edges into {@link BackEdgeSpec}s.
 *
 * <p>A pure function over the plan's existing accessors rather than a method on
 * {@link WorkflowPlan}: resolution is behaviour, the plan is data, and keeping it out of the
 * plan means the granular accessors ({@code getIterateEdgesForSource}, {@code findLoopBodyTarget},
 * …) remain the single seam the engine reads a loop through.
 */
public final class BackEdgeSpecs {

    private BackEdgeSpecs() {}

    /**
     * Every loop-back whose source is the given node.
     *
     * <p>Two shapes are collected: {@code :iterate}-port edges into a {@code core:loop} hub, and
     * edges carrying a declared {@link Edge.BackEdge} marker. The source is matched port-stripped,
     * so a Decision's {@code core:check:else} back-edge is found from {@code core:check}.
     */
    public static List<BackEdgeSpec> forSource(WorkflowPlan plan, String sourceNodeId) {
        if (plan == null || sourceNodeId == null) return List.of();

        List<BackEdgeSpec> specs = new ArrayList<>();

        for (Edge edge : plan.getIterateEdgesForSource(sourceNodeId)) {
            BackEdgeSpec spec = resolve(plan, edge);
            if (spec != null) specs.add(spec);
        }

        for (Edge edge : plan.getEdges()) {
            if (edge.backEdge() == null) continue;
            if (!sourceNodeId.equals(EdgeRefParser.getNodeKey(edge.from()))) continue;
            BackEdgeSpec spec = resolve(plan, edge);
            if (spec != null) specs.add(spec);
        }

        return specs;
    }

    /**
     * Every loop-back in the plan.
     */
    public static List<BackEdgeSpec> all(WorkflowPlan plan) {
        if (plan == null) return List.of();

        List<BackEdgeSpec> specs = new ArrayList<>();

        // Same two accessors as forSource(), in the same order, so both entry points see the
        // same loops.
        for (Edge edge : plan.getIterateEdges()) {
            BackEdgeSpec spec = resolve(plan, edge);
            if (spec != null) specs.add(spec);
        }

        for (Edge edge : plan.getEdges()) {
            if (edge.backEdge() == null) continue;
            BackEdgeSpec spec = resolve(plan, edge);
            if (spec != null) specs.add(spec);
        }

        return specs;
    }

    /**
     * Resolve one edge, or null when it is not a loop-back or its endpoints cannot be parsed.
     *
     * <p>Deliberately lenient about a hub that resolves only partially (no loop Core declared, no
     * {@code :body} edge yet): this describes the loop, it does not decide whether it can run.
     * Callers that need a body entry guard on {@link BackEdgeSpec#bodyEntryKey()} being null, so a
     * half-wired loop is skipped at the point of use instead of disappearing from every query.
     */
    public static BackEdgeSpec resolve(WorkflowPlan plan, Edge edge) {
        if (plan == null || !WorkflowPlan.isBackEdge(edge)) return null;

        String sourceKey = EdgeRefParser.getNodeKey(edge.from());
        String sourcePort = EdgeRefParser.getPort(edge.from());

        if (edge.backEdge() == null) {
            // Hub shape: the loop Core owns condition, cap, body entry and exit.
            String hubKey = EdgeRefParser.getNodeKey(edge.to());
            if (hubKey == null) return null;

            Optional<Core> hub = plan.findLoopCoreForIterateEdge(edge);
            return new BackEdgeSpec(
                edge, edge.getEdgeId(), sourceKey, sourcePort,
                plan.findLoopBodyTarget(hubKey), hubKey,
                hub.map(Core::loopCondition).orElse(null),
                hub.map(Core::maxIterations).orElse(null),
                plan.findLoopExitTarget(hubKey));
        }

        // Declared back-edge: its own target IS the re-entry point, and there is no exit port -
        // when the loop stops, the source node's other port has already routed.
        String bodyEntry = EdgeRefParser.getNodeKey(edge.to());
        if (bodyEntry == null) return null;

        Edge.BackEdge marker = edge.backEdge();
        return new BackEdgeSpec(
            edge, edge.getEdgeId(), sourceKey, sourcePort,
            bodyEntry, null,
            marker.condition(), marker.maxIterations(),
            null);
    }
}
