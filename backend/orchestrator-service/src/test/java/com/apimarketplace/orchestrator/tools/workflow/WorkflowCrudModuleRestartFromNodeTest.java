package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationPolicy;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService;
import com.apimarketplace.orchestrator.services.resume.StepRerunService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.common.RunStopToolHandler;
import com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderActionConfig;
import com.apimarketplace.publication.client.PublicationClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent-facing entry point for "restart this run from one node".
 *
 * <p>What matters here is that the agent is never left guessing: the run must be scoped and
 * allow-listed like every other run action, a refusal has to say what to do instead, and the
 * response has to state whether the replay actually finished, because a rerun that YIELDED and
 * one that ran to completion both come back with success=true.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudModule - restart_from_node")
class WorkflowCrudModuleRestartFromNodeTest {

    private static final String TENANT_ID = "tenant-restart";
    private static final String RUN_ID = "run-restart-1";
    private static final String NODE = "mcp:fetch_data";
    private static final String TRIGGER = "trigger:start";

    @Mock WorkflowManagementService workflowService;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock AgentWorkflowFireService agentWorkflowFireService;
    @Mock WorkflowPlanVersionService planVersionService;
    @Mock WorkflowPinService pinService;
    @Mock PublicationClient publicationClient;
    @Mock com.apimarketplace.credential.client.CredentialClient credentialClient;
    @Mock com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;
    @Mock AgentCancellationProbe cancellationProbe;
    @Mock RunStopToolHandler runStopToolHandler;
    @Mock StepRerunService stepRerunService;
    @Mock AutoRestartExecutionService autoRestartExecutionService;

