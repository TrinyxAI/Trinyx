package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code stop_run} WIRING on the builder path.
 *
 * <p>Without this, the single dispatch line in {@link WorkflowBuilderProvider} can be
 * deleted and every other test still passes: {@code stop_run} is a known action, so
 * validation lets it through and the dispatcher then falls to its default branch,
 * answering agents with "Unknown action: stop_run". These tests fail on that.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderProvider - stop_run dispatch")
class WorkflowBuilderProviderStopRunTest {

    @Mock private WorkflowBuilderSessionManager sessionManager;
    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private WorkflowBuilderResultEnricher resultEnricher;
    @Mock private WorkflowDraftAutoSaver draftAutoSaver;
    @Mock private WorkflowBuilderToolDefinitionFactory toolDefinitionFactory;
    @Mock private WorkflowBuilderLogger buildLogger;
    @Mock private com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule crudModule;

    @Mock private WorkflowManagementService workflowService;
    @Mock private InterfaceClient interfaceClient;
    @Mock private NodeTypeSearchService nodeTypeSearchService;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private NodeParamsValidator nodeParamsValidator;
    @Mock private WorkflowHelpProvider workflowHelpProvider;

    @Mock private WorkflowBuilderCreator creator;
    @Mock private WorkflowBuilderConnectionManager connectionManager;
    @Mock private WorkflowBuilderModifier modifier;
    @Mock private WorkflowBuilderViewer viewer;
    @Mock private WorkflowBuilderLoader loader;
    @Mock private WorkflowBuilderTableOperations tableOperations;
    @Mock private WorkflowBuilderPlanExporter planExporter;
    @Mock private WorkflowBuilderHelpModule helpModule;

    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private AgentWorkflowFireService agentWorkflowFireService;
    @Mock private com.apimarketplace.orchestrator.services.WorkflowPlanVersionService planVersionService;
    @Mock private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    @Mock private com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService runSignalResolution;
    @Mock private com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher conversationEventPublisher;

    @Spy private AgentDefaultsConfig agentDefaults = new AgentDefaultsConfig();

    @InjectMocks
    private WorkflowBuilderProvider provider;

    private static final String TENANT = "tenant-1";
    private static final String RUN_ID = "run-1";

    private ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private Map<String, Object> stopParams(String action) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        p.put("run_id", RUN_ID);
        p.put("reason", "it went wrong");
        return p;
    }

    private void passThroughEnricher() {
        when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @ParameterizedTest(name = "action=''{0}'' reaches the run-stop module")
    @ValueSource(strings = {"stop_run", "stop", "cancel_run"})
    @DisplayName("stop_run and each of its aliases dispatch to the CRUD module as stop_run")
    void stopRunAndAliasesDispatch(String action) {
        passThroughEnricher();
        when(crudModule.execute(eq("stop_run"), any(), eq(TENANT), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("status", "CANCELLED"))));

        ToolExecutionResult result = provider.execute("workflow", stopParams(action), ctx(Map.of()));

        assertThat(result.success()).isTrue();
        verify(crudModule).execute(eq("stop_run"), any(), eq(TENANT), any());
    }

    /**
     * "cancel" is also how an LLM says "abandon the draft I am building" (the real action
     * is `discard`), and stop_run with no run_id self-aborts. It must stay an unknown
     * action the agent can recover from, not a terminal stop.
     */
    @Test
    @DisplayName("bare 'cancel' does NOT reach the run-stop module")
    void bareCancelDoesNotStopARun() {
        ToolExecutionResult result = provider.execute("workflow", stopParams("cancel"), ctx(Map.of()));

        assertThat(result.success()).isFalse();
        verify(crudModule, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the reason and run_id reach the module untouched")
    void parametersArePassedThrough() {
        passThroughEnricher();
        when(crudModule.execute(eq("stop_run"), any(), eq(TENANT), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of())));

        provider.execute("workflow", stopParams("stop_run"), ctx(Map.of()));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> params =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(crudModule).execute(eq("stop_run"), params.capture(), eq(TENANT), any());
        assertThat(params.getValue())
                .containsEntry("run_id", RUN_ID)
                .containsEntry("reason", "it went wrong");
    }

    /**
     * stop_run is destructive, so it must sit on the WRITE side of the access-mode gate.
     * It is WRITE only by being absent from ToolAccessControl's workflow READ set, and the
     * help lists it next to the read actions, so this is easy to "fix" the wrong way.
     */
    @Test
    @DisplayName("a read-mode agent is DENIED stop_run and never reaches the module")
    void readModeAgentCannotStopARun() {
        ToolExecutionResult result = provider.execute("workflow", stopParams("stop_run"),
                ctx(Map.of("__workflowAccessMode__", "read")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verify(crudModule, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("stopping a run does NOT auto-save the draft (it changes no plan)")
    void stopRunDoesNotTriggerDraftAutoSave() {
        passThroughEnricher();
        when(crudModule.execute(eq("stop_run"), any(), eq(TENANT), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of())));

        provider.execute("workflow", stopParams("stop_run"), ctx(Map.of()));

        verify(draftAutoSaver, never()).autoSaveDraft(any(), any(), any());
    }
}
