package com.apimarketplace.orchestrator.tools.common;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.services.resume.AgentRunStopService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RunStopToolHandler} - the one implementation of the
 * agent-facing {@code stop_run} action shared by the workflow and application tools.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RunStopToolHandler - shared stop_run action")
class RunStopToolHandlerTest {

    private static final String RUN_ID = "run-42";
    private static final String OTHER_RUN_ID = "run-99";

    @Mock AgentRunStopService agentRunStopService;

    private RunStopToolHandler handler() {
        return new RunStopToolHandler(agentRunStopService);
    }

    private WorkflowRunEntity run() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        return run;
    }

    private ToolExecutionContext contextRunningInside(String runId) {
        Map<String, Object> credentials = new HashMap<>();
        if (runId != null) credentials.put("__workflowRunId__", runId);
        return new ToolExecutionContext("tenant-1", credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private AgentRunStopService.StopOutcome outcome(String previous, String status, boolean alreadyTerminal) {
        return new AgentRunStopService.StopOutcome(RUN_ID, previous, status,
                AgentRunStopService.Mode.CANCEL, "it went wrong", "agent", alreadyTerminal);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    // ==================== Target resolution ====================

    @Test
    @DisplayName("an explicit run_id wins over the caller's own run")
    void explicitRunIdWins() {
        String resolved = handler().resolveTargetRunId(
                Map.of("run_id", OTHER_RUN_ID), contextRunningInside(RUN_ID));
        assertThat(resolved).isEqualTo(OTHER_RUN_ID);
    }

    @Test
    @DisplayName("without run_id, an agent running inside a workflow targets its own run (self-abort)")
    void missingRunIdFallsBackToCallerRun() {
        assertThat(handler().resolveTargetRunId(Map.of(), contextRunningInside(RUN_ID))).isEqualTo(RUN_ID);
    }

    @Test
    @DisplayName("no run_id and no execution context resolves to nothing")
    void noTargetResolvesToNull() {
        assertThat(handler().resolveTargetRunId(Map.of(), contextRunningInside(null))).isNull();
        assertThat(handler().resolveTargetRunId(Map.of(), null)).isNull();
    }

    /**
     * The dangerous case: an agent inside a workflow passes run_id as a number (LLMs do).
     * Falling back to "the run I am inside" would turn a typo into a self-abort, which is
     * the most destructive reading of an ambiguous call.
     */
    @Test
    @DisplayName("a run_id that is not a run id string is REFUSED, never taken as a self-abort")
    void unusableRunIdIsNotTreatedAsSelfAbort() {
        Map<String, Object> numeric = new HashMap<>();
        numeric.put("run_id", 42);

        String resolved = handler().resolveTargetRunId(numeric, contextRunningInside(RUN_ID));

        assertThat(resolved).isEqualTo(RunStopToolHandler.BAD_RUN_ID);
        assertThat(handler().badRunIdMessage(numeric))
                .contains("must be a run id string")
                .contains("omit run_id entirely");
    }

    @Test
    @DisplayName("an empty run_id is refused too, rather than silently stopping the caller's own run")
    void emptyRunIdIsRefused() {
        Map<String, Object> blank = new HashMap<>();
        blank.put("run_id", "");

        assertThat(handler().resolveTargetRunId(blank, contextRunningInside(RUN_ID)))
                .isEqualTo(RunStopToolHandler.BAD_RUN_ID);
    }

    @Test
    @DisplayName("an explicit JSON null run_id still means 'stop my own run'")
    void explicitNullRunIdFallsBackToSelf() {
        Map<String, Object> nulled = new HashMap<>();
        nulled.put("run_id", null);

        assertThat(handler().resolveTargetRunId(nulled, contextRunningInside(RUN_ID))).isEqualTo(RUN_ID);
    }

    @Test
    @DisplayName("the no-target message names the calling tool so the agent can fix the call")
    void noTargetMessageNamesTheTool() {
        assertThat(handler().noTargetMessage("application"))
                .contains("run_id is required")
                .contains("application(action='execute')");
    }

    // ==================== Stop ====================

    @Test
    @DisplayName("a stop returns the status transition, the mode and the reason")
    void stopReturnsTransition() {
        when(agentRunStopService.stop(eq(RUN_ID), eq(AgentRunStopService.Mode.CANCEL),
                eq("it went wrong"), eq("agent")))
                .thenReturn(outcome("RUNNING", "CANCELLED", false));

        ToolExecutionResult result = handler().stop(run(),
                Map.of("reason", "it went wrong"), contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(result.success()).isTrue();
        Map<String, Object> data = data(result);
        assertThat(data).containsEntry("run_id", RUN_ID)
                .containsEntry("previous_status", "RUNNING")
                .containsEntry("status", "CANCELLED")
                .containsEntry("mode", "cancel")
                .containsEntry("stopped_by", "agent")
                .containsEntry("reason", "it went wrong");
        assertThat((String) data.get("NEXT")).contains("workflow(action='get_run'");
    }

    @Test
    @DisplayName("stopping another run does NOT mark the result as a self-abort")
    void stoppingAnotherRunIsNotSelf() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenReturn(outcome("RUNNING", "CANCELLED", false));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(data(result)).doesNotContainKey("self");
        assertThat((String) data(result).get("message"))
                .doesNotContain("the run you are executing inside")
                .doesNotContain("Stop working now");
    }

    @Test
    @DisplayName("an agent stopping its OWN run gets self=true and an explicit stop-working instruction")
    void selfAbortIsFlagged() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenReturn(outcome("RUNNING", "CANCELLED", false));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(RUN_ID), "workflow");

        Map<String, Object> data = data(result);
        assertThat(data).containsEntry("self", true);
        assertThat((String) data.get("message"))
                .contains("the run you are executing inside")
                .contains("Stop working now");
    }

    @Test
    @DisplayName("a run that had already ended reports already_terminal instead of pretending it stopped it")
    void alreadyTerminalIsReported() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenReturn(outcome("COMPLETED", "COMPLETED", true));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(data(result)).containsEntry("already_terminal", true);
        assertThat((String) data(result).get("message")).contains("Nothing to stop");
    }

    @Test
    @DisplayName("mode is optional and defaults to cancel")
    void modeDefaultsToCancel() {
        when(agentRunStopService.stop(eq(RUN_ID), eq(AgentRunStopService.Mode.CANCEL), isNull(), eq("agent")))
                .thenReturn(outcome("RUNNING", "CANCELLED", false));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(result.success()).isTrue();
        verify(agentRunStopService).stop(RUN_ID, AgentRunStopService.Mode.CANCEL, null, "agent");
    }

    @Test
    @DisplayName("graceful mode is passed through instead of being silently downgraded to cancel")
    void gracefulModeIsPassedThrough() {
        when(agentRunStopService.stop(eq(RUN_ID), eq(AgentRunStopService.Mode.GRACEFUL), isNull(), eq("agent")))
                .thenReturn(new AgentRunStopService.StopOutcome(RUN_ID, "RUNNING", "WAITING_TRIGGER",
                        AgentRunStopService.Mode.GRACEFUL, null, "agent", false));

        ToolExecutionResult result = handler().stop(run(), Map.of("mode", "graceful"),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(data(result)).containsEntry("mode", "graceful");
        assertThat((String) data(result).get("message")).contains("next trigger fire");
    }

    @Test
    @DisplayName("an unknown mode is rejected up front and nothing is stopped")
    void unknownModeIsRejected() {
        ToolExecutionResult result = handler().stop(run(), Map.of("mode", "kill"),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).contains("'cancel'").contains("'graceful'");
        verifyNoInteractions(agentRunStopService);
    }

    @Test
    @DisplayName("a non-string mode is refused instead of silently defaulting to the terminal cancel")
    void nonStringModeIsRefused() {
        Map<String, Object> params = new HashMap<>();
        params.put("mode", 1);

        ToolExecutionResult result = handler().stop(run(), params, contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        verifyNoInteractions(agentRunStopService);
    }

    @Test
    @DisplayName("a non-string reason is refused instead of being silently dropped")
    void nonStringReasonIsRefused() {
        Map<String, Object> params = new HashMap<>();
        params.put("reason", Map.of("why", "nested"));

        ToolExecutionResult result = handler().stop(run(), params, contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).contains("reason must be");
        verifyNoInteractions(agentRunStopService);
    }

    /**
     * cancel is workflow-level: it suspends the workflow's schedules. An agent that
     * only wanted to end one execution has to learn that from the response.
     */
    @Test
    @DisplayName("the cancel message warns that the workflow's schedules were suspended, and names the alternative")
    void cancelMessageWarnsAboutSchedules() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenReturn(outcome("RUNNING", "CANCELLED", false));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat((String) data(result).get("message"))
                .contains("SUSPENDED the schedules")
                .contains("mode='graceful'");
    }

    @Test
    @DisplayName("the graceful message states the schedules were left alone")
    void gracefulMessageSaysSchedulesKeepRunning() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenReturn(new AgentRunStopService.StopOutcome(RUN_ID, "RUNNING", "WAITING_TRIGGER",
                        AgentRunStopService.Mode.GRACEFUL, null, "agent", false));

        ToolExecutionResult result = handler().stop(run(), Map.of("mode", "graceful"),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat((String) data(result).get("message")).contains("schedules keep running");
    }

    @Test
    @DisplayName("a self-abort warns a sub-agent that it just ended the whole parent run")
    void selfAbortWarnsSubAgent() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenReturn(outcome("RUNNING", "CANCELLED", false));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(RUN_ID), "workflow");

        assertThat((String) data(result).get("message")).contains("sub-agent");
    }

    @Test
    @DisplayName("a run that has not started yet is a dead end, and the message says so instead of looping")
    void notYetStartedRunIsADeadEnd() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Cannot cancel workflow in status: PENDING."));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("has not started executing yet")
                .doesNotContain("action='get_run'");
    }

    @Test
    @DisplayName("the not-yet-started hint never names an action the calling tool does not have")
    void notYetStartedHintStaysWithinTheCallingTool() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Cannot cancel workflow in status: PENDING."));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(OTHER_RUN_ID), "application");

        // wait_run exists on the workflow tool only.
        assertThat(result.error())
                .doesNotContain("wait_run")
                .contains("application(action='get_run'");
    }

    @Test
    @DisplayName("isCallerOwnRun is true only for the run the caller is executing inside")
    void isCallerOwnRunIdentifiesTheCallersRun() {
        assertThat(handler().isCallerOwnRun(run(), contextRunningInside(RUN_ID))).isTrue();
        assertThat(handler().isCallerOwnRun(run(), contextRunningInside(OTHER_RUN_ID))).isFalse();
        assertThat(handler().isCallerOwnRun(run(), contextRunningInside(null))).isFalse();
        assertThat(handler().isCallerOwnRun(run(), null)).isFalse();
        assertThat(handler().isCallerOwnRun(null, contextRunningInside(RUN_ID))).isFalse();
    }

    @Test
    @DisplayName("a run that is alive but not stoppable in this mode tells the agent what to do instead")
    void notStoppableInModeSuggestsCancel() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Cannot stop workflow in status: WAITING_TRIGGER."));

        ToolExecutionResult result = handler().stop(run(), Map.of("mode", "graceful"),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(result.error())
                .contains("WAITING_TRIGGER")
                .contains("mode='cancel'");
    }

    @Test
    @DisplayName("a vanished run surfaces as not found, not as a generic execution failure")
    void vanishedRunSurfacesAsNotFound() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Run not found: " + RUN_ID));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(OTHER_RUN_ID), "workflow");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("the follow-up hint points at the tool the agent actually called")
    void nextHintFollowsTheCallingTool() {
        when(agentRunStopService.stop(any(), any(), any(), any()))
                .thenReturn(outcome("RUNNING", "CANCELLED", false));

        ToolExecutionResult result = handler().stop(run(), Map.of(),
                contextRunningInside(OTHER_RUN_ID), "application");

        assertThat((String) data(result).get("NEXT")).startsWith("application(action='get_run'");
    }
}
