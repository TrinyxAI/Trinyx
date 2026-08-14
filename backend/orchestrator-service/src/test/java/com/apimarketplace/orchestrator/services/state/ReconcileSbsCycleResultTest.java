package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a step-by-step run reports once it comes to rest, end to end through the real reconcile.
 *
 * <p>Three rules are pinned here, and the third is the one that is easy to break while fixing the
 * other two: a run that was LAUNCHED but whose trigger has never fired must keep reading
 * "waiting for trigger". Recording an outcome for it would badge a run that has done nothing as
 * a success.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("reconcileSbsRunStatus - the outcome a resting run reports")
class ReconcileSbsCycleResultTest {

    private static final String RUN_ID = "run-cycle-1";
    private static final String TRIGGER = "trigger:start";

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher eventPublisher;
    @Mock private com.apimarketplace.common.storage.service.StorageBreakdownService breakdownService;

    private StateSnapshotService service;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        var meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        service = new StateSnapshotService(runRepository, mapper, workflowEpochService, eventPublisher,
                breakdownService, new TxScopedSnapshotCache(runRepository, meterRegistry),
                new com.apimarketplace.orchestrator.metrics.WorkflowMetrics(meterRegistry),
                org.mockito.Mockito.mock(ClaimRefusalRegistry.class));
    }

    /** A run row whose snapshot is the given one, ready for reconcile to read and write back. */
    private WorkflowRunEntity runWith(StateSnapshot snapshot) throws Exception {
        String json = mapper.writeValueAsString(snapshot);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findStateSnapshotByRunIdPublic(RUN_ID)).thenReturn(Optional.of(json));
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.updateSnapshotAndSeq(anyString(), anyString())).thenReturn(1);
        return run;
    }

    private Object savedCycleResult() {
        ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
        verify(runRepository).save(captor.capture());
        Map<String, Object> meta = captor.getValue().getMetadata();
        return meta == null ? null : meta.get("lastCycleResult");
    }

    @Test
    @DisplayName("A cycle where some nodes succeeded and one failed reports FAILED, not partial")
    void mixedCycleReportsFailed() throws Exception {
        runWith(StateSnapshot.empty()
                .addReadyNode(TRIGGER, TRIGGER)
                .markNodeCompleted(TRIGGER, TRIGGER)
                .markNodeCompleted(TRIGGER, "mcp:ok")
                .markNodeFailed(TRIGGER, "mcp:broken"));

        service.reconcileSbsRunStatus(RUN_ID);

        assertThat(savedCycleResult())
                .as("a run is not a collection of items; any failure makes the cycle failed")
                .isEqualTo("failed");
    }

    @Test
    @DisplayName("A clean cycle reports COMPLETED")
    void cleanCycleReportsCompleted() throws Exception {
        runWith(StateSnapshot.empty()
                .addReadyNode(TRIGGER, TRIGGER)
                .markNodeCompleted(TRIGGER, TRIGGER)
                .markNodeCompleted(TRIGGER, "mcp:ok"));

        service.reconcileSbsRunStatus(RUN_ID);

        assertThat(savedCycleResult()).isEqualTo("completed");
    }

    @Test
    @DisplayName("A cycle whose whole downstream was skipped still reports COMPLETED")
    void skippedOnlyCycleStillReportsAnOutcome() throws Exception {
        // The trigger fired and the cycle reached its end, so it owes an outcome even though no
        // node did any work (an exit branch, an unrouted split). Left out, the answer depended on
        // which mode drove the run: the automatic path records "completed" for this same cycle,
        // while step-by-step recorded nothing and left the previous badge standing.
        runWith(StateSnapshot.empty()
                .addReadyNode(TRIGGER, TRIGGER)
                .markNodeCompleted(TRIGGER, TRIGGER)
                .markNodeSkipped(TRIGGER, "mcp:never_ran"));

        service.reconcileSbsRunStatus(RUN_ID);

        assertThat(savedCycleResult())
                .as("a fired cycle reports an outcome even when everything downstream was skipped")
                .isEqualTo("completed");
    }

    @Test
    @DisplayName("A launched run whose trigger never fired stays WAITING_TRIGGER with no outcome")
    void armedButUnfiredRunReportsNoOutcome() throws Exception {
        // Nothing has executed: only the trigger is ready, waiting to be fired. Recording an
        // outcome here would badge a run that has done nothing as COMPLETED.
        WorkflowRunEntity run = runWith(StateSnapshot.empty().addReadyNode(TRIGGER, TRIGGER));

        service.reconcileSbsRunStatus(RUN_ID);

        assertThat(run.getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        assertThat(run.getMetadata() == null ? null : run.getMetadata().get("lastCycleResult"))
                .as("no outcome until the trigger actually fires")
                .isNull();
    }

    @Test
    @DisplayName("A run with a step still pending stays PAUSED and reports nothing")
    void midRunReportsNoOutcome() throws Exception {
        WorkflowRunEntity run = runWith(StateSnapshot.empty()
                .markNodeCompleted(TRIGGER, TRIGGER)
                .addReadyNode(TRIGGER, "mcp:next"));

        service.reconcileSbsRunStatus(RUN_ID);

        assertThat(run.getStatus())
                .as("a step is still runnable, so the run is mid-flight")
                .isEqualTo(RunStatus.PAUSED);
        assertThat(run.getMetadata() == null ? null : run.getMetadata().get("lastCycleResult")).isNull();
    }

    @Test
    @DisplayName("An unchanged run is not written back")
    void noWriteWhenNothingChanged() throws Exception {
        WorkflowRunEntity run = runWith(StateSnapshot.empty().addReadyNode(TRIGGER, TRIGGER));
        run.setStatus(RunStatus.WAITING_TRIGGER);

        service.reconcileSbsRunStatus(RUN_ID);

        verify(runRepository, never()).save(run);
    }
}
