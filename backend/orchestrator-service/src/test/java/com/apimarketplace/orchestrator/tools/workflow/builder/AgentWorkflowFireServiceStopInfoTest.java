package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.EditorRunResolver;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.AgentRunStopService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins that a run stopped on purpose reports WHY.
 *
 * <p>Without this, an agent that stopped a run (or was waiting on one someone else
 * stopped) only sees {@code status=CANCELLED} and cannot tell a deliberate stop from
 * a crash. The reason recorded by {@code stop_run} has to travel all the way back to
 * the run reports the agent reads.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentWorkflowFireService - stop reason in run reports")
class AgentWorkflowFireServiceStopInfoTest {

    private static final String RUN_ID = "run-stop-info";

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowExecutionService executionService;
    @Mock ReusableTriggerService reusableTriggerService;
    @Mock SignalWaitRepository signalWaitRepository;
    @Mock EditorRunResolver editorRunResolver;
    @Mock StepOutputService stepOutputService;
    @Mock WorkflowStepDataRepository stepDataRepository;
    @Mock WorkflowEpochService epochService;

    private AgentWorkflowFireService service;

    @BeforeEach
    void setUp() {
        service = new AgentWorkflowFireService(
                runRepository, executionService, reusableTriggerService,
                signalWaitRepository, editorRunResolver, new ObjectMapper(),
                stepOutputService, stepDataRepository, epochService);
    }

    private WorkflowRunEntity stoppedRun(Map<String, Object> metadata) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getRunIdPublic()).thenReturn(RUN_ID);
        lenient().when(run.getStatus()).thenReturn(RunStatus.CANCELLED);
        lenient().when(run.getPlanVersion()).thenReturn(1);
        lenient().when(run.getMetadata()).thenReturn(metadata);
        lenient().when(run.getStateSnapshot()).thenReturn(null);
        return run;
    }

    private Map<String, Object> stopMetadata() {
        return Map.of(
                AgentRunStopService.META_STOP_REASON, "the browser agent was stuck on a login wall",
                AgentRunStopService.META_STOPPED_BY, "agent",
                AgentRunStopService.META_STOPPED_AT, "2026-07-30T10:15:30Z");
    }

    private WorkflowEntity workflow() {
        WorkflowEntity e = new WorkflowEntity();
        e.setId(UUID.randomUUID());
        e.setName("Test");
        return e;
    }

    private WorkflowPlan emptyPlan() {
        return WorkflowPlan.fromMap(Map.of(
                "triggers", List.of(Map.of("id", "start", "label", "Start", "type", "manual")),
                "mcps", List.of(), "cores", List.of(), "edges", List.of()));
    }

    private TriggerExecutionResult successResult() {
        return TriggerExecutionResult.success(RUN_ID, "trigger:start", TriggerType.MANUAL, Set.of(), 0);
    }

    @Test
    @DisplayName("the execute report of a stopped run carries the reason, not just CANCELLED")
    void executeReportCarriesStopReason() {
        WorkflowRunEntity run = stoppedRun(stopMetadata());
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        Map<String, Object> result = service.buildResult(run, successResult(), workflow(), emptyPlan());

        assertThat(result).containsEntry("status", "CANCELLED")
                .containsEntry("stop_reason", "the browser agent was stuck on a login wall")
                .containsEntry("stopped_by", "agent")
                .containsEntry("stopped_at", "2026-07-30T10:15:30Z");
    }

    @Test
    @DisplayName("the get_run macro report carries the reason so a later reader still sees the cause")
    void macroReportCarriesStopReason() {
        WorkflowRunEntity run = stoppedRun(stopMetadata());
        when(epochService.listEpochHeaders(RUN_ID)).thenReturn(List.of());

        Map<String, Object> result = service.buildRunMacroReport(run, emptyPlan(), "tenant-1");

        assertThat(result).containsEntry("stop_reason", "the browser agent was stuck on a login wall")
                .containsEntry("stopped_by", "agent");
    }

    @Test
    @DisplayName("a run nobody stopped reports no stop fields at all (zero noise on normal runs)")
    void normalRunHasNoStopFields() {
        WorkflowRunEntity run = stoppedRun(Map.of("__editorRun__", true));
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        Map<String, Object> result = service.buildResult(run, successResult(), workflow(), emptyPlan());

        assertThat(result).doesNotContainKeys("stop_reason", "stopped_by", "stopped_at");
    }

    @Test
    @DisplayName("a stop recorded without a reason still reports WHO stopped it")
    void stopWithoutReasonStillReportsAuthor() {
        WorkflowRunEntity run = stoppedRun(Map.of(
                AgentRunStopService.META_STOPPED_BY, "agent",
                AgentRunStopService.META_STOPPED_AT, "2026-07-30T10:15:30Z"));
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        Map<String, Object> result = service.buildResult(run, successResult(), workflow(), emptyPlan());

        assertThat(result).doesNotContainKey("stop_reason");
        assertThat(result).containsEntry("stopped_by", "agent");
    }
}