    private WorkflowCrudModule module;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        module = new WorkflowCrudModule(workflowService, workflowRunRepository,
                agentWorkflowFireService, planVersionService, pinService, publicationClient,
                credentialClient, workflowRepository, new ApplicationShowcaseResolver(workflowRunRepository),
                cancellationProbe, runStopToolHandler, stepRerunService, autoRestartExecutionService);
        workflowId = UUID.randomUUID();
    }

    private WorkflowRunEntity run(String tenantId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(workflowId);
        workflow.setTenantId(tenantId);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(tenantId);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setWorkflow(workflow);
        return run;
    }

    private ToolExecutionContext context(List<String> allowedWorkflowIds) {
        Map<String, Object> credentials = allowedWorkflowIds == null
                ? Map.of()
                : Map.of("allowedWorkflowIds", allowedWorkflowIds);
        return new ToolExecutionContext(TENANT_ID, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private ToolExecutionResult restart(Map<String, Object> params, ToolExecutionContext ctx) {
        Optional<ToolExecutionResult> result = module.execute("restart_from_node", params, TENANT_ID, ctx);
        assertThat(result).isPresent();
        return result.get();
    }

    private void rerunReturns(AutoRestartExecutionService.Outcome outcome) {
        when(stepRerunService.rerunFromStep(RUN_ID, NODE)).thenReturn(new StepRerunService.RerunResult(
                RUN_ID, NODE, 4, 1, Set.of(NODE, "mcp:downstream"), Set.of(NODE), "running", 42L, TRIGGER));
        when(autoRestartExecutionService.resumeAfterRerun(anyString(), any(), anyString(), anyInt()))
                .thenReturn(outcome);
    }

    @Test
    @DisplayName("The action is reachable: handled by the module and listed for the agent")
    void actionIsReachable() {
        assertThat(module.canHandle("restart_from_node")).isTrue();
        // Listing it is what makes it callable at all; a handled-but-unlisted action is dead code.
        assertThat(WorkflowBuilderActionConfig.PRIMARY_ACTIONS).contains("restart_from_node");
    }

    @Test
    @DisplayName("It needs the user's go-ahead in chat, like every action that STARTS work")
    void requiresUserAuthorization() {
        // It re-executes nodes unattended, with the same spend and side effects as a fresh fire.
        assertThat(ToolAuthorizationPolicy.requires("workflow", "restart_from_node")).isTrue();
    }

    @Test
    @DisplayName("Missing run_id: the agent is told where to find one, not just refused")
    void missingRunIdIsRejected() {
        ToolExecutionResult result = restart(Map.of("node", NODE), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.error()).contains("action='runs'");
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString());
    }

    @Test
    @DisplayName("Missing node: the message says which key shape to pass")
    void missingNodeIsRejected() {
        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.error()).contains("get_run");
        assertThat(result.error()).contains("mcp:fetch_data");
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString());
    }

    @Test
    @DisplayName("A blank node is treated as missing, not passed through as a step id")
    void blankNodeIsRejected() {
        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", "   "), context(null));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString());
    }

    @Test
    @DisplayName("Another tenant's run reads as not found, never as forbidden")
    void otherTenantRunIsNotFound() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run("someone-else")));

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString());
    }

    @Test
    @DisplayName("A workflow outside the agent's allow-list is refused before anything is reset")
    void workflowOutsideAllowListIsRefused() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE),
                context(List.of(UUID.randomUUID().toString())));

        assertThat(result.success()).isFalse();
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString());
    }

    @Test
    @DisplayName("Happy path: reports what was replayed, in which attempt, and that it settled")
    void restartsAndReportsWhatHappened() {
        WorkflowRunEntity entity = run(TENANT_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(entity));
        rerunReturns(AutoRestartExecutionService.Outcome.QUIESCED);

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        assertThat(result.success()).isTrue();
        Map<String, Object> data = asMap(result.data());
        assertThat(data.get("restarted_from")).isEqualTo(NODE);
        assertThat(data.get("outcome")).isEqualTo("QUIESCED");
        assertThat(data.get("epoch")).isEqualTo(4);
        // The attempt counter is what lets the agent tell this replay's output from the first run's.
        assertThat(data.get("attempt")).isEqualTo(1);
        Set<String> replayed = ((Set<?>) data.get("replayed_nodes")).stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        assertThat(replayed).containsExactlyInAnyOrder(NODE, "mcp:downstream");
        assertThat(String.valueOf(data.get("summary"))).contains("get_run");
    }

    @Test
    @DisplayName("A yield is reported as unfinished work, with the action that unblocks it")
    void yieldTellsTheAgentItIsNotDone() {
        // success=true would otherwise read as "the replay is done" while the run sits on an
        // approval nobody resolves.
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.YIELDED);

        Map<String, Object> data = asMap(restart(Map.of("run_id", RUN_ID, "node", NODE), context(null)).data());

        assertThat(data.get("outcome")).isEqualTo("YIELDED");
        assertThat(String.valueOf(data.get("summary"))).contains("resolve_approval");
    }

    @Test
    @DisplayName("A stepped run says nothing was executed, instead of implying a replay ran")
    void steppedRunSaysNothingRan() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.STEP_BY_STEP);

        Map<String, Object> data = asMap(restart(Map.of("run_id", RUN_ID, "node", NODE), context(null)).data());

        assertThat(String.valueOf(data.get("summary"))).contains("nothing was executed");
    }

    @Test
    @DisplayName("Hitting the safety limit is reported as INCOMPLETE, not as a finished replay")
    void waveCapIsReportedAsIncomplete() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.WAVE_CAP);

        Map<String, Object> data = asMap(restart(Map.of("run_id", RUN_ID, "node", NODE), context(null)).data());

        assertThat(String.valueOf(data.get("summary"))).contains("INCOMPLETE");
    }

    @Test
    @DisplayName("A node that cannot be restarted surfaces the backend's own explanation")
    void refusalKeepsTheBackendExplanation() {
        // The service refuses a node that never settled; swallowing that message would leave the
        // agent retrying the same call forever.
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        when(stepRerunService.rerunFromStep(RUN_ID, NODE))
                .thenThrow(new IllegalStateException("Cannot rerun step mcp:fetch_data: not in rerunnable state."));

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).contains("not in rerunnable state");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object data) {
        assertThat(data).isInstanceOf(Map.class);
        return (Map<String, Object>) data;
    }
}
