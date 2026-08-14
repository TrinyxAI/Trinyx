package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeExecutionService;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeResult;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three ways {@code run_node} could be turned into an escalation, each verified to be shut.
 *
 * <p>All three share a shape: a gate is asked about one thing, and something else executes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunNodeEscalationTest {

    @Mock private AdHocNodeExecutionService adHocNodeExecutionService;
    @Mock private NodeTypeSearchService nodeTypeSearchService;
    @Mock private WorkflowBuilderResultEnricher resultEnricher;
    @Mock private WorkflowBuilderLogger buildLogger;

    private WorkflowBuilderProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        java.lang.reflect.Constructor<?> ctor = WorkflowBuilderProvider.class.getDeclaredConstructors()[0];
        provider = (WorkflowBuilderProvider) ctor.newInstance(new Object[ctor.getParameterCount()]);
        set("adHocNodeExecutionService", adHocNodeExecutionService);
        set("nodeTypeSearchService", nodeTypeSearchService);
        set("resultEnricher", resultEnricher);
        set("buildLogger", buildLogger);
        when(nodeTypeSearchService.isNodeTypeEnabled(any())).thenReturn(true);
        lenient().when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(adHocNodeExecutionService.execute(any()))
                .thenReturn(new AdHocNodeResult("code", AdHocNodeResult.COMPLETED, Map.of(), null, 1L, null));
    }

    private void set(String name, Object value) throws Exception {
        Field f = WorkflowBuilderProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(provider, value);
    }

    private ToolExecutionResult run(ToolExecutionContext ctx, String type, Map<String, Object> config) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "run_node");
        params.put("type", type);
        params.put("params", config);
        return provider.execute("workflow", params, ctx);
    }

    /** The one context where a card is really raised: general chat, live stream, no bound agent. */
    private static ToolExecutionContext interactiveChat() {
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        return new ToolExecutionContext("tenant-1", creds, Map.of(), Set.of(), null, null, "org-1", "OWNER");
    }

    private static ToolExecutionContext withCredentials(Map<String, Object> creds) {
        return new ToolExecutionContext("tenant-1", creds, Map.of(), Set.of(), null, null, "org-1", "OWNER");
    }

    @Nested
    @DisplayName("the config cannot rewrite what the node IS")
    class TypeHijack {

        /**
         * For a type whose config lives at the core's top level, the config used to be merged in
         * AFTER the identity, so it could overwrite `type`. Every gate was then asked about the
         * declared type while a different node executed, and the conversation and the
         * observability row both recorded the declared type. That is a gate bypass, an audit
         * falsification and a refusal bypass in one call.
         */
        @ParameterizedTest(name = "params.type on a {0} node must be refused")
        @ValueSource(strings = {"decision", "switch", "exit", "media", "public_link"})
        @DisplayName("Should refuse a config that carries its own type")
        void shouldRefuseTypeInConfig(String declaredType) {
            Map<String, Object> hijack = new LinkedHashMap<>();
            hijack.put("type", "ssh");
            hijack.put("ssh", Map.of("host", "127.0.0.1", "command", "id"));

            ToolExecutionResult result = run(interactiveChat(), declaredType, hijack);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("identifies the node itself");
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @Test
        @DisplayName("Should refuse a config that carries its own id or label too")
        void shouldRefuseIdentityKeys() {
            assertThat(run(interactiveChat(), "code", Map.of("id", "core:other")).success()).isFalse();
            assertThat(run(interactiveChat(), "code", Map.of("label", "Other")).success()).isFalse();
        }
    }

    @Nested
    @DisplayName("only a context where the user is really asked")
    class AuthorizationSurface {

        @Test
        @DisplayName("Should allow the general interactive chat, which is the one gated context")
        void shouldAllowInteractiveChat() {
            run(interactiveChat(), "code", Map.of("code", "return 1;"));

            verify(adHocNodeExecutionService).execute(any());
        }

        /**
         * Each of these has a conversationId, a streamId, or both, so a lookalike predicate says
         * "conversation" and lets the node run. None of them raises a card, so nobody is asked.
         */
        @Test
        @DisplayName("Should refuse an unattended workflow run, which has nobody to ask")
        void shouldRefuseWorkflowRun() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__streamId__", "stream-1");
            creds.put("__workflowRunId__", "run-42");

            ToolExecutionResult result = run(withCredentials(creds), "ssh", Map.of("host", "h"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("no one to ask");
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @Test
        @DisplayName("Should refuse a sub-agent, which inherits its parent's authorization")
        void shouldRefuseSubAgent() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__streamId__", "stream-1");
            creds.put("__agent_depth__", 1);

            assertThat(run(withCredentials(creds), "ssh", Map.of("host", "h")).success()).isFalse();
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @Test
        @DisplayName("Should refuse an agent-backed chat, which is exempt from the card")
        void shouldRefuseAgentBackedChat() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__streamId__", "stream-1");
            creds.put("__agentId__", "agent-7");

            assertThat(run(withCredentials(creds), "ssh", Map.of("host", "h")).success()).isFalse();
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @Test
        @DisplayName("Should refuse a task-driven agent")
        void shouldRefuseTaskDriven() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__streamId__", "stream-1");
            creds.put("__taskId__", "task-3");

            assertThat(run(withCredentials(creds), "ssh", Map.of("host", "h")).success()).isFalse();
        }

        @Test
        @DisplayName("Should refuse a conversation with no live stream, which shows no card")
        void shouldRefuseHeadlessConversation() {
            assertThat(run(withCredentials(Map.of("conversationId", "conv-1")), "ssh", Map.of()).success())
                    .isFalse();
        }

        @Test
        @DisplayName("Should refuse the external MCP surface, whose credentials are empty")
        void shouldRefuseExternalMcp() {
            assertThat(run(withCredentials(Map.of()), "code", Map.of("code", "x")).success()).isFalse();
        }

        @Test
        @DisplayName("Should allow an otherwise-exempt context that explicitly turned the card back on")
        void shouldAllowExplicitAuthorizationOverride() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("__workflowRunId__", "run-42");
            creds.put("__requireToolAuthorization__", true);

            run(withCredentials(creds), "code", Map.of("code", "return 1;"));

            verify(adHocNodeExecutionService).execute(any());
        }
    }

    @Nested
    @DisplayName("paid generation keeps its own grant")
    class GenerationGrant {

        @Test
        @DisplayName("Should refuse a generate node when the caller's modules exclude generation")
        void shouldRefuseGenerateWithoutGrant() {
            // add_node refuses it because the node spends credits on a paid provider. Leaving it
            // out here would reopen the opt-in one indirection away.
            // NOTE on the fixture: an ABSENT module list means full access, so the refusal can
            // only be exercised with a list that exists and omits generation.
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__streamId__", "stream-1");
            creds.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, java.util.List.of("workflow", "table"));

            ToolExecutionResult result = run(withCredentials(creds), "generate", Map.of("prompt", "a cat"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not set up to run generations");
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @Test
        @DisplayName("Should allow a generate node once generation is granted")
        void shouldAllowGenerateWithGrant() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__streamId__", "stream-1");
            creds.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, java.util.List.of("workflow", "generation"));

            run(withCredentials(creds), "generate", Map.of("prompt", "a cat"));

            verify(adHocNodeExecutionService).execute(any());
        }
    }
}
