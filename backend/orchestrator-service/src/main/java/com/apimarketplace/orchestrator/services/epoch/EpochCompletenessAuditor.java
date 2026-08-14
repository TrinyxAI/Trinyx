package com.apimarketplace.orchestrator.services.epoch;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reports an epoch that closes having reached only part of its DAG.
 *
 * <p>An epoch that stops early is INVISIBLE today: the run status is derived from
 * {@code failedNodeIds}, so a truncated epoch - one where the engine simply stopped finding
 * work - has no failed node and closes as COMPLETED. The 2026-08-05 production incident
 * ("xAI Video Sequence", epoch 2 executed 10 nodes out of 36) was only noticed by comparing
 * node counts between two epochs of the same run, hours later.
 *
 * <p>This auditor closes that observability gap. It is deliberately <b>diagnostic only</b>:
 * it logs, it never changes the epoch outcome. Turning an incomplete epoch into a FAILED one
 * cannot be done from this signal alone - an untaken branch that is absent rather than
 * SKIPPED would then redden a healthy run, which is worse than the current silence. The
 * WARN gives the missing node ids by name, which is what an investigation actually needs.
 *
 * <p>It reports a node only when NOTHING below it was reached either - the frontier where the
 * flow stopped, not every node the flow went around. That is what keeps it quiet on legitimate
 * shapes (a loop with {@code maxIterations=0} never runs its body, yet its exit target does).
 * The cost is two blind spots, each pinned by a test rather than left to be rediscovered:
 * a node stranded INSIDE a loop body is masked (the body's back-edge makes the always-executed
 * loop head its descendant), and a fork branch that never ran is masked when the merge below it
 * did. The second only arises when merge readiness is itself wrong - a correct merge waits for
 * every predecessor to be COMPLETED or SKIPPED. The loop's EXIT target is still reported, which
 * is where the 2026-08-05 truncation surfaced.
 *
 * <p>Known limitation, tolerable because this only ever logs: a split's per-item skips go
 * through {@code completeSkippedStepWithoutStateUpdate}, which deliberately does not touch the
 * EpochState, so a branch that no split item routed to can be reported on a healthy run when
 * nothing was reached below it.
 *
 * @see com.apimarketplace.orchestrator.trigger.ReusableTriggerService#resetForNextCycle
 */
@Component
public class EpochCompletenessAuditor {

    private static final Logger logger = LoggerFactory.getLogger(EpochCompletenessAuditor.class);

    /** Node id prefixes that never carry an execution record and must not count as unreached. */
    private static final List<String> NON_EXECUTING_PREFIXES = List.of("trigger:", "note:");

    /** Cap on the node ids printed in one line; the COUNT is always exact. */
    private static final int MAX_LOGGED_NODE_IDS = 25;

    private final StateSnapshotService stateSnapshotService;

    public EpochCompletenessAuditor(StateSnapshotService stateSnapshotService) {
        this.stateSnapshotService = stateSnapshotService;
    }

