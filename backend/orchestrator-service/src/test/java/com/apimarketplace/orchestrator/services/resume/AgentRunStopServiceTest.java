package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentRunStopService} - the agent-initiated stop of a run.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRunStopService - agent-initiated run stop")
class AgentRunStopServiceTest {

    private static final String RUN_ID = "run-stop-1";

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowResumeService resumeService;

    /** The metadata the service handed to the resume service, captured as it is sent. */
    private final Map<String, Object> sentMetadata = new java.util.HashMap<>();

    private AgentRunStopService service;

    /**
     * The capture is installed ONCE, before each test's own stubbing: a test that makes the
     * resume service throw must be able to override it, and Mockito lets the last stubbing
     * win. Installing it inside a service() factory called during the act phase would
     * silently cancel those doThrow stubs.
     */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().doAnswer(captureMetadata())
                .when(resumeService).cancelWorkflow(any(), any());
        org.mockito.Mockito.lenient().doAnswer(captureMetadata())
                .when(resumeService).stopWorkflow(any(), any());
        service = new AgentRunStopService(runRepository, resumeService);
    }

    private AgentRunStopService service() {
        return service;
    }

    private org.mockito.stubbing.Answer<Void> captureMetadata() {
        return invocation -> {
            Map<String, Object> sent = invocation.getArgument(1);
            sentMetadata.clear();
            if (sent != null) sent.forEach(sentMetadata::put);
            return null;
        };
    }

    private WorkflowRunEntity run(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(status);
        return run;
    }

    /**
     * The run as the locked write leaves it: carrying the very stamp this stop generated.
     * The service uses that stamp to tell a stop that landed from one the engine skipped,
     * so a happy-path fixture MUST reproduce it.
     */
    private Optional<WorkflowRunEntity> runAfterStopLanded(RunStatus status) {
        WorkflowRunEntity stopped = run(status);
        stopped.setMetadata(new java.util.HashMap<>(sentMetadata));
        return Optional.of(stopped);
    }

    @Test
    @DisplayName("cancel mode hard-cancels the run and reports the status transition")
    void cancelModeCancelsRun() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.CANCELLED));

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, "login wall", "agent");

        verify(resumeService).cancelWorkflow(eq(RUN_ID), any());
        verify(resumeService, never()).stopWorkflow(any(), any());
        assertThat(outcome.previousStatus()).isEqualTo("RUNNING");
        assertThat(outcome.status()).isEqualTo("CANCELLED");
        assertThat(outcome.reason()).isEqualTo("login wall");
        assertThat(outcome.stoppedBy()).isEqualTo("agent");
        assertThat(outcome.alreadyTerminal()).isFalse();
    }

    @Test
    @DisplayName("graceful mode closes the epoch instead of hard-cancelling")
    void gracefulModeStopsRun() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.WAITING_TRIGGER));

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.GRACEFUL, null, "agent");

        verify(resumeService).stopWorkflow(eq(RUN_ID), any());
        verify(resumeService, never()).cancelWorkflow(any(), any());
        assertThat(outcome.status()).isEqualTo("WAITING_TRIGGER");
        assertThat(outcome.mode()).isEqualTo(AgentRunStopService.Mode.GRACEFUL);
    }

    /**
     * The reason must travel INSIDE the stop, not as a separate save: the entity read
     * here is detached, so saving it would write back every column of the row (including
     * a stale state_snapshot). The stop already holds the run under a pessimistic lock.
     */
    @Test
    @DisplayName("the reason travels with the stop itself - never as a separate write on a detached run")
    void reasonTravelsWithTheStop() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.CANCELLED));

        service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, "budget blown", "agent");

        verify(runRepository, never()).save(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(resumeService).cancelWorkflow(eq(RUN_ID), metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry(AgentRunStopService.META_STOP_REASON, "budget blown")
                .containsEntry(AgentRunStopService.META_STOPPED_BY, "agent")
                .containsKey(AgentRunStopService.META_STOPPED_AT);
    }

    @Test
    @DisplayName("the graceful stop carries the same cause metadata as the hard cancel")
    void gracefulStopCarriesTheSameMetadata() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.WAITING_TRIGGER));

        service().stop(RUN_ID, AgentRunStopService.Mode.GRACEFUL, "pausing until tomorrow", "agent");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(resumeService).stopWorkflow(eq(RUN_ID), metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry(AgentRunStopService.META_STOP_REASON, "pausing until tomorrow");
    }

    @Test
    @DisplayName("a blank reason asks for the reason to be removed, never stored as an empty string")
    void blankReasonIsNotRecorded() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.CANCELLED));

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, "   ", "agent");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(resumeService).cancelWorkflow(eq(RUN_ID), metadata.capture());
        assertThat(metadata.getValue().get(AgentRunStopService.META_STOP_REASON)).isNull();
        assertThat(outcome.reason()).isNull();
    }

    @Test
    @DisplayName("an oversized reason is capped so one agent cannot bloat the run metadata")
    void oversizedReasonIsCapped() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.CANCELLED));
        String huge = "x".repeat(AgentRunStopService.MAX_REASON_LENGTH + 250);

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, huge, "agent");

        assertThat(outcome.reason()).hasSize(AgentRunStopService.MAX_REASON_LENGTH);
    }

    @Test
    @DisplayName("a run that already ended is left untouched and reported as already_terminal")
    void alreadyTerminalRunIsNotStopped() {
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(RunStatus.COMPLETED)));

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, "too late", "agent");

        assertThat(outcome.alreadyTerminal()).isTrue();
        assertThat(outcome.status()).isEqualTo("COMPLETED");
        assertThat(outcome.previousStatus()).isEqualTo("COMPLETED");
        verifyNoInteractions(resumeService);
        verify(runRepository, never()).save(any());
    }

    /**
     * The status is read outside the lock, so a run an agent is racing to stop very often
     * ends on its own in that window. The engine then refuses a terminal run - which is not
     * an error for the caller: there was nothing left to stop.
     */
    @Test
    @DisplayName("a run that ends on its own mid-stop reports already_terminal, not a failure")
    void runThatEndsDuringTheStopIsReportedAsAlreadyTerminal() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenReturn(Optional.of(run(RunStatus.COMPLETED)));
        doThrow(new IllegalStateException("Cannot cancel workflow in status: COMPLETED."))
                .when(resumeService).cancelWorkflow(eq(RUN_ID), any());

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, "too late", "agent");

        assertThat(outcome.alreadyTerminal()).isTrue();
        assertThat(outcome.status()).isEqualTo("COMPLETED");
    }

    /**
     * The graceful stop does NOT throw on a terminal run: it cleans up and returns. Without
     * an explicit check the caller would be told "the run is back to COMPLETED, its next
     * trigger fire still works", which is a lie about a run that ended.
     */
    @Test
    @DisplayName("a graceful stop on a run that ended silently reports already_terminal, not success")
    void gracefulStopOnATerminalRunIsNotReportedAsSuccess() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenReturn(Optional.of(run(RunStatus.COMPLETED)));

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.GRACEFUL, "too late", "agent");

        assertThat(outcome.alreadyTerminal()).isTrue();
        assertThat(outcome.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a 'nothing to stop' outcome claims no cause, because none was recorded")
    void nothingToStopReportsNoCause() {
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(RunStatus.COMPLETED)));

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, "a reason nobody stored", "agent");

        assertThat(outcome.alreadyTerminal()).isTrue();
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.stoppedBy()).isNull();
    }

    /**
     * The cancel twin of the silent race: cancelWorkflow skips an already-CANCELLED run
     * without throwing and without merging. Reporting "stopped, reason: your sentence"
     * would then contradict a row carrying somebody else's cause.
     */
    @Test
    @DisplayName("a cancel that the engine skipped reports already_terminal, not a stop it did not do")
    void cancelThatDidNotLandIsNotReportedAsSuccess() {
        WorkflowRunEntity someoneElseStoppedIt = run(RunStatus.CANCELLED);
        someoneElseStoppedIt.setMetadata(new java.util.HashMap<>(Map.of(
                AgentRunStopService.META_STOPPED_BY, "user",
                AgentRunStopService.META_STOPPED_AT, "2026-07-30T10:00:00Z")));
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenReturn(Optional.of(someoneElseStoppedIt));

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, "my sentence", "agent");

        assertThat(outcome.alreadyTerminal()).isTrue();
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.stoppedBy()).isNull();
    }

    @Test
    @DisplayName("a stop whose cause reached the row is reported as a real stop")
    void stopThatLandedIsReportedAsSuccess() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.CANCELLED));

        AgentRunStopService.StopOutcome outcome =
                service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, "my sentence", "agent");

        assertThat(outcome.alreadyTerminal()).isFalse();
        assertThat(outcome.reason()).isEqualTo("my sentence");
        assertThat(outcome.stoppedBy()).isEqualTo("agent");
    }

    @Test
    @DisplayName("a run that is alive but not stoppable still propagates the refusal")
    void aliveButNotStoppableStillFails() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.PENDING)))
                .thenReturn(Optional.of(run(RunStatus.PENDING)));
        doThrow(new IllegalStateException("Cannot cancel workflow in status: PENDING."))
                .when(resumeService).cancelWorkflow(eq(RUN_ID), any());

        assertThatThrownBy(() -> service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, null, "agent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    /**
     * The three keys describe ONE stop, so they are written as a set: a later stop with no
     * reason must clear the previous sentence instead of letting it be re-attributed.
     */
    @Test
    @DisplayName("a stop with no reason asks for the previous reason to be removed, not kept")
    void reasonlessStopRequestsRemovalOfTheOldReason() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.CANCELLED));

        service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, null, "agent");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(resumeService).cancelWorkflow(eq(RUN_ID), metadata.capture());
        assertThat(metadata.getValue()).containsKey(AgentRunStopService.META_STOP_REASON);
        assertThat(metadata.getValue().get(AgentRunStopService.META_STOP_REASON)).isNull();
    }

    @Test
    @DisplayName("an unknown run id is rejected instead of silently doing nothing")
    void unknownRunIsRejected() {
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().stop(RUN_ID, AgentRunStopService.Mode.CANCEL, null, "agent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RUN_ID);
        verifyNoInteractions(resumeService);
    }

    @Test
    @DisplayName("a null mode defaults to cancel, the mode 'stop this for good' means")
    void nullModeDefaultsToCancel() {
        when(runRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)))
                .thenAnswer(inv -> runAfterStopLanded(RunStatus.CANCELLED));

        AgentRunStopService.StopOutcome outcome = service().stop(RUN_ID, null, null, "agent");

        verify(resumeService).cancelWorkflow(eq(RUN_ID), any());
        assertThat(outcome.mode()).isEqualTo(AgentRunStopService.Mode.CANCEL);
    }

    @Test
    @DisplayName("Mode.parse: known values, blank defaults to cancel, garbage is rejected")
    void modeParsing() {
        assertThat(AgentRunStopService.Mode.parse(null)).contains(AgentRunStopService.Mode.CANCEL);
        assertThat(AgentRunStopService.Mode.parse("  ")).contains(AgentRunStopService.Mode.CANCEL);
        assertThat(AgentRunStopService.Mode.parse("CANCEL")).contains(AgentRunStopService.Mode.CANCEL);
        assertThat(AgentRunStopService.Mode.parse(" graceful ")).contains(AgentRunStopService.Mode.GRACEFUL);
        assertThat(AgentRunStopService.Mode.parse("kill")).isEmpty();
        assertThat(AgentRunStopService.Mode.GRACEFUL.wireValue()).isEqualTo("graceful");
    }
}
