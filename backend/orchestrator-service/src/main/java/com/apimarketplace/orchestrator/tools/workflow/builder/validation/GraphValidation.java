package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Validates workflow graph structure.
 *
 * Rules enforced:
 * - All nodes must be reachable from triggers
 * - No UNDECLARED cycles: a loop must be declared (a loop node's iterate port, or a back-edge
 *   marker), otherwise the engine treats every edge in it as a dependency and it deadlocks
 * - Nothing unsafe to re-run inside a declared loop ({@link BackEdgeSafetyValidator})
 */
@Slf4j
@Component
public class GraphValidation implements WorkflowValidator {

    private final BackEdgeSafetyValidator backEdgeSafetyValidator = new BackEdgeSafetyValidator();

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        validate(session, new ValidationGraphAnalyzer(session), result);
    }

    /**
     * Validate with an existing graph analyzer (for performance when validating multiple aspects).
     */
    public void validate(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        validateReachability(session, graph, result);
        validateCycles(session, graph, result);
        backEdgeSafetyValidator.validate(session, graph, result);
    }


    private void validateReachability(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        Set<String> reachable = graph.getReachableFromTriggers();
        Set<String> allNodes = graph.getAllNodeIds();

        for (String nodeId : allNodes) {
            // Skip triggers (they are starting points)
            if (nodeId.startsWith("trigger:")) continue;

            if (!reachable.contains(nodeId)) {
                result.addError("UNREACHABLE_NODE", nodeId,
                        "Node '" + nodeId + "' is not reachable from any trigger. " +
                        "Use workflow(action='add_node', type='<tool-uuid>', ..., connect_after='Source Label') or workflow(action='connect', from='Source Label', to='Target Label').");
            }
        }
    }

    /**
     * A loop must be DECLARED, never inferred.
     *
     * <p>Declared loop-backs (a loop node's {@code iterate} port, or an edge carrying the
     * {@code backEdge} marker) are excluded from the graph, so anything the detector still finds
     * is a cycle nobody asked for: it would deadlock at run time, because the engine treats every
     * remaining edge as a dependency and each node in the cycle would wait for the next one.
     *
     * <p>The previous rule accepted any cycle whose printed path merely mentioned a "core:" node,
     * which let an accidental cycle through any control node through, while rejecting a
     * deliberate loop between two tool nodes.
     */
    private void validateCycles(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        List<String> cycles = graph.detectCycles();

        for (String cycle : cycles) {
            result.addError("CYCLE_DETECTED", null,
                    "Cycle detected: " + cycle + ". A loop must be declared: connect the last node "
                    + "of the loop back to a loop node's iterate port, or mark the closing "
                    + "connection as a loop-back so it re-enters instead of waiting for itself.");
        }
    }
}
