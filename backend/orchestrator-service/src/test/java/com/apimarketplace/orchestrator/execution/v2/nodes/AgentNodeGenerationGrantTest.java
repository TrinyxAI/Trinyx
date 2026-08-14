package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The workflow {@code agent:} node is the PRODUCER of the module set that scopes the remote
 * core tool schemas AND of the system prompt that advertises them.
 *
 * <p><b>The defect these pin.</b> {@code buildAgentRequest} resolved the correct restricted
 * module set, built the PROMPT from it, and then sent {@code null} for the TOOLS whenever the
 * agent row had no {@code toolsConfig}. agent-service read that null as "unrestricted" and
 * handed back every core tool, so an unconfigured agent dropped into a workflow received the
 * credit-spending {@code generation} tool and could spend the customer's balance with no grant
 * set anywhere. One method, two answers to one question.
 *
 * <p>Kept in its own class because {@code AgentNodeModularPromptTest} is excluded from
 * compilation in the pom (its assertions predate the DefaultSystemPrompts rewrite), so anything
 * added there never runs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode - credit-spending opt-ins (generation / image_generation)")
class AgentNodeGenerationGrantTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private AgentClient mockAgentClient;
    @Mock private AgentConfigResolver mockAgentConfigResolver;

    private ExecutionContext context;
    private final String entityId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1",
            "item-1", 0,
            Map.of("user_input", "Hello"),
            mockPlan
        );
    }

    /** The full per-family grant block, so only the opt-in toggles vary between cases. */
    private Map<String, Object> grantsWith(String optInKey, Object value) {
        Map<String, Object> toolsConfig = new HashMap<>(Map.of(
            "mode", "all",
            "workflowsGrant", "all", "tablesGrant", "all", "interfacesGrant", "all",
            "agentsGrant", "all", "applicationsGrant", "all"));
        if (optInKey != null) {
            toolsConfig.put(optInKey, value);
        }
        return toolsConfig;
    }

    /** Run the node against a stored {@code toolsConfig} and capture the DTO it dispatches. */
    private AgentExecutionRequestDto requestFor(Map<String, Object> toolsConfig) {
        when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
            .thenReturn(toolsConfig);
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(new AgentExecutionResponseDto(
                true, "OK", "OK", List.of(), 1, Map.of(), null, 100L,
                "openai", "gpt-4o", List.of(), null, Map.of(),
                List.of(), List.of(), List.of(), null, null, null));

        AgentNode node = new AgentNode("agent:test", createAgent());
        node.acceptServices(ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .agentConfigResolver(mockAgentConfigResolver)
            .build());
        node.execute(context);

        ArgumentCaptor<AgentExecutionRequestDto> captor =
            ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(captor.capture());
        return captor.getValue();
    }

    /**
     * The CONSUMER's own computation, run on the module set this node produced:
     * {@code AgentRemoteExecutionService} and {@code CliAgentService} both rebuild the core
     * tool names exactly this way. Asserting it here is what makes a producer-side test cross
     * the seam - it is the tool list the model ends up with, not just a set a helper returned.
     */
    private Set<String> coreToolNamesFor(AgentExecutionRequestDto request) {
        return DefaultSystemPrompts
            .build(new LinkedHashSet<>(request.enabledModules()), false)
            .coreToolNames();
    }

    @Test
    @DisplayName("toolsConfig=null → the forwarded module set, and so the model's tool list, has NEITHER credit-spending tool")
    void nullToolsConfigGrantsNeither() {
        AgentExecutionRequestDto request = requestFor(null);

        assertThat(request.enabledModules())
            .as("pre-fix this was null, which agent-service read as 'every core tool'")
            .isNotNull()
            .doesNotContain("generation", "image_generation");
        assertThat(coreToolNamesFor(request))
            .doesNotContain("generation", "image_generation")
            .contains("catalog", "table", "workflow", "files");
    }

    @Test
    @DisplayName("toolsConfig=null → the system prompt does not advertise a tool the agent will not be given")
    void nullToolsConfigPromptAgreesWithToolList() {
        AgentExecutionRequestDto request = requestFor(null);

        assertThat(request.systemPrompt())
            .doesNotContain("- generation -")
            .doesNotContain("- image_generation -")
            .contains("- workflow -");
    }

    @Test
    @DisplayName("generation grant ON → the tool IS forwarded (the fix does not break the ON direction)")
    void generationGrantOnIsForwarded() {
        AgentExecutionRequestDto request = requestFor(grantsWith("generation", true));

        assertThat(request.enabledModules()).contains("generation");
        assertThat(coreToolNamesFor(request)).contains("generation");
        assertThat(request.systemPrompt()).contains("- generation -");
    }

    /**
     * A workflow agent node reads the agent's persisted toolsConfig. Rows still carrying
     * the retired grant are forwarded to the agent as-is, so the node must resolve them
     * to nothing rather than to the unified generation tool, which also reaches
     * per-second video models.
     */
    @Test
    @DisplayName("a retired imageGeneration grant (boolean shape) forwards no module and no tool")
    void retiredImageGenerationGrantBooleanForwardsNothing() {
        AgentExecutionRequestDto request = requestFor(grantsWith("imageGeneration", true));

        assertThat(request.enabledModules()).doesNotContain("image_generation", "generation");
        assertThat(coreToolNamesFor(request)).doesNotContain("image_generation", "generation");
    }

    @Test
    @DisplayName("a retired imageGeneration grant (object shape) forwards no module and no tool")
    void retiredImageGenerationGrantObjectForwardsNothing() {
        AgentExecutionRequestDto request = requestFor(grantsWith("imageGeneration", Map.of("enabled", true)));

        assertThat(request.enabledModules()).doesNotContain("image_generation", "generation");
        assertThat(coreToolNamesFor(request)).doesNotContain("image_generation", "generation");
    }

    @Test
    @DisplayName("generation ON does not widen into the retired image_generation")
    void generationDoesNotWidenIntoImageGeneration() {
        assertThat(requestFor(grantsWith("generation", true)).enabledModules())
            .contains("generation").doesNotContain("image_generation");
    }

    @Test
    @DisplayName("grant explicitly OFF → the module is withheld")
    void grantOffIsWithheld() {
        AgentExecutionRequestDto request =
            requestFor(grantsWith("generation", Map.of("enabled", false)));

        assertThat(request.enabledModules()).doesNotContain("generation");
    }

    @Test
    @DisplayName("prompt and tool list are built from ONE answer: the routing table advertises generation iff the module set grants it")
    void promptAndToolListAgreeWhenGranted() {
        AgentExecutionRequestDto granted = requestFor(grantsWith("generation", true));

        assertThat(granted.enabledModules().contains("generation")).isTrue();
        assertThat(granted.systemPrompt().contains("- generation -")).isTrue();
        assertThat(coreToolNamesFor(granted).contains("generation")).isTrue();
    }

    @Test
    @DisplayName("prompt and tool list agree when NOT granted too")
    void promptAndToolListAgreeWhenUngranted() {
        AgentExecutionRequestDto ungranted = requestFor(grantsWith(null, null));

        assertThat(ungranted.enabledModules().contains("generation")).isFalse();
        assertThat(ungranted.systemPrompt().contains("- generation -")).isFalse();
        assertThat(coreToolNamesFor(ungranted).contains("generation")).isFalse();
    }

    @Test
    @DisplayName("mode=off still forwards an EMPTY module set (zero tools) - the always-send change must not turn it into 'everything'")
    void modeOffStillForwardsAnEmptyModuleSet() {
        Map<String, Object> toolsConfig = new HashMap<>();
        toolsConfig.put("mode", "off");

        AgentExecutionRequestDto request = requestFor(toolsConfig);

        assertThat(request.enabledModules()).isNotNull().isEmpty();
        assertThat(coreToolNamesFor(request)).isEmpty();
    }

    private Agent createAgent() {
        return new Agent(
            "agent-1", "agent", "Test Agent", entityId,
            false, "openai", "gpt-4o", null, "Do something",
            0.7, 4096, 10, 5,
            List.of(), null, Map.of(), List.of(), null, List.of(), null, null);
    }
}
