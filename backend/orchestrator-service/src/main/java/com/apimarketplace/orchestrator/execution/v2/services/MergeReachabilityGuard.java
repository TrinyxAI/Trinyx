package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a merge node is UNREACHABLE for the item it is about to run for:
 * every one of its predecessors reached a terminal state for that (epoch, item), and
 * every one of them is SKIPPED.
 *
 * <p>A merge fires when all its predecessors are terminal, SKIPPED included. That rule is
 * what lets a branch that was not taken stop holding the merge, but on its own it also lets
 * a merge run when NO branch was taken - it then executes on an item that was routed
 * somewhere else entirely. Prod run {@code run_<id>} epoch 152: the three
 * reply-category Move nodes were all SKIPPED for the item classified as spam, an approval
 * gate on that item's real branch held the epoch open, and on resume the merge
 * {@code agent:draft_reply} was declared ready and burned a 70k-token Opus call drafting a
 * reply to a mail whose route was "delete". The same shape fired in the only two other
 * epochs, across two runs, where that gate ever resolved.
 *
 * <p>{@link com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService}
 * already refuses to fire such a merge ({@code anyCompleted}), but it decides from the
 * IN-MEMORY execution results, which are node-level: inside a split it never sees all
 * predecessors resolved and so never gets to apply its own rule. This guard reads the
 * durable per-item rows instead, which is the only source that can tell "skipped for THIS
 * item" from "skipped for every item".
 *
 * <p>The verdict is the one the public docs already state ("Merge is itself skipped only when
 * every predecessor was skipped"); it was the engine that disagreed with them.
 *
 * <p><b>Fails open by design.</b> Anything unknown - no repository, no run id, a predecessor
 * with no row yet, a query error - means "reachable", i.e. today's behaviour. The guard only
 * ever suppresses an execution it can prove pointless.
 */
@Service
public class MergeReachabilityGuard {

    private static final Logger logger = LoggerFactory.getLogger(MergeReachabilityGuard.class);

    /** Skip reason persisted on a merge that no branch reached. */
    public static final String SKIP_REASON = "All predecessors were skipped for this item";

    private final WorkflowStepDataRepository stepDataRepository;

    public MergeReachabilityGuard(WorkflowStepDataRepository stepDataRepository) {
        this.stepDataRepository = stepDataRepository;
    }

    /**
     * @return true when {@code node} is a merge whose every predecessor is SKIPPED for
     *         {@code context}'s epoch and item, so executing it would run a node no branch
     *         reached. False whenever that cannot be established.
     */
    public boolean isUnreachableForItem(ExecutionNode node, ExecutionContext context) {
        if (node == null || context == null || stepDataRepository == null) {
            return false;
        }
        String runId = context.runId();
        if (runId == null || runId.isBlank()) {
            return false;
        }

        Set<String> predecessorKeys = distinctPredecessorKeys(node);
        // Only merges. A single-predecessor node whose predecessor was skipped is already
        // handled by the ordinary skip cascade, and widening the rule there would change
        // the behaviour of every linear chain in the product.
        if (predecessorKeys.size() < 2) {
            return false;
        }

        List<Object[]> rows;
        try {
            rows = stepDataRepository.findTerminalStatusesForItem(
                runId, predecessorKeys, context.epoch(), context.itemIndex());
        } catch (Exception e) {
            logger.warn("[MergeReachability] Could not read predecessor statuses, treating merge as reachable: nodeId={}, runId={}, error={}",
                node.getNodeId(), runId, e.getMessage());
            return false;
        }
        if (rows == null || rows.isEmpty()) {
            return false;
        }

        Set<String> terminalKeys = new LinkedHashSet<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            String status = row[1] == null ? null : row[1].toString();
            // One live predecessor is enough: the merge has real input for this item.
            if (!"SKIPPED".equals(status)) {
                return false;
            }
            terminalKeys.add(row[0].toString());
        }

        // A predecessor with no row for this (epoch, item) has not spoken yet - never
        // conclude from silence, that is how a persistence hiccup would turn into a
        // mass-skip of the downstream chain.
        if (!terminalKeys.containsAll(predecessorKeys)) {
            return false;
        }

        logger.info("[MergeReachability] Merge {} is UNREACHABLE for item {} (epoch {}): every predecessor {} is SKIPPED - suppressing execution",
            node.getNodeId(), context.itemIndex(), context.epoch(), predecessorKeys);
        return true;
    }

    /**
     * Predecessor node keys with any port stripped ({@code core:decision:if} to
     * {@code core:decision}), which is the form {@code workflow_step_data.normalized_key}
     * stores.
     */
    private Set<String> distinctPredecessorKeys(ExecutionNode node) {
        Set<String> keys = new LinkedHashSet<>();
        List<String> predecessorIds = node.getPredecessorIds();
        if (predecessorIds == null) {
            return keys;
        }
        for (String predecessorId : predecessorIds) {
            if (predecessorId == null || predecessorId.isBlank()) {
                continue;
            }
            String key = EdgeRefParser.getNodeKey(predecessorId);
            keys.add(key != null ? key : predecessorId);
        }
        return keys;
    }
}
