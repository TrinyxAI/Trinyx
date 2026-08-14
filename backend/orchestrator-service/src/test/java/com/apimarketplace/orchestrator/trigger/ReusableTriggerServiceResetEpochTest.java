package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: the failure path must close the epoch that was ACTUALLY opened.
 *
 * <p>Found live (CE e2e slot, out-of-credit fire). {@code executeTriggerInternal}
 * increments the epoch AFTER loading the run entity it hands to
 * {@code resetRunOnFailure}, so reading the epoch off that stale copy yields the
 * PREVIOUS number (0 on a first fire). Closing epoch 0 leaves the real epoch (1)
 * in {@code activeEpochs}, so {@code hasAnyActiveEpoch} answers true and the run
 * is parked in RUNNING forever: no schedule/webhook dispatcher picks it up again
 * (they resolve WAITING_TRIGGER), so the workflow never runs again even after the
 * user tops up.
 *
 * <p>Observed before the fix on run_<id>: snapshot
 * {@code currentEpoch=1, activeEpochs=[1]} while the log said
 * "Reset run ... to RUNNING after failure (otherEpochsActive=true)".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - failure reset closes the live epoch")
class ReusableTriggerServiceResetEpochTest {

    private static final String RUN_ID = "run-reset-1";
    private static final String TRIGGER_ID = "trigger:start";
    private static final int STALE_EPOCH = 0;   // what the caller's entity still says
    private static final int LIVE_EPOCH = 1;    // what the DB says after incrementEpoch

    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private UnifiedSignalService unifiedSignalService;

    private ReusableTriggerService service;
    private WorkflowRunEntity staleRun;
    private WorkflowRunEntity freshRun;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository,
                mock(WorkflowRepository.class),
                mock(WorkflowPlanVersionRepository.class),
                epochManager,
                mock(WorkflowStreamingService.class),
                mock(WorkflowExecutionService.class),
                mock(com.apimarketplace.orchestrator.services.TriggerResolverService.class),
                stateSnapshotService,
                epochConcurrencyLimiter,
                mock(com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue.class),
                mock(CreditBudgetService.class));
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);

        staleRun = new WorkflowRunEntity();
        staleRun.setRunIdPublic(RUN_ID);
        staleRun.setStatus(RunStatus.RUNNING);

        freshRun = new WorkflowRunEntity();
        freshRun.setRunIdPublic(RUN_ID);
        freshRun.setStatus(RunStatus.RUNNING);

        lenient().when(epochManager.getCurrentEpoch(staleRun, TRIGGER_ID)).thenReturn(STALE_EPOCH);
        lenient().when(epochManager.getCurrentEpoch(freshRun, TRIGGER_ID)).thenReturn(LIVE_EPOCH);
        lenient().when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(freshRun));
    }

    private void resetOnFailure() {
        ReflectionTestUtils.invokeMethod(service, "resetRunOnFailure", staleRun, RUN_ID, TRIGGER_ID);
    }

    @Test
    @DisplayName("closes the epoch read from the DB, not the stale one on the caller's entity")
    void closesTheLiveEpochNotTheStaleOne() {
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

        resetOnFailure();

        verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, LIVE_EPOCH);
        verify(stateSnapshotService, never()).closeEpoch(RUN_ID, TRIGGER_ID, STALE_EPOCH);
    }

    @Test
    @DisplayName("with no epoch left active the run returns to WAITING_TRIGGER (reusable, revives on top-up)")
    void parksTheRunBackOnWaitingTrigger() {
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

        resetOnFailure();

        ArgumentCaptor<WorkflowRunEntity> saved = ArgumentCaptor.forClass(WorkflowRunEntity.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
    }

    @Test
    @DisplayName("another epoch still running keeps the run RUNNING - the failed one must not park a live run")
    void keepsRunningWhenAnotherEpochIsStillActive() {
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(true);

        resetOnFailure();

        ArgumentCaptor<WorkflowRunEntity> saved = ArgumentCaptor.forClass(WorkflowRunEntity.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("cancels the failed epoch's blocking signals before closing it")
    void cancelsBlockingSignalsForTheLiveEpoch() {
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

        resetOnFailure();

        verify(unifiedSignalService).cancelBlockingByDagAndEpoch(RUN_ID, TRIGGER_ID, LIVE_EPOCH);
        verify(epochConcurrencyLimiter).release(RUN_ID, TRIGGER_ID);
    }

    @Test
    @DisplayName("a terminal run is left alone - the reset must never un-cancel it")
    void terminalRunIsNotOverwritten() {
        freshRun.setStatus(RunStatus.CANCELLED);
        lenient().when(stateSnapshotService.hasAnyActiveEpoch(anyString())).thenReturn(false);

        resetOnFailure();

        verify(runRepository, never()).save(org.mockito.ArgumentMatchers.any(WorkflowRunEntity.class));
    }
}
