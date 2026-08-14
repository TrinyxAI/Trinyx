package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end for {@code workflow(action='run_node')} against the REAL wiring: the Spring context,
 * the real tool provider, the real {@code CoreNodeBuilder}, the real service injector and the real
 * node. Nothing is mocked.
 *
 * <p>The unit tests around this feature all mock the builder, for a good reason (wiring it by hand
 * drags in the template engine and the node registry) - but that leaves one question they cannot
 * answer: does a config handed to this action actually build a node that actually runs? That is
 * what this covers, plus the two refusals that are the whole security argument.
 */
@IntegrationTest
class RunNodeEndToEndIT {

    @Autowired
    private WorkflowBuilderProvider provider;

    /** The one context where an authorization card is really raised. */
    private static ToolExecutionContext interactiveChat() {
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("conversationId", "it-conversation");
        credentials.put("__streamId__", "it-stream");
        return new ToolExecutionContext("it-tenant", credentials, Map.of(), Set.of(),
                null, null, null, null);
    }

    private ToolExecutionResult runNode(ToolExecutionContext ctx, String type, Map<String, Object> config) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "run_node");
        params.put("type", type);
        params.put("params", config);
        return provider.execute("workflow", params, ctx);
    }

    @Test
    @DisplayName("Should build and run a real transform node and hand back its output")
    void shouldRunARealNode() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mappings", List.of(Map.of("label", "greeting", "expression", "'hello'")));

        ToolExecutionResult result = runNode(interactiveChat(), "transform", config);

        assertThat(result.success())
                .as("a pure transform with a valid config must complete: %s", result.error())
                .isTrue();
        String rendered = String.valueOf(result.data());
        assertThat(rendered).contains("COMPLETED");
        assertThat(rendered)
                .as("the output has to reach the agent, not just the status")
                .contains("greeting");
    }

    @Test
    @DisplayName("Should resolve an alias and report the canonical type it actually ran")
    void shouldResolveAliasEndToEnd() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mappings", List.of(Map.of("label", "x", "expression", "'1'")));

        ToolExecutionResult result = runNode(interactiveChat(), "core:transform", config);

        assertThat(result.success()).isTrue();
        assertThat(String.valueOf(result.data())).contains("transform");
    }

    @Test
    @DisplayName("Should refuse a config that carries its own type, so no gate can be walked past")
    void shouldRefuseTypeHijack() {
        // Declared type is gated, refusal-listed and audited; without this the config would decide
        // what really runs.
        Map<String, Object> hijack = new LinkedHashMap<>();
        hijack.put("type", "ssh");
        hijack.put("ssh", Map.of("host", "127.0.0.1", "command", "id"));

        ToolExecutionResult result = runNode(interactiveChat(), "decision", hijack);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("identifies the node itself");
    }

    @Test
    @DisplayName("Should refuse to run anywhere the user cannot be asked to authorize it")
    void shouldRefuseUnattendedContext() {
        // What an agent node inside a scheduled workflow run looks like: a conversation id and a
        // stream, but no card, so nobody to ask.
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("conversationId", "it-conversation");
        credentials.put("__streamId__", "it-stream");
        credentials.put("__workflowRunId__", "it-run");
        ToolExecutionContext unattended = new ToolExecutionContext("it-tenant", credentials,
                Map.of(), Set.of(), null, null, null, null);

        ToolExecutionResult result = runNode(unattended, "transform", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("no one to ask");
    }

    @Test
    @DisplayName("Should refuse a node type whose meaning is the graph around it")
    void shouldRefuseDagType() {
        ToolExecutionResult result = runNode(interactiveChat(), "merge", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("no meaning on its own");
    }

    @Test
    @DisplayName("Should echo the configuration back with credentials removed")
    void shouldEchoRedactedConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mappings", List.of(Map.of("label", "x", "expression", "'1'")));
        config.put("password", "hunter2");

        ToolExecutionResult result = runNode(interactiveChat(), "transform", config);

        String rendered = String.valueOf(result.data()) + result.error();
        assertThat(rendered)
                .as("an inline secret must never come back out into the conversation")
                .doesNotContain("hunter2");
    }
}
