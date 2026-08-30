package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes workflow graph structure for validation.
 * Provides methods for cycle detection, reachability analysis, and edge counting.
 *
 * Note: This is different from GraphAnalyzer in the parent package which
 * analyzes variable accessibility based on WorkflowPlan objects.
 */
public class ValidationGraphAnalyzer {

    private final WorkflowBuilderSession session;
    private final Map<String, List<String>> outgoing = new HashMap<>();
    private final Map<String, List<String>> incoming = new HashMap<>();
    private final Set<String> allNodes = new HashSet<>();
    private final List<String[]> backEdges = new ArrayList<>();
    /** Loop hub -> the node its :body port enters, so a span walk can skip the :exit path. */
    private final Map<String, String> bodyEntryByHub = new HashMap<>();

    public ValidationGraphAnalyzer(WorkflowBuilderSession session) {
        this.session = session;
        buildGraph();
    }

    private void buildGraph() {
        // Collect all node IDs using LabelNormalizer as single source of truth
        for (Map<String, Object> trigger : session.getTriggers()) {
            String label = (String) trigger.get("label");
            allNodes.add(LabelNormalizer.triggerKey(label));
        }
        for (Map<String, Object> step : session.getMcps()) {
            String label = (String) step.get("label");
            Boolean isAgent = (Boolean) step.get("isAgent");
            if (isAgent != null && isAgent) {
                allNodes.add(LabelNormalizer.agentKey(label));
            } else {
                allNodes.add(LabelNormalizer.mcpKey(label));
            }
        }
        for (Map<String, Object> cn : session.getCores()) {
            String label = (String) cn.get("label");
            // Use LabelNormalizer.coreKey() to be consistent with edge format (core:label)
            allNodes.add(LabelNormalizer.coreKey(label));
        }
        for (Map<String, Object> iface : session.getInterfaces()) {
            String label = (String) iface.get("label");
            if (label == null) {
                label = (String) iface.get("name");
            }
            allNodes.add(LabelNormalizer.interfaceKey(label));
        }
        for (Map<String, Object> table : session.getTables()) {
            String label = (String) table.get("label");
            allNodes.add(LabelNormalizer.tableKey(label));
        }

        // Build edge maps - V2 format: simple { from, to } with optional ports
        for (Map<String, Object> edge : session.getEdges()) {
            String from = (String) edge.get("from");
            String to = edge.get("to") instanceof String ? (String) edge.get("to") : null;

            if (from == null || to == null) {
                continue;
            }

            // V2: both sides are keyed port-stripped. Storing the RAW `to` used to leave a
            // ported target ("core:check:if") under a key nothing else uses, so a cycle through
            // a branch port never closed in the detector and never counted as an incoming edge.
            String baseFromNode = extractBaseNodeId(from);
            String baseToNode = extractBaseNodeId(to);

            // Remember which successor the :body port leads to BEFORE the port is stripped.
            // Afterwards body and exit are indistinguishable, and a span walk started at the hub
            // descends both - which is what made everything after a loop look like it was inside.
            if ("body".equals(EdgeRefParser.getPort(from))) {
                bodyEntryByHub.putIfAbsent(baseFromNode, baseToNode);
            }

            if (isBackEdge(edge, to)) {
                // A loop-back is a re-entry, not a dependency: it must not make its target
                // reachable, must not count as an incoming edge (which would make every
                // loop-back into a Decision a "multiple incoming" error), and must not close a
                // cycle in the detector (declaring it is precisely how the author legalises it).
                //
                // Only DECLARED back-edges are reported to the span checks. An :iterate edge
                // belongs to a loop node, a construct that already shipped: its span cannot be
                // derived here anyway (the loop node's body and exit ports are indistinguishable
                // once the port is stripped, so the walk would run down the exit path and flag
                // everything AFTER the loop), and applying new restrictions to it would reject
                // workflows that run correctly today.
                if (edge.get("backEdge") != null) {
                    // The source PORT is kept: a loop-back hanging off a branch behaves very
                    // differently from one on a plain node, and the difference decides whether
                    // the loop can ever run.
                    backEdges.add(new String[] { baseFromNode, baseToNode, EdgeRefParser.getPort(from) });
                }
                continue;
            }

            outgoing.computeIfAbsent(baseFromNode, k -> new ArrayList<>()).add(baseToNode);
            incoming.computeIfAbsent(baseToNode, k -> new ArrayList<>()).add(baseFromNode);
        }
    }

