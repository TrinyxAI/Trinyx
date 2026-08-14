package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Drives what happens AFTER a step rerun has reset the DAG.
 *
 * <p>A rerun leaves the target marked READY but executes nothing itself. Who advances from
 * there depends on the run's execution mode: step-by-step waits for the user to click each
 * node, automatic mode runs the wave loop below until the work is done or yields.
 *
 * <p>Extracted from {@code WorkflowRunController}: this is execution policy, not request
 * handling, and the controller could not be unit-tested against it.
 *
 * <p>A rerun executes OUTSIDE a trigger fire, so no fire-path cycle close covers the epoch it
 * re-opened. Whenever this service reaches quiescence it must close the cycle itself, or the
 * epoch stays active forever, the run never re-arms to WAITING_TRIGGER, and the zombie scanner
 * eventually fails it. When it YIELDS instead, the matching resume path
 * ({@code SignalResumeService} for blocking signals, {@code AgentAsyncCompletionService} for
 * async agent offloads) performs that same close once the pending work resolves, so closing
 * here too would double-close.
 */
@Service
public class AutoRestartExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(AutoRestartExecutionService.class);

    /** Safety bound on the wave loop; a ready set that never advances would otherwise spin. */
    static final int MAX_WAVES = 100;

    /** What the post-rerun drive actually did, so the caller can report it instead of guessing. */
    public enum Outcome {
        /** Step-by-step run: the user drives it from here, status set for the UI. */
        STEP_BY_STEP,
        /** Automatic run with nothing executable: the cycle was closed. */
        NOTHING_TO_RUN,
        /** Automatic run that ran to quiescence: the cycle was closed. */
        QUIESCED,
        /** A node yielded (blocking signal or async agent). The resume path owns the close. */
        YIELDED,
        /** The wave cap was hit with work still ready. NOT quiescence: the cycle stays open. */
        WAVE_CAP,
        /**
         * The run vanished, or an AUTOMATIC run had work to do and no executor wired. Nothing
         * was executed and the cycle was NOT closed. A step-by-step run never lands here: its
         * status is settled before the executor is consulted.
         */
        UNAVAILABLE,
    }

    private final WorkflowRunRepository runRepository;
    private final StepRerunService stepRerunService;

    @Autowired(required = false)
    private V2StepByStepService v2StepByStepService;

    @Autowired(required = false)
    private V2StepByStepScheduler v2StepByStepScheduler;

    public AutoRestartExecutionService(WorkflowRunRepository runRepository,
                                       StepRerunService stepRerunService) {
        this.runRepository = runRepository;
        this.stepRerunService = stepRerunService;
    }

    /**
     * Advance a run that a rerun just reset.
     *
     * @param readySteps     the ready set the rerun produced (may be empty)
     * @param ownerTriggerId the DAG the rerun target belongs to
     * @param rerunEpoch     the epoch the rerun re-opened, needed to close the right cycle
     */
    public Outcome resumeAfterRerun(String runId, Set<String> readySteps,
                                    String ownerTriggerId, int rerunEpoch) {
        WorkflowRunEntity run = runRepository.findByRunIdPublic(runId).orElse(null);
        if (run == null) {
            logger.warn("[Rerun] Run not found for post-rerun execution: runId={}", runId);
            return Outcome.UNAVAILABLE;
        }

        // Deliberately BEFORE the executor check, unlike the controller version this replaces:
        // a step-by-step run needs its status settled (rerunFromStep left it RUNNING) whether or
        // not automatic execution happens to be wired, and it never uses the executor anyway.
        if (run.getExecutionMode() == ExecutionMode.STEP_BY_STEP) {
            applyStepByStepStatus(run, readySteps);
            return Outcome.STEP_BY_STEP;
        }

        // Triggers are fired explicitly, never auto-executed.
        Set<String> currentReady = withoutTriggers(readySteps);
        if (currentReady.isEmpty()) {
            // Checked before the executor: with nothing to execute the executor is irrelevant,
            // and the reopened epoch still has to be closed or the run never re-arms.
            logger.info("[Rerun] Nothing executable after the rerun for runId={}", runId);
            stepRerunService.closeCycleAfterAutoExecution(runId, ownerTriggerId, rerunEpoch);
            return Outcome.NOTHING_TO_RUN;
        }

        if (v2StepByStepService == null) {
            logger.warn("[Rerun] Step execution not wired, skipping post-rerun execution for runId={}", runId);
            return Outcome.UNAVAILABLE;
        }

        logger.info("[Rerun] AUTO mode: driving {} ready steps after the rerun for runId={}",
                currentReady.size(), runId);

        int wave = 0;
        boolean yielded = false;
        while (!currentReady.isEmpty() && wave < MAX_WAVES && !yielded) {
            wave++;
            Set<String> nextReady = new HashSet<>();
            for (String stepId : currentReady) {
                if (executeStep(runId, stepId, nextReady, ownerTriggerId, rerunEpoch)) {
                    yielded = true;
                    break;
                }
            }
            currentReady = nextReady;
            if (currentReady.isEmpty()) {
                currentReady = pendingSplitWork(runId);
            }
        }

        if (yielded) {
            logger.info("[Rerun] Post-rerun execution yielded for runId={} after {} wave(s)", runId, wave);
            return Outcome.YIELDED;
        }
        if (!currentReady.isEmpty()) {
            // Truncation is not quiescence: leave the run visibly RUNNING rather than mask it
            // with a clean cycle end.
            //
            // ERROR, with the ready set that never advanced, and deliberately so. This cap is
            // a RUNAWAY detector: reaching it means the same nodes were dispatched 100 times
            // without the ready set shrinking, and each of those dispatches still emits a
            // completion that per-node execution counts accumulate. That is the whole of
            // "expected 2 but was 101" - 1 + MAX_WAVES.
            //
            // It used to log a bare WARN naming only the run, which is why the defect survived
            // months as "suite flakiness": the sole visible trace was an odd number in a
            // 124-test log, with nothing saying WHICH node was spinning or in which epoch.
            // Whatever finally explains the remaining occurrences, the next one has to be
            // diagnosable from a single run - hence the node ids, the trigger and the epoch.
            logger.error("[Rerun] RUNAWAY: hit the {}-wave cap with work still ready - "
                    + "the ready set never advanced, so these nodes were re-dispatched every wave "
                    + "and each dispatch still counted as an execution. runId={}, triggerId={}, "
                    + "epoch={}, stillReady={}",
                MAX_WAVES, runId, ownerTriggerId, rerunEpoch, currentReady);
            return Outcome.WAVE_CAP;
        }

        logger.info("[Rerun] Post-rerun execution reached quiescence for runId={} in {} wave(s)", runId, wave);
        stepRerunService.closeCycleAfterAutoExecution(runId, ownerTriggerId, rerunEpoch);
        return Outcome.QUIESCED;
    }

    /**
     * Single-epoch step-by-step policy: PAUSED while non-trigger nodes are ready (the epoch is
     * mid-flight), WAITING_TRIGGER only when nothing but triggers remain (the epoch is done).
     * Needed because {@code rerunFromStep} sets RUNNING unconditionally.
     */
    private void applyStepByStepStatus(WorkflowRunEntity run, Set<String> readySteps) {
        boolean hasNonTriggerReady = readySteps.stream().anyMatch(s -> !s.startsWith("trigger:"));
        boolean hasTriggerReady = readySteps.stream().anyMatch(s -> s.startsWith("trigger:"));
        RunStatus status = (!hasNonTriggerReady && hasTriggerReady)
                ? RunStatus.WAITING_TRIGGER
                : RunStatus.PAUSED;
        logger.info("[Rerun] Step-by-step run, setting status to {} for runId={}", status, run.getRunIdPublic());
        run.setStatus(status);
        runRepository.save(run);
    }

    /**
     * Execute one node, collecting whatever it makes ready.
     *
     * @return {@code true} when the node yielded and the loop must stop
     */
    private boolean executeStep(String runId, String stepId, Set<String> nextReady,
                               String ownerTriggerId, int rerunEpoch) {
        try {
            if (v2StepByStepScheduler != null) {
                Set<String> pendingItemIds = v2StepByStepScheduler.getPendingItemIdsForNode(runId, stepId);
                if (!pendingItemIds.isEmpty()) {
                    logger.info("[Rerun] Executing {} pending split item(s) for step {}", pendingItemIds.size(), stepId);
                    v2StepByStepService.executeSplitItems(runId, stepId, pendingItemIds);
                    return false;
                }
            }

            // The rerun's epoch and DAG, not an implicit re-resolution. The 3-arg overload
            // sends explicitEpoch=-1 / explicitTriggerId=null, and that had two effects: it
            // disabled the cluster-wide at-most-once guard (whose precondition is
            // explicitEpoch >= 0 && explicitTriggerId != null - this was the only auto-mode
            // driver running without it), and it let mergeReadyNodesAfterExecution remove the
            // executed node from an epoch resolved out of metadata.dagLastEpoch rather than
            // the one the rerun wrote its READY marker at. When those disagree the marker
            // survives, this loop is fed from the flat ready view (it does not recompute
            // readiness), and the node is replayed to MAX_WAVES - the "expected 2 but was 101".
            StepByStepExecutionResult result =
                v2StepByStepService.executeNode(runId, stepId, "0", rerunEpoch, ownerTriggerId);

            // ANY non-terminal yield stops the loop, not just blocking signals: gating on
            // isAwaitingSignal() alone used to miss async-running agent offloads and re-fire
            // the same node on every wave.
            if (result.isPending()) {
                logger.info("[Rerun] Node {} yielded, pausing post-rerun execution for runId={}", stepId, runId);
                return true;
            }
            if (result.isFailed()) {
                logger.warn("[Rerun] Step {} failed during post-rerun execution: {}", stepId, result.getErrorMessage());
            }
            if (result.readyNodes() != null) {
                nextReady.addAll(withoutTriggers(result.readyNodes()));
            }
        } catch (Exception e) {
            // One failing node must not abandon its siblings; the run's own node states record it.
            logger.error("[Rerun] Error executing step {} after the rerun: {}", stepId, e.getMessage(), e);
        }
        return false;
    }

    /** Split items scheduled but not yet dispatched, which keep the wave loop going. */
    private Set<String> pendingSplitWork(String runId) {
        if (v2StepByStepScheduler == null) {
            return Set.of();
        }
        return withoutTriggers(v2StepByStepScheduler.getPendingNodeIds(runId));
    }

    private static Set<String> withoutTriggers(Set<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> filtered = new HashSet<>();
        for (String id : nodeIds) {
            if (id != null && !id.startsWith("trigger:")) {
                filtered.add(id);
            }
        }
        return filtered;
    }
}
