package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;

import java.util.HashSet;
import java.util.Set;

/**
 * The single answer to "has every node this plan can execute finished?".
 *
 * <p>This existed as three hand-rolled copies that had drifted apart:
 * {@code StateReconstructorHelper.determineOverallStatus} (read side, feeds
 * {@code GET /state}), {@code ExecutionContextManager.checkAndCompleteWorkflow} and the
 * inlined expression in {@code WorkflowResumeService.resumeWorkflow} (both WRITE
 * {@code workflow_runs.status}). All three compared a COUNT of settled nodes against the
 * SIZE of a plan set that enumerated only mcps and triggers, so {@code core:},
 * {@code agent:} and {@code table:} nodes were invisible to the completion test: a plan of
 * one trigger plus one core node was declared finished by its trigger alone. Two of the
 * three also disagreed about whether skipped nodes counted.
 *
 * <p><b>Containment, not cardinality.</b> The two sides of the old comparison came from
 * different universes. The settled sets are the snapshot's flat views
 * ({@code StateSnapshot.computeFlatSet}) and legitimately carry ids the plan set does not,
 * notably {@code interface:}. Under {@code >=} those foreign ids inflate the left-hand side
 * and can satisfy the threshold on their own. Containment is one-directional, so they are
 * simply ignored.
 *
 * <p><b>Skipped counts as settled.</b> Decision, switch, loop-exit and fork plans settle
 * their untaken branch as SKIPPED and never as completed or failed, so omitting it would
 * make the completion test unreachable for that entire family.
 *
 * <p><b>A node awaiting a blocking signal is in none of the three sets</b>, so containment
 * correctly reports "not finished" for a run parked on an approval, a wait or an interface.
 * That is the user-visible half of the defect: such runs used to report COMPLETED.
 *
 * <p>{@code interface:} and {@code note:} nodes are deliberately not required.
 * {@link WorkflowPlan#getAllStepIds()} omits them: interfaces enter the executable graph
 * through edges only, and notes never execute.
 */
public final class RunCompletionPredicate {

    private RunCompletionPredicate() {
    }

    /**
     * @param plan      the run's plan; {@code null} yields {@code false} (cannot conclude)
     * @param completed nodes that finished successfully
     * @param failed    nodes that finished with an error
     * @param skipped   nodes on a branch that was not taken
     * @return {@code true} only when every id in {@code plan.getAllStepIds()} is settled
     */
    public static boolean allPlanNodesSettled(WorkflowPlan plan,
                                              Set<String> completed,
                                              Set<String> failed,
                                              Set<String> skipped) {
        if (plan == null) {
            return false;
        }
        Set<String> settled = new HashSet<>();
        if (completed != null) {
            settled.addAll(completed);
        }
        if (failed != null) {
            settled.addAll(failed);
        }
        if (skipped != null) {
            settled.addAll(skipped);
        }
        return settled.containsAll(plan.getAllStepIds());
    }
}
