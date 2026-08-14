package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The cycle verdict is SHIPPED by the backend on /state, and it participates in the ETag.
 *
 * <p>Why the backend answers instead of the client deriving it: a client only ever sees the
 * CUMULATIVE completed/failed sets, while this verdict is scoped to the epoch that actually
 * closed. A node that failed in epoch 1 and succeeded in epoch 2 is partial (accumulation), yet
 * epoch 2 closed clean. No client-side derivation can tell those two apart, so the canvas badge
 * and the run-history row used to disagree about the same run.
 *
 * <p>Why it must be in the ETag: a reusable-trigger run rests at WAITING_TRIGGER between fires, so
 * a cycle closing with a DIFFERENT outcome can leave seq, status and epoch all unchanged. Left out
 * of the hash, the response 304s and the client keeps rendering the previous cycle's verdict.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - /state ships lastCycleResult")
class WorkflowRunControllerLastCycleResultTest {

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private WorkflowResumeService resumeService;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private WorkflowEpochService workflowEpochService;

    @InjectMocks
    private WorkflowRunController controller;

    private static final String RUN_ID = "run-cycle-result";
    private static final String TENANT_ID = "tenant-A";

    @BeforeEach
    void wireSnapshotAndState() {
        lenient().when(workflowEpochService.listEpochTimestamps(RUN_ID)).thenReturn(List.of());
        lenient().when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(
                StateSnapshot.empty().withDagState("trigger:start", DagState.initial().advanceEpoch(1)));
        lenient().when(resumeService.reconstructStateForApi(RUN_ID)).thenReturn(new WorkflowRunState(
                RUN_ID, "wf-1", RunStatus.WAITING_TRIGGER, ExecutionMode.STEP_BY_STEP,
                Instant.now(), null, Map.of(), List.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Map.of(), List.of()));
    }

    /** Wires the run entity so its metadata carries (or omits) a cycle verdict. */
    private void runWithCycleResult(String cycleResult) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        Map<String, Object> metadata = new HashMap<>();
        if (cycleResult != null) {
            metadata.put("lastCycleResult", cycleResult);
        }
        run.setMetadata(metadata);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    private ResponseEntity<?> callState() {
        return controller.getRunState(RUN_ID, false, TENANT_ID, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Object body(ResponseEntity<?> response, String key) {
        return ((Map<String, Object>) response.getBody()).get(key);
    }

    @Test
    @DisplayName("A closed failed cycle is reported as failed, even though the run rests at WAITING_TRIGGER")
    void failedCycleIsShipped() {
        runWithCycleResult("failed");

        ResponseEntity<?> response = callState();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response, "lastCycleResult")).isEqualTo("failed");
        // The raw status is untouched: execution logic keys off it, only the badge uses the verdict.
        assertThat(body(response, "status")).isEqualTo(RunStatus.WAITING_TRIGGER);
    }

    @Test
    @DisplayName("A closed clean cycle is reported as completed")
    void completedCycleIsShipped() {
        runWithCycleResult("completed");

        assertThat(body(callState(), "lastCycleResult")).isEqualTo("completed");
    }

    @Test
    @DisplayName("A run whose trigger never fired ships NO verdict, so the badge keeps reading waiting")
    void armedButUnfiredRunShipsNothing() {
        // Absent (not "completed"): borrowing an outcome here is what made a launched-but-unfired
        // run read "Completed" before it had run anything at all.
        runWithCycleResult(null);

        ResponseEntity<?> response = callState();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).containsKey("lastCycleResult")).isFalse();
    }

    @Test
    @DisplayName("Two cycles that differ ONLY by outcome get different ETags, so the second is not 304'd away")
    void cycleResultChangesTheEtag() {
        runWithCycleResult("failed");
        String failedEtag = callState().getHeaders().getETag();

        runWithCycleResult("completed");
        String completedEtag = callState().getHeaders().getETag();

        assertThat(failedEtag).isNotNull();
        assertThat(completedEtag)
                .as("seq, status and epoch are identical here - only the verdict moved")
                .isNotEqualTo(failedEtag);
    }

    @Test
    @DisplayName("The run row is read ONCE, so the verdict did not add a query to a polled endpoint")
    void runRowIsReadOnce() {
        // This endpoint is polled continuously. The verdict is served from the row the scope check
        // already loaded; a regression to a second query would be invisible without this.
        runWithCycleResult("completed");

        callState();

        org.mockito.Mockito.verify(workflowRunRepository, org.mockito.Mockito.times(1))
                .findByRunIdPublic(RUN_ID);
    }

    @Test
    @DisplayName("An unchanged cycle still 304s, so adding the verdict did not break the cache")
    void unchangedCycleStill304s() {
        runWithCycleResult("completed");
        String etag = callState().getHeaders().getETag();

        ResponseEntity<?> conditional =
                controller.getRunState(RUN_ID, false, TENANT_ID, null, etag);

        assertThat(conditional.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }
}
