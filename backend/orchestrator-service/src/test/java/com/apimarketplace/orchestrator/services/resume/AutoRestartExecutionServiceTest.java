package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens after a rerun has reset the DAG.
 *
 * <p>This logic used to live in {@code WorkflowRunController} and could not be unit-tested
 * there, which is why its cycle-close rules (the ones that decide whether a run ever re-arms)
 * had no coverage at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AutoRestartExecutionService")
class AutoRestartExecutionServiceTest {

    private static final String RUN_ID = "run-1";
    private static final String TRIGGER = "trigger:start";
    private static final int EPOCH = 4;

    @Mock private WorkflowRunRepository runRepository;
    @Mock private StepRerunService stepRerunService;
    @Mock private V2StepByStepService stepExecutor;
    @Mock private V2StepByStepScheduler scheduler;

    private AutoRestartExecutionService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AutoRestartExecutionService(runRepository, stepRerunService);
        setField("v2StepByStepService", stepExecutor);
        setField("v2StepByStepScheduler", scheduler);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AutoRestartExecutionService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private WorkflowRunEntity run(ExecutionMode mode) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setExecutionMode(mode);
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        return run;
    }

    /** A node that finished and made {@code next} ready. */
    private StepByStepExecutionResult completedMaking(String... next) {
        StepByStepExecutionResult result = mock(StepByStepExecutionResult.class);
        lenient().when(result.isPending()).thenReturn(false);
        lenient().when(result.isFailed()).thenReturn(false);
        lenient().when(result.readyNodes()).thenReturn(Set.of(next));
        return result;
    }

    private StepByStepExecutionResult pending() {
        StepByStepExecutionResult result = mock(StepByStepExecutionResult.class);
        lenient().when(result.isPending()).thenReturn(true);
        return result;
    }

    private void noPendingSplitWork() {
        lenient().when(scheduler.getPendingItemIdsForNode(anyString(), anyString())).thenReturn(Set.of());
        lenient().when(scheduler.getPendingNodeIds(anyString())).thenReturn(Set.of());
    }

    @Nested
    @DisplayName("Automatic mode")
    class AutomaticMode {

        @Test
        @DisplayName("Drives the wave chain to quiescence and closes the cycle")
        void drivesToQuiescenceAndClosesTheCycle() {
            run(ExecutionMode.AUTOMATIC);
            noPendingSplitWork();
            // Built before the when(...) call: creating a mock inside a stubbing argument
            // is what Mockito reports as UnfinishedStubbing.
            StepByStepExecutionResult first = completedMaking("mcp:b");
            StepByStepExecutionResult second = completedMaking();
            when(stepExecutor.executeNode(RUN_ID, "mcp:a", "0", EPOCH, TRIGGER)).thenReturn(first);
            when(stepExecutor.executeNode(RUN_ID, "mcp:b", "0", EPOCH, TRIGGER)).thenReturn(second);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.QUIESCED, outcome);
            verify(stepExecutor).executeNode(RUN_ID, "mcp:b", "0", EPOCH, TRIGGER);
            // A rerun runs outside a trigger fire, so nothing else closes the epoch it reopened.
            verify(stepRerunService).closeCycleAfterAutoExecution(RUN_ID, TRIGGER, EPOCH);
        }

        @Test
        @DisplayName("Never auto-executes a trigger, and closes the cycle when only triggers are ready")
        void neverExecutesTriggers() {
            run(ExecutionMode.AUTOMATIC);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of(TRIGGER), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.NOTHING_TO_RUN, outcome);
            verify(stepExecutor, never()).executeNode(anyString(), anyString(), anyString(), anyInt(), anyString());
            // Without this close the reopened epoch stays active and the run never re-arms.
            verify(stepRerunService).closeCycleAfterAutoExecution(RUN_ID, TRIGGER, EPOCH);
        }

        @Test
        @DisplayName("Leaves the cycle open when a node yields: the resume path owns the close")
        void doesNotCloseTheCycleOnYield() {
            run(ExecutionMode.AUTOMATIC);
            noPendingSplitWork();
            StepByStepExecutionResult yielding = pending();
            when(stepExecutor.executeNode(RUN_ID, "mcp:a", "0", EPOCH, TRIGGER)).thenReturn(yielding);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.YIELDED, outcome);
            // Closing here too would double-close under SignalResumeService.
            verify(stepRerunService, never()).closeCycleAfterAutoExecution(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Truncation at the wave cap is not quiescence: the cycle stays open")
        void waveCapDoesNotCloseTheCycle() {
            run(ExecutionMode.AUTOMATIC);
            noPendingSplitWork();
            // A ready set that never drains: the node re-readies itself.
            StepByStepExecutionResult selfReadying = completedMaking("mcp:a");
            when(stepExecutor.executeNode(RUN_ID, "mcp:a", "0", EPOCH, TRIGGER)).thenReturn(selfReadying);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.WAVE_CAP, outcome);
            verify(stepExecutor, times(AutoRestartExecutionService.MAX_WAVES))
                    .executeNode(RUN_ID, "mcp:a", "0", EPOCH, TRIGGER);
            // Masking a truncation with a clean cycle end would report work that never ran.
            verify(stepRerunService, never()).closeCycleAfterAutoExecution(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Quiescence landing exactly on the last allowed wave still closes the cycle")
        void quiescenceOnTheLastWaveStillCloses() {
            // The old controller comment called this case out explicitly ("true even when
            // quiescence lands exactly on the last allowed wave") and nothing pinned it: an
            // off-by-one turning it into WAVE_CAP would leave the epoch open and the run would
            // never re-arm - the exact failure this whole branch exists to fix.
            run(ExecutionMode.AUTOMATIC);
            noPendingSplitWork();
            AtomicInteger wave = new AtomicInteger();
            when(stepExecutor.executeNode(eq(RUN_ID), anyString(), eq("0"), anyInt(), anyString())).thenAnswer(inv -> {
                // Chain one node per wave, and drain on the very last allowed one.
                int n = wave.incrementAndGet();
                return n < AutoRestartExecutionService.MAX_WAVES
                        ? completedMaking("mcp:step" + n)
                        : completedMaking();
            });

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:step0"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.QUIESCED, outcome);
            assertEquals(AutoRestartExecutionService.MAX_WAVES, wave.get());
            verify(stepRerunService).closeCycleAfterAutoExecution(RUN_ID, TRIGGER, EPOCH);
        }

        @Test
        @DisplayName("A failing node does not abandon the siblings queued after it")
        void oneFailingNodeDoesNotStopTheWave() {
            run(ExecutionMode.AUTOMATIC);
            noPendingSplitWork();
            // Set.of iteration order is JVM-salted, so a single wave of two would prove nothing
            // when the healthy node happens to run first. Chaining makes "boom" strictly first.
            StepByStepExecutionResult reachesBoom = completedMaking("mcp:boom");
            StepByStepExecutionResult ok = completedMaking();
            when(stepExecutor.executeNode(RUN_ID, "mcp:head", "0", EPOCH, TRIGGER)).thenReturn(reachesBoom);
            when(stepExecutor.executeNode(RUN_ID, "mcp:boom", "0", EPOCH, TRIGGER)).thenThrow(new RuntimeException("boom"));
            when(stepExecutor.executeNode(RUN_ID, "mcp:ok", "0", EPOCH, TRIGGER)).thenReturn(ok);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:head", "mcp:ok"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.QUIESCED, outcome);
            verify(stepExecutor).executeNode(RUN_ID, "mcp:boom", "0", EPOCH, TRIGGER);
            verify(stepExecutor).executeNode(RUN_ID, "mcp:ok", "0", EPOCH, TRIGGER);
            // The wave still settled, so the reopened epoch is still closed.
            verify(stepRerunService).closeCycleAfterAutoExecution(RUN_ID, TRIGGER, EPOCH);
        }

        @Test
        @DisplayName("Dispatches pending split items instead of re-executing the split node")
        void dispatchesPendingSplitItems() {
            run(ExecutionMode.AUTOMATIC);
            when(scheduler.getPendingItemIdsForNode(RUN_ID, "core:split")).thenReturn(Set.of("item-1", "item-2"));
            lenient().when(scheduler.getPendingNodeIds(RUN_ID)).thenReturn(Set.of());

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("core:split"), TRIGGER, EPOCH);

            verify(stepExecutor).executeSplitItems(RUN_ID, "core:split", Set.of("item-1", "item-2"));
            verify(stepExecutor, never()).executeNode(RUN_ID, "core:split", "0", EPOCH, TRIGGER);
            // The split path must still reach quiescence and close the cycle: a regression that
            // turned it into WAVE_CAP would leave the epoch open and the run would never re-arm,
            // which is the very failure this branch exists to fix.
            assertEquals(AutoRestartExecutionService.Outcome.QUIESCED, outcome);
            verify(stepRerunService).closeCycleAfterAutoExecution(RUN_ID, TRIGGER, EPOCH);
        }

        @Test
        @DisplayName("A node that FAILS is not a yield: the wave continues and the cycle closes")
        void aFailedNodeStillLetsTheWaveSettle() {
            run(ExecutionMode.AUTOMATIC);
            noPendingSplitWork();
            StepByStepExecutionResult failed = mock(StepByStepExecutionResult.class);
            lenient().when(failed.isPending()).thenReturn(false);
            lenient().when(failed.isFailed()).thenReturn(true);
            lenient().when(failed.getErrorMessage()).thenReturn("nope");
            lenient().when(failed.readyNodes()).thenReturn(Set.of());
            when(stepExecutor.executeNode(RUN_ID, "mcp:a", "0", EPOCH, TRIGGER)).thenReturn(failed);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.QUIESCED, outcome);
            verify(stepRerunService).closeCycleAfterAutoExecution(RUN_ID, TRIGGER, EPOCH);
        }

        @Test
        @DisplayName("Picks up split work scheduled after the wave drained")
        void picksUpSplitWorkAfterTheWaveDrains() {
            run(ExecutionMode.AUTOMATIC);
            lenient().when(scheduler.getPendingItemIdsForNode(anyString(), anyString())).thenReturn(Set.of());
            StepByStepExecutionResult drained = completedMaking();
            StepByStepExecutionResult lateDone = completedMaking();
            when(stepExecutor.executeNode(RUN_ID, "mcp:a", "0", EPOCH, TRIGGER)).thenReturn(drained);
            when(stepExecutor.executeNode(RUN_ID, "mcp:late", "0", EPOCH, TRIGGER)).thenReturn(lateDone);
            when(scheduler.getPendingNodeIds(RUN_ID))
                    .thenReturn(Set.of("mcp:late", TRIGGER))
                    .thenReturn(Set.of());

            service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            verify(stepExecutor).executeNode(RUN_ID, "mcp:late", "0", EPOCH, TRIGGER);
            verify(stepExecutor, never()).executeNode(RUN_ID, TRIGGER, "0");
        }
    }

    @Nested
    @DisplayName("Step-by-step mode")
    class StepByStepMode {

        @Test
        @DisplayName("Executes nothing: the user drives each node")
        void executesNothing() {
            run(ExecutionMode.STEP_BY_STEP);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.STEP_BY_STEP, outcome);
            verify(stepExecutor, never()).executeNode(anyString(), anyString(), anyString(), anyInt(), anyString());
            verify(stepRerunService, never()).closeCycleAfterAutoExecution(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("PAUSED while a non-trigger node is ready: rerunFromStep left the run RUNNING")
        void pausesWhileWorkIsReady() {
            run(ExecutionMode.STEP_BY_STEP);

            service.resumeAfterRerun(RUN_ID, Set.of("mcp:a", TRIGGER), TRIGGER, EPOCH);

            assertEquals(RunStatus.PAUSED, savedStatus());
        }

        @Test
        @DisplayName("WAITING_TRIGGER when only the trigger is ready: the epoch is done")
        void armsWhenOnlyTheTriggerIsReady() {
            run(ExecutionMode.STEP_BY_STEP);

            service.resumeAfterRerun(RUN_ID, Set.of(TRIGGER), TRIGGER, EPOCH);

            assertEquals(RunStatus.WAITING_TRIGGER, savedStatus());
        }

        @Test
        @DisplayName("PAUSED when nothing is ready at all")
        void pausesWhenNothingIsReady() {
            run(ExecutionMode.STEP_BY_STEP);

            service.resumeAfterRerun(RUN_ID, Set.of(), TRIGGER, EPOCH);

            assertEquals(RunStatus.PAUSED, savedStatus());
        }

        private RunStatus savedStatus() {
            ArgumentCaptor<WorkflowRunEntity> saved = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(1)).save(saved.capture());
            return saved.getValue().getStatus();
        }
    }

    @Nested
    @DisplayName("Unavailable")
    class Unavailable {

        @Test
        @DisplayName("Reports UNAVAILABLE and touches nothing when the run is gone")
        void runMissing() {
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.UNAVAILABLE, outcome);
            verify(stepRerunService, never()).closeCycleAfterAutoExecution(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Reports UNAVAILABLE when step execution is not wired")
        void executorMissing() throws Exception {
            setField("v2StepByStepService", null);
            run(ExecutionMode.AUTOMATIC);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.UNAVAILABLE, outcome);
            // No close: work was ready and none of it ran, so reporting a clean cycle end would
            // claim the epoch settled when it did not.
            verify(stepRerunService, never()).closeCycleAfterAutoExecution(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("With nothing to run, a missing executor still does not strand the epoch")
        void closesTheCycleWithNothingToRunEvenWithoutAnExecutor() throws Exception {
            // The executor is irrelevant when there is nothing to execute. Checking it first
            // would leave the epoch the rerun reopened active forever, and the run would sit
            // RUNNING until the zombie scanner failed it.
            setField("v2StepByStepService", null);
            run(ExecutionMode.AUTOMATIC);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of(TRIGGER), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.NOTHING_TO_RUN, outcome);
            verify(stepRerunService).closeCycleAfterAutoExecution(RUN_ID, TRIGGER, EPOCH);
        }

        @Test
        @DisplayName("A step-by-step run settles its status even with no executor wired")
        void stepByStepStatusIsAppliedWithoutAnExecutor() throws Exception {
            setField("v2StepByStepService", null);
            WorkflowRunEntity r = run(ExecutionMode.STEP_BY_STEP);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.STEP_BY_STEP, outcome);
            // rerunFromStep leaves the run RUNNING; without this it would stay that way.
            assertEquals(RunStatus.PAUSED, r.getStatus());
        }

        @Test
        @DisplayName("Runs without a scheduler wired")
        void schedulerMissing() throws Exception {
            setField("v2StepByStepScheduler", null);
            run(ExecutionMode.AUTOMATIC);
            StepByStepExecutionResult done = completedMaking();
            when(stepExecutor.executeNode(RUN_ID, "mcp:a", "0", EPOCH, TRIGGER)).thenReturn(done);

            AutoRestartExecutionService.Outcome outcome =
                    service.resumeAfterRerun(RUN_ID, Set.of("mcp:a"), TRIGGER, EPOCH);

            assertEquals(AutoRestartExecutionService.Outcome.QUIESCED, outcome);
        }
    }
}
