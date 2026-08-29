package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.*;

/**
 * Guards what may run INSIDE a loop.
 *
 * <p>Every node between a loop-back's target and its source re-executes on each iteration. Most
 * nodes are safe to re-run; some are not, and the engine cannot make them safe:
 *
 * <ul>
 *   <li><b>Rejected</b> - nodes whose second pass is incoherent rather than merely wasteful: a
 *       trigger re-fired inside the graph corrupts the epoch model that signals, split contexts
 *       and interface pagination are all keyed on; a webhook response can only be sent once;
 *       stop-on-error aborts the run it is looping in. Parallel constructs (fork/merge, split)
 *       and interfaces track their state per epoch and item with no iteration dimension, so a
 *       second pass silently reuses the first pass's state.</li>
 *   <li><b>Warned</b> - nodes that work, but whose side effects repeat: an approval re-asked,
 *       an email re-sent, a sub-workflow re-run, a row re-inserted. That may be exactly what the
 *       author wants, so it is a warning, not an error.</li>
 * </ul>
 *
 * <p>The span is computed the same way the engine computes it: forward from the loop-back's
 * target, stopping at its source, ignoring other loop-backs.
 */
public class BackEdgeSafetyValidator {

    /** Node types that cannot be re-executed coherently. */
    private static final Set<String> FORBIDDEN_CORE_TYPES = Set.of(
        "fork", "merge", "stop_on_error", "respond_to_webhook"
    );

    /**
     * Node types whose side effects simply repeat - the author may well intend that.
     *
     * <p>{@code split} is here rather than in the forbidden set because the engine genuinely
     * supports it: iterating a split inside a loop is what the back-edge claim dedup exists for
     * (several split items reach the same loop-back and only the first drives it). It still
     * deserves a mention, because every iteration re-processes the whole list.
     */
    private static final Set<String> WARNED_CORE_TYPES = Set.of(
        "user_approval", "approval", "sub_workflow", "send_email", "split"
    );

    public void validate(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        List<String[]> backEdges = graph.getBackEdges();
        if (backEdges.isEmpty()) {
            return;
        }

        Map<String, String> coreTypesByKey = coreTypesByKey(session);
        Set<String> interfaceKeys = interfaceKeys(session);

        for (String[] backEdge : backEdges) {
            String sourceKey = backEdge[0];
            String targetKey = backEdge[1];

            // The engine takes a loop-back only when the source produced no forward successor.
            // An UNPORTED loop-back on a node that also continues forward therefore never runs -
            // silently. (A loop-back on a BRANCH is fine and is the normal shape: the branch it
            // sits on has no forward target, so selecting that branch enters the loop while the
            // other branches carry on.)
            String sourcePort = backEdge.length > 2 ? backEdge[2] : null;
            boolean unported = sourcePort == null || sourcePort.isBlank();
            if (unported && !graph.getOutgoingNodeIds(sourceKey).isEmpty()) {
                result.addError("BACK_EDGE_SOURCE_ALSO_CONTINUES", sourceKey,
                    "'" + sourceKey + "' loops back to '" + targetKey + "' but also continues "
                    + "forward, so the loop would never run: the workflow always takes the "
                    + "forward path. Put the loop-back on a branch (so one branch loops and "
                    + "another continues), or remove the forward connection.");
                continue;
            }

            // Walk from the loop's BODY entry, not from the hub. A loop-back into a hub's
            // :iterate port re-enters the BODY; walking from the hub itself also descends the
            // :exit port, so everything AFTER the loop looked like it was inside it - and a run
            // report placed after a loop, the normal shape, was rejected as "inside" it.
            // The engine resets the body span only, so reading the hub here also disagreed with
            // what actually re-executes.
            String spanStart = graph.getLoopBodyEntry(targetKey);
            if (spanStart == null) spanStart = targetKey;
            Set<String> span = graph.collectForwardSpan(spanStart, sourceKey);

            for (String nodeKey : span) {
                if (nodeKey.startsWith("trigger:")) {
                    result.addError("BACK_EDGE_TRIGGER_IN_LOOP", nodeKey,
                        "Trigger '" + nodeKey + "' is inside the loop that re-enters '" + targetKey
                        + "'. A trigger starts a run; re-running it inside the graph corrupts the "
                        + "run's own bookkeeping. Move the loop-back so it re-enters the first "
                        + "node AFTER the trigger.");
                    continue;
                }

                if (interfaceKeys.contains(nodeKey)) {
                    result.addError("BACK_EDGE_INTERFACE_IN_LOOP", nodeKey,
                        "Interface '" + nodeKey + "' is inside the loop that re-enters '" + targetKey
                        + "'. An interface keeps one set of data per run and has no notion of "
                        + "iteration, so every pass after the first would show the first pass's "
                        + "data. Put the interface after the loop instead.");
                    continue;
                }

                String coreType = coreTypesByKey.get(nodeKey);
                if (coreType == null) {
                    continue;
                }

                if (FORBIDDEN_CORE_TYPES.contains(coreType)) {
                    result.addError("BACK_EDGE_UNSAFE_NODE_IN_LOOP", nodeKey,
                        "Node '" + nodeKey + "' (" + coreType + ") is inside the loop that re-enters '"
                        + targetKey + "'. It tracks its state once per run and cannot be re-entered, "
                        + "so a second pass would reuse the first pass's state. Move it outside the "
                        + "loop, or end the loop before it.");
                } else if (WARNED_CORE_TYPES.contains(coreType)) {
                    result.addWarning("BACK_EDGE_REPEATED_SIDE_EFFECT", nodeKey,
                        "Node '" + nodeKey + "' (" + coreType + ") runs again on every iteration of "
                        + "the loop that re-enters '" + targetKey + "'. Confirm that repeating its "
                        + "effect is intended.");
                }
            }
        }
    }

    private Map<String, String> coreTypesByKey(WorkflowBuilderSession session) {
        Map<String, String> types = new HashMap<>();
        for (Map<String, Object> core : session.getCores()) {
            String label = (String) core.get("label");
            String type = (String) core.get("type");
            if (label != null && type != null) {
                types.put(LabelNormalizer.coreKey(label), type);
            }
        }
        return types;
    }

    private Set<String> interfaceKeys(WorkflowBuilderSession session) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> iface : session.getInterfaces()) {
            String label = (String) iface.get("label");
            if (label == null) {
                label = (String) iface.get("name");
            }
            if (label != null) {
                keys.add(LabelNormalizer.interfaceKey(label));
            }
        }
        return keys;
    }
}
