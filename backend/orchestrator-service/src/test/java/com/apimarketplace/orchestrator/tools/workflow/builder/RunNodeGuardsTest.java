package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeExecutionService;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeRequest;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeResult;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The refusals and gates around {@code workflow(action='run_node')}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunNodeGuardsTest {

    @Mock private AdHocNodeExecutionService adHocNodeExecutionService;
    @Mock private NodeTypeSearchService nodeTypeSearchService;
    @Mock private WorkflowBuilderResultEnricher resultEnricher;
    @Mock private WorkflowBuilderLogger buildLogger;

    private WorkflowBuilderProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        java.lang.reflect.Constructor<?> ctor = WorkflowBuilderProvider.class.getDeclaredConstructors()[0];
        provider = (WorkflowBuilderProvider) ctor.newInstance(new Object[ctor.getParameterCount()]);
        setField("adHocNodeExecutionService", adHocNodeExecutionService);
        setField("nodeTypeSearchService", nodeTypeSearchService);
        setField("resultEnricher", resultEnricher);
        setField("buildLogger", buildLogger);
        when(nodeTypeSearchService.isNodeTypeEnabled(any())).thenReturn(true);
        lenient().when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(adHocNodeExecutionService.execute(any()))
                .thenReturn(new AdHocNodeResult("code", AdHocNodeResult.COMPLETED, Map.of(), null, 5L, null));
    }

    private void setField(String name, Object value) throws Exception {
        Field f = WorkflowBuilderProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(provider, value);
    }

    private static ToolExecutionContext inConversation() {
        return new ToolExecutionContext("tenant-1",
                Map.of("conversationId", "conv-1", "__streamId__", "stream-1"),
                Map.of(), Set.of(), null, null, "org-1", "OWNER");
    }

    /** What McpProtocolService builds: same registry, empty credentials, no turn. */
    private static ToolExecutionContext externalMcp() {
        return new ToolExecutionContext("tenant-1", Map.of(), Map.of(), Set.of(), null, null, "org-1", "OWNER");
    }

    private ToolExecutionResult run(ToolExecutionContext ctx, String type, Map<String, Object> config) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "run_node");
        if (type != null) params.put("type", type);
        if (config != null) params.put("params", config);
        return provider.execute("workflow", params, ctx);
    }

    @Nested
    @DisplayName("external MCP surface")
    class ExternalSurface {

        @Test
        @DisplayName("Should refuse run_node outside a conversation, so existing API keys gain nothing")
        void shouldRefuseOutsideConversation() {
            // API-key scopes are per TOOL, never per action. Any key already issued with the
            // 'workflow' scope would otherwise be able to send mail and run SQL the day this
            // ships, without its owner doing anything.
            ToolExecutionResult result = run(externalMcp(), "code", Map.of("code", "return 1;"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("no one to ask");
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @Test
        @DisplayName("Should accept run_node when a stream id identifies the turn")
        void shouldAcceptWithStreamId() {
            ToolExecutionContext ctx = new ToolExecutionContext("tenant-1",
                    Map.of("conversationId", "conv-9", "__streamId__", "stream-9"),
                    Map.of(), Set.of(), null, null, null, null);

            run(ctx, "code", Map.of("code", "return 1;"));

            verify(adHocNodeExecutionService).execute(any());
        }
    }

    @Nested
    @DisplayName("node types that cannot mean anything standalone")
    class RefusedTypes {

        @ParameterizedTest(name = "{0} suspends on a signal nothing can deliver")
        @ValueSource(strings = {"approval", "interface", "wait"})
        @DisplayName("Should refuse a signal type instead of hanging on an unresolvable wait")
        void shouldRefuseSignalTypes(String type) {
            ToolExecutionResult result = run(inConversation(), type, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("pauses on a signal");
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @ParameterizedTest(name = "{0} only means something inside a DAG")
        @ValueSource(strings = {"merge", "aggregate", "split", "fork", "loop"})
        @DisplayName("Should refuse a DAG type instead of returning a misleading success")
        void shouldRefuseDagTypes(String type) {
            // Left to run, merge skips itself for want of predecessors and aggregate finalises
            // on a batch of one: both look like success and mean nothing.
            ToolExecutionResult result = run(inConversation(), type, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("no meaning on its own");
            verify(adHocNodeExecutionService, never()).execute(any());
        }
    }

    @Nested
    @DisplayName("administrator gate")
    class DisabledTypes {

        @Test
        @DisplayName("Should gate the CANONICAL type so an alias cannot walk past a disabled node")
        void shouldGateResolvedAliasNotTypedName() {
            // add_node checks the typed name BEFORE resolving aliases, so type='remote_command'
            // sails past a gate set on 'ssh'. This surface executes, so it resolves first.
            when(nodeTypeSearchService.isNodeTypeEnabled("ssh")).thenReturn(false);

            ToolExecutionResult result = run(inConversation(), "remote_command", Map.of("command", "id"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("disabled by the administrator");
            verify(nodeTypeSearchService).isNodeTypeEnabled("ssh");
            verify(adHocNodeExecutionService, never()).execute(any());
        }
    }

    @Nested
    @DisplayName("alias resolution")
    class Aliases {

        @ParameterizedTest(name = "{0} resolves to the canonical type")
        @ValueSource(strings = {"imap", "inbox", "read_email", "core:email_inbox", "EMAIL_INBOX"})
        @DisplayName("Should resolve every accepted spelling onto one canonical type")
        void shouldResolveAliases(String spelling) {
            run(inConversation(), spelling, Map.of("action", "read"));

            ArgumentCaptor<AdHocNodeRequest> captor = ArgumentCaptor.forClass(AdHocNodeRequest.class);
            verify(adHocNodeExecutionService).execute(captor.capture());
            assertThat(captor.getValue().nodeType()).isEqualTo("email_inbox");
        }

        @Test
        @DisplayName("Should place the config under the nested key the engine reads")
        void shouldCarryTheNestedConfigKey() {
            // Depositing config at the core's top level is the silent-empty-node failure mode:
            // the node builds, runs, and reads nothing.
            run(inConversation(), "email_inbox", Map.of("action", "read"));

            ArgumentCaptor<AdHocNodeRequest> captor = ArgumentCaptor.forClass(AdHocNodeRequest.class);
            verify(adHocNodeExecutionService).execute(captor.capture());
            assertThat(captor.getValue().configKey()).isEqualTo("emailInbox");
        }
    }

    @Nested
    @DisplayName("response shape")
    class ResponseShape {

        @Test
        @DisplayName("Should echo the config back with credentials redacted")
        void shouldRedactEchoedConfig() {
            // The echo is what makes the result reusable in add_node. Echoing it raw is what
            // would write an ssh password into the conversation and the observability row.
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("host", "10.0.0.9");
            config.put("password", "hunter2");

            ToolExecutionResult result = run(inConversation(), "ssh", config);

            String rendered = String.valueOf(result.data());
            assertThat(rendered).contains("10.0.0.9");
            assertThat(rendered).doesNotContain("hunter2");
        }

        @Test
        @DisplayName("Should report an awaiting-signal outcome as neither success nor a config error")
        void shouldSurfaceAwaitingSignal() {
            when(adHocNodeExecutionService.execute(any())).thenReturn(
                    new AdHocNodeResult("code", AdHocNodeResult.AWAITING_SIGNAL, Map.of(), null, 3L,
                            "needs a run"));

            ToolExecutionResult result = run(inConversation(), "code", Map.of("code", "x"));

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                    .as("the note is the only thing telling the agent what to do instead")
                    .contains("AWAITING_SIGNAL")
                    .contains("needs a run");
        }

        @Test
        @DisplayName("Should require a type rather than guessing one")
        void shouldRequireType() {
            ToolExecutionResult result = run(inConversation(), null, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'type' is required");
        }
    }

    @Nested
    @DisplayName("no draft is touched")
    class NoSessionSideEffects {

        @Test
        @DisplayName("Should never auto-save a draft, because run_node mutates no plan")
        void shouldNotAutoSaveDraft() {
            // Listing run_node among the modifying actions would auto-save an unrelated draft on
            // every probe.
            assertThat(WorkflowBuilderActionConfig.isModifyingAction("run_node")).isFalse();
            assertThat(WorkflowBuilderActionConfig.PLAN_MUTATING_ACTIONS).doesNotContain("run_node");
            assertThat(WorkflowBuilderActionConfig.READ_ONLY_ACTIONS)
                    .as("run_node has real side effects, it is not a read")
                    .doesNotContain("run_node");
            assertThat(WorkflowBuilderActionConfig.PRIMARY_ACTIONS).contains("run_node");
        }

        @Test
        @DisplayName("Should carry the caller's workspace identity onto the node")
        void shouldCarryOrgScope() {
            // Credentials resolve per tenant AND per workspace; dropping the org here would make
            // an org-scoped credential resolve in personal scope, or not at all.
            run(inConversation(), "code", Map.of("code", "return 1;"));

            ArgumentCaptor<AdHocNodeRequest> captor = ArgumentCaptor.forClass(AdHocNodeRequest.class);
            verify(adHocNodeExecutionService).execute(captor.capture());
            assertThat(captor.getValue().organizationId()).isEqualTo("org-1");
            assertThat(captor.getValue().organizationRole()).isEqualTo("OWNER");
            assertThat(captor.getValue().tenantId()).isEqualTo("tenant-1");
        }
    }
}
