package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.common.RunStopToolHandler;
import com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowCrudModule} - stop_run action (resolution,
 * scoping and allow-list; the stop itself is covered by RunStopToolHandlerTest).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudModule - stop_run")
class WorkflowCrudModuleStopRunTest {

    private static final String TENANT_ID = "tenant-stop";
    private static final String RUN_ID = "run-stop-1";

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

    private WorkflowCrudModule module;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        module = new WorkflowCrudModule(workflowService, workflowRunRepository,
                agentWorkflowFireService, planVersionService, pinService, publicationClient,
                credentialClient, workflowRepository, new ApplicationShowcaseResolver(workflowRunRepository),
                cancellationProbe, runStopToolHandler,
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.resume.StepRerunService.class),
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService.class));
        workflowId = UUID.randomUUID();
    }

    private WorkflowRunEntity run(String tenantId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(workflowId);
        workflow.setTenantId(tenantId);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(tenantId);
        run.setStatus(RunStatus.RUNNING);
        run.setWorkflow(workflow);
        return run;
    }

    private ToolExecutionContext contextWithAllowedWorkflows(List<String> allowed) {
        Map<String, Object> credentials = allowed == null
                ? Map.of()
                : Map.of("allowedWorkflowIds", allowed);
        return new ToolExecutionContext(TENANT_ID, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private ToolExecutionResult stopRun(Map<String, Object> params, ToolExecutionContext context) {
        Optional<ToolExecutionResult> result = module.execute("stop_run", params, TENANT_ID, context);
        assertThat(result).isPresent();
        return result.get();
    }

    @Test
    @DisplayName("canHandle: stop_run is a handled action")
    void canHandleStopRun() {
        assertThat(module.canHandle("stop_run")).isTrue();
    }

    @Test
    @DisplayName("no run_id and no caller run: the agent is told how to name a run instead of a silent no-op")
    void missingTargetIsRejected() {
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(null);
        when(runStopToolHandler.noTargetMessage("workflow")).thenReturn("run_id is required for stop_run.");

        ToolExecutionResult result = stopRun(Map.of(), null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.error()).contains("run_id is required");
        verify(workflowRunRepository, never()).findByRunIdPublic(any());
    }

    @Test
    @DisplayName("a run_id that is not a run id string is refused, never taken as a self-abort")
    void unusableRunIdIsRefused() {
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RunStopToolHandler.BAD_RUN_ID);
        when(runStopToolHandler.badRunIdMessage(any())).thenReturn("run_id must be a run id string");

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("run_id", 42);
        ToolExecutionResult result = stopRun(params, null);

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        verify(workflowRunRepository, never()).findByRunIdPublic(any());
        verify(runStopToolHandler, never()).stop(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unknown run is reported as not found and never reaches the stop")
    void unknownRunIsNotFound() {
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

        ToolExecutionResult result = stopRun(Map.of("run_id", RUN_ID), null);

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        verify(runStopToolHandler, never()).stop(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a run of another tenant is invisible: not found, and it is NOT stopped")
    void crossTenantRunIsNotStopped() {
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run("someone-else")));

        ToolExecutionResult result = stopRun(Map.of("run_id", RUN_ID), null);

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        verify(runStopToolHandler, never()).stop(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a restricted agent cannot stop a run of a workflow outside its allow-list")
    void allowListBlocksForeignWorkflow() {
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));

        ToolExecutionResult result = stopRun(Map.of("run_id", RUN_ID),
                contextWithAllowedWorkflows(List.of(UUID.randomUUID().toString())));

        assertThat(result.success()).isFalse();
        verify(runStopToolHandler, never()).stop(any(), any(), any(), any());
    }

    /**
     * Every help text promises an agent can abort ITSELF. Applying the workflow allow-list
     * to that call would deny exactly that: an agent restricted to workflow X, executing
     * inside workflow Y, could not stop its own run.
     */
    @Test
    @DisplayName("a restricted agent CAN still abort the run it is executing inside")
    void allowListDoesNotBlockSelfAbort() {
        WorkflowRunEntity run = run(TENANT_ID);
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(runStopToolHandler.isCallerOwnRun(eq(run), any())).thenReturn(true);
        when(runStopToolHandler.stop(eq(run), any(), any(), eq("workflow")))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "CANCELLED", "self", true)));

        ToolExecutionResult result = stopRun(Map.of(),
                contextWithAllowedWorkflows(List.of(UUID.randomUUID().toString())));

        assertThat(result.success()).isTrue();
        verify(runStopToolHandler).stop(eq(run), any(), any(), eq("workflow"));
    }

    @Test
    @DisplayName("an in-scope run is handed to the shared stop handler with the workflow tool name")
    void inScopeRunIsDelegatedToHandler() {
        WorkflowRunEntity run = run(TENANT_ID);
        Map<String, Object> params = Map.of("run_id", RUN_ID, "reason", "stuck on a login wall");
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(runStopToolHandler.stop(eq(run), eq(params), any(), eq("workflow")))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "CANCELLED")));

        ToolExecutionResult result = stopRun(params, contextWithAllowedWorkflows(null));

        assertThat(result.success()).isTrue();
        verify(runStopToolHandler).stop(eq(run), eq(params), any(), eq("workflow"));
    }
}