    /**
     * A session edge that closes a loop: an edge into a loop node's {@code :iterate} port, or one
     * carrying the declared {@code backEdge} marker.
     */
    private static boolean isBackEdge(Map<String, Object> edge, String to) {
        if (edge.get("backEdge") != null) return true;
        return "iterate".equals(EdgeRefParser.getPort(to));
    }

    /**
     * DECLARED loop-backs as {sourceKey, targetKey} pairs, port-stripped.
     *
     * <p>Excludes loop-node {@code :iterate} edges - see the note in {@link #buildGraph()}.
     */
    public List<String[]> getBackEdges() {
        return backEdges;
    }

    /**
     * Forward successors of a node (loop-backs excluded), port-stripped.
     */
    public List<String> getOutgoingNodeIds(String nodeKey) {
        return outgoing.getOrDefault(nodeKey, List.of());
    }

    /**
     * The node a loop hub enters its body with, i.e. the target of its {@code :body} port.
     *
     * <p>Returns null when {@code hubKey} is not a hub (a declared back-edge re-enters its own
     * target directly, which is already the body entry).
     */
    public String getLoopBodyEntry(String hubKey) {
        if (hubKey == null) return null;
        return bodyEntryByHub.get(hubKey);
    }

    /**
     * Nodes reachable forward from {@code fromKey}, walled at {@code untilKey} (inclusive).
     *
     * <p>This is the set that re-executes on each iteration of a loop-back going from
     * {@code untilKey} back to {@code fromKey}. Loop-backs are already absent from the
     * adjacency, so the walk terminates.
     */
    public Set<String> collectForwardSpan(String fromKey, String untilKey) {
        Set<String> span = new LinkedHashSet<>();
        if (fromKey == null) return span;

        Deque<String> queue = new ArrayDeque<>();
        queue.add(fromKey);
        span.add(fromKey);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(untilKey)) {
                continue;
            }
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (span.add(next)) {
                    queue.add(next);
                }
            }
        }
        return span;
    }

    /**
     * V2: Extract base node ID from a port-based reference.
     * Examples:
     * - "core:my_loop:body" -> "core:my_loop"
     * - "core:check:if" -> "core:check"
     * - "agent:classify:category_0" -> "agent:classify"
     * - "mcp:my_step" -> "mcp:my_step"
     */
    private String extractBaseNodeId(String nodeRef) {
        if (nodeRef == null) return null;
        // EdgeRefParser is the single source of truth for the port set.
        return EdgeRefParser.splitPort(nodeRef)[0];
    }

    public boolean nodeExists(String nodeId) {
        // First check exact match
        if (allNodes.contains(nodeId)) {
            return true;
        }
        // For port-based references (e.g., core:if_else:if), check if base node exists
        String baseNode = extractBaseNodeId(nodeId);
        return allNodes.contains(baseNode);
    }

    public Set<String> getAllNodeIds() {
        return allNodes;
    }

    public Map<String, Integer> getIncomingEdgeCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String node : allNodes) {
            counts.put(node, incoming.getOrDefault(node, List.of()).size());
        }
        return counts;
    }

    public Set<String> getReachableFromTriggers() {
        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        // Start from all triggers
        for (String node : allNodes) {
            if (node.startsWith("trigger:")) {
                queue.add(node);
                reachable.add(node);
            }
        }

        // BFS
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (!reachable.contains(next)) {
                    reachable.add(next);
                    queue.add(next);
                }
            }
        }

        return reachable;
    }

    public List<String> detectCycles() {
        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();

        for (String node : allNodes) {
            detectCyclesDFS(node, visited, recStack, new ArrayList<>(), cycles);
        }

        return cycles;
    }

    private boolean detectCyclesDFS(String node, Set<String> visited, Set<String> recStack,
                                    List<String> path, List<String> cycles) {
        if (recStack.contains(node)) {
            // Found cycle
            int startIdx = path.indexOf(node);
            if (startIdx >= 0) {
                String cycle = path.subList(startIdx, path.size()).stream()
                        .collect(Collectors.joining(" -> ")) + " -> " + node;
                cycles.add(cycle);
            }
            return true;
        }

        if (visited.contains(node)) {
            return false;
        }

        visited.add(node);
        recStack.add(node);
        path.add(node);

        for (String next : outgoing.getOrDefault(node, List.of())) {
            detectCyclesDFS(next, visited, recStack, path, cycles);
        }

        path.remove(path.size() - 1);
        recStack.remove(node);
        return false;
    }

    public boolean hasOutgoingEdges(String nodeId) {
        List<String> edges = outgoing.get(nodeId);
        return edges != null && !edges.isEmpty();
    }
}