    /**
     * Log a WARN when this epoch closes without having reached every node of its DAG.
     *
     * <p>Silent when the epoch already has failures: the run is reported as FAILED anyway, and
     * the nodes downstream of the failure are unreached by design. The interesting case is the
     * one that looks clean.
     *
     * <p>Never throws: an audit must not be able to break an epoch close.
     *
     * @param triggerId the DAG being closed; a null trigger id means the caller has no DAG
     *                  coordinates, and reachability cannot be scoped - the audit is skipped
     */
    public void auditEpochClose(String runId, String triggerId, int epoch,
                                WorkflowPlan plan, boolean hasFailures) {
        if (hasFailures || runId == null || triggerId == null || plan == null || epoch < 0) {
            return;
        }
        try {
            EpochState epochState = stateSnapshotService.getSnapshot(runId).getEpochState(triggerId, epoch);

            Set<String> terminal = new HashSet<>(epochState.getCompletedNodeIds());
            terminal.addAll(epochState.getFailedNodeIds());
            terminal.addAll(epochState.getSkippedNodeIds());

            if (terminal.isEmpty()) {
                // Not a truncation - a coordinate miss. StateSnapshot.getEpochState returns
                // EpochState.fresh() (never null) for an epoch it does not know, so an unknown
                // (trigger, epoch) yields an empty terminal set and would otherwise be reported
                // as "every node of the DAG was skipped". A diagnostic must not manufacture the
                // incident it looks for.
                logger.debug("[EpochAudit] No epoch state for runId={} triggerId={} epoch={} - "
                        + "skipping completeness audit", runId, triggerId, epoch);
                return;
            }

            Map<String, List<String>> outgoing = outgoingEdges(plan);
            Set<String> unreached = new TreeSet<>();
            for (String nodeId : reachableFrom(triggerId, outgoing)) {
                if (terminal.contains(nodeId) || !executes(nodeId)) {
                    continue;
                }
                // Report only where the flow STOPPED, not where it went around. A node the
                // engine deliberately bypassed still has reached nodes downstream of it: a loop
                // body with maxIterations=0 never runs, yet the loop's exit target does. A
                // truncation has nothing reached below it - that is what "the engine stopped
                // finding work" means, and it is the difference between a diagnostic and noise.
                if (!hasReachedDescendant(nodeId, outgoing, terminal)) {
                    unreached.add(nodeId);
                }
            }
            if (unreached.isEmpty()) {
                return;
            }

            logger.warn("[EpochAudit] Epoch closed WITHOUT failures but {} node(s) of DAG {} were never "
                    + "reached: runId={}, epoch={}, unreached={}, reached={}. The epoch will be reported "
                    + "as COMPLETED even though this part of the workflow never ran - if these nodes were "
                    + "expected to execute, the engine stopped finding work before the end of the DAG.",
                unreached.size(), triggerId, runId, epoch, abbreviate(unreached), terminal.size());
        } catch (Exception e) {
            logger.debug("[EpochAudit] Completeness audit skipped for runId={} triggerId={} epoch={}: {}",
                runId, triggerId, epoch, e.getMessage());
        }
    }

    /** Keep one log line readable on a large DAG; the count above is never truncated. */
    private String abbreviate(Set<String> nodeIds) {
        if (nodeIds.size() <= MAX_LOGGED_NODE_IDS) {
            return nodeIds.toString();
        }
        List<String> head = new ArrayList<>(nodeIds).subList(0, MAX_LOGGED_NODE_IDS);
        return head + " ... and " + (nodeIds.size() - MAX_LOGGED_NODE_IDS) + " more";
    }

    /** True for node ids that are supposed to leave an execution record in the epoch. */
    private boolean executes(String nodeId) {
        return NON_EXECUTING_PREFIXES.stream().noneMatch(nodeId::startsWith);
    }

    /** The plan's edges as a node-to-successors map, ports stripped from both ends. */
    private Map<String, List<String>> outgoingEdges(WorkflowPlan plan) {
        Map<String, List<String>> outgoing = new HashMap<>();
        for (Edge edge : plan.getEdges()) {
            String from = EdgeRefParser.getNodeKey(edge.from());
            String to = EdgeRefParser.getNodeKey(edge.to());
            if (from == null || to == null) {
                continue;
            }
            outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }
        return outgoing;
    }

    /**
     * True when anything downstream of {@code nodeId} did reach a terminal state - i.e. the flow
     * continued past this node rather than stopping at it. Walks the whole descendant set, so a
     * loop's back-edge cannot make it answer on itself (visited guard).
     */
    private boolean hasReachedDescendant(String nodeId, Map<String, List<String>> outgoing,
                                         Set<String> terminal) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(outgoing.getOrDefault(nodeId, List.of()));
        seen.add(nodeId);
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!seen.add(next)) {
                continue;
            }
            if (terminal.contains(next)) {
                return true;
            }
            queue.addAll(outgoing.getOrDefault(next, List.of()));
        }
        return false;
    }

    /**
     * Node ids reachable from {@code triggerId} by following plan edges, ports stripped.
     *
     * <p>Port-blind on purpose: reachability here answers "does this node belong to this DAG",
     * not "was this branch selected". Whether a branch was taken is exactly what the terminal
     * sets record - a branch the engine skipped appears in {@code skippedNodeIds} and is not
     * reported.
     */
    private Set<String> reachableFrom(String triggerId, Map<String, List<String>> outgoing) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(triggerId);
        seen.add(triggerId);
        while (!queue.isEmpty()) {
            for (String next : outgoing.getOrDefault(queue.poll(), List.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }
}
