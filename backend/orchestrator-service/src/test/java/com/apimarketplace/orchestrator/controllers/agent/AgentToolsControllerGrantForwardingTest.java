package com.apimarketplace.orchestrator.controllers.agent;

import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A grant that does not reach the tool is not a grant.
 *
 * <p>This controller rebuilds the credentials map key by key from the request
 * body, so a key it does not copy is simply gone by the time a tool reads it,
 * and a tool that finds no stated permissions treats the caller as allowed. The
 * agent id was copied here from the start; the module set that says what that
 * agent may DO was not, so the first version of the generation gate enforced
 * nothing on this path while looking correct at both ends.
 *
 * <p>These pin the forwarding, next to the agent id it travels with, because
 * the two are only useful together: who, and what they may do.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the calling agent's grant reaches the tool, not just its id")
class AgentToolsControllerGrantForwardingTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;
    private AgentToolsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentToolsController(registry, registrationService);
    }

    private Map<String, Object> credentialsFor(Map<String, Object> extraBody) {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "workflow");
        body.put("parameters", Map.of("action", "list"));
        body.putAll(extraBody);

        controller.executeTool(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeTool(eq("workflow"), any(), ctx.capture());
        return ctx.getValue().credentials();
    }

    @Test
    @DisplayName("the module set is forwarded, so a tool can tell what the caller may spend on")
    void forwardsTheGrant() {
        Map<String, Object> credentials = credentialsFor(Map.of(
                "agentId", "agent-1",
                "enabledModules", List.of("catalog", "workflow", "generation")));

        assertThat(credentials)
                .as("without this the generation gate enforces nothing on the bridge path")
                .containsEntry(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY,
                        List.of("catalog", "workflow", "generation"));
        assertThat(AgentModuleResolver.callerMayUse(credentials, "generation")).isTrue();
    }

    @Test
    @DisplayName("a caller WITHOUT the module is forwarded as such, which is what makes the gate bite")
    void forwardsARestrictedGrant() {
        Map<String, Object> credentials = credentialsFor(Map.of(
                "agentId", "agent-1",
                "enabledModules", List.of("catalog", "workflow")));

        assertThat(AgentModuleResolver.callerMayUse(credentials, "generation")).isFalse();
    }

    @Test
    @DisplayName("a body that states no modules leaves the key absent, so older callers keep working")
    void absentGrantStaysAbsent() {
        // Absence must stay absence rather than becoming an empty list: an
        // empty list is a real statement (a tool-less agent) and denies, while
        // absence means "this caller predates the key" and must not.
        Map<String, Object> credentials = credentialsFor(Map.of("agentId", "agent-1"));

        assertThat(credentials).doesNotContainKey(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY);
        assertThat(AgentModuleResolver.callerMayUse(credentials, "generation")).isTrue();
    }
}
