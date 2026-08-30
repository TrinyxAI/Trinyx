package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeExecutionService;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * The batch parameter must not collide with a name other actions of the same tool already use.
 *
 * <p>Parameters are declared on the `workflow` TOOL, not per action, and the registration pipeline
 * coerces every argument against its declared type before any action sees it. So declaring an
 * array named `items` would have re-typed the `items` that `add_node` has always taken as a STRING
 * expression for split, filter, sort, limit and set: the coercer turns
 * {@code items='{{mcp:x.output.y}}'} into a one-element list, the creator reads only strings, and
 * `add_node type='split'` then fails with "'items' is REQUIRED" while naming a parameter the agent
 * did supply.
 *
 * <p>This test pins the separation by name so the collision cannot come back through a rename.
 */
@ExtendWith(MockitoExtension.class)
class RunNodeBatchParamNameTest {

    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider workflowHelpProvider;

    private AgentToolDefinition tool;

    @BeforeEach
    void setUp() {
        lenient().when(nodeLibraryService.getQuickReference()).thenReturn("quick-ref");
        lenient().when(nodeLibraryService.getAlwaysAvailableHelp()).thenReturn("full-help");
        tool = new WorkflowBuilderToolDefinitionFactory(nodeLibraryService).buildToolDefinition();
    }

    private Optional<ToolParameter> param(String name) {
        return tool.parameters().stream().filter(p -> name.equals(p.name())).findFirst();
    }

    @Test
    @DisplayName("the batch parameter is run_inputs, an array")
    void batchParameterIsRunInputs() {
        assertThat(param("run_inputs")).isPresent();
        assertThat(param("run_inputs").get().type()).isEqualTo("array");
    }

    @Test
    @DisplayName("no array named 'items' is declared, because add_node takes items as a STRING expression")
    void itemsIsNotDeclaredAsAnArray() {
        // The creators that read a top-level `items` do so through a string accessor. If the tool
        // ever declares `items` as an array, the coercer converts the expression to a list, the
        // string read returns nothing, and add_node refuses a parameter the agent did pass.
        assertThat(param("items"))
                .as("declaring `items` as an array breaks add_node for split/filter/sort/limit/set")
                .isEmpty();
    }

    @Test
    @DisplayName("the batch parameter sits beside run_input, not on top of it")
    void runInputStillExistsSeparately() {
        assertThat(param("run_input")).isPresent();
        assertThat(param("run_input").get().type()).isEqualTo("object");
    }

    @Test
    @DisplayName("the numbers the agent is told match the constants the code enforces")
    void theHelpDoesNotDriftFromTheLimits() {
        // The agent sizes its batches from these sentences. If a constant moves and the text does
        // not, the agent is told a limit that is no longer true and finds out by being refused.
        String description = param("run_inputs").orElseThrow().description();

        assertThat(description)
                .contains("1 to " + AdHocNodeExecutionService.MAX_BATCH_ITEMS + " entries")
                .contains("Up to " + AdHocNodeExecutionService.MAX_BATCH_PARALLELISM + " entries run at once");

        // The action prose is a second surface an agent reads before any help call, and the help
        // module is a third. Pinning one of the three leaves the other two free to drift.
        String actionProse = param("action").orElseThrow().description();
        assertThat(actionProse)
                .contains("up to " + AdHocNodeExecutionService.MAX_BATCH_ITEMS + " inputs");

        String moduleHelp = String.valueOf(new WorkflowBuilderHelpModule(workflowHelpProvider)
                .execute("help", java.util.Map.of(), "tenant-1", null)
                .orElseThrow()
                .data());
        assertThat(moduleHelp)
                .contains("1 to " + AdHocNodeExecutionService.MAX_BATCH_ITEMS + " entries")
                .contains("Up to " + AdHocNodeExecutionService.MAX_BATCH_PARALLELISM + " entries run at once");

        // The budget is the number that decides whether a batch of 20 mostly comes back
        // NOT_STARTED, so it is the one an agent most needs and the one it cannot look up.
        assertThat(description)
                .contains(AdHocNodeExecutionService.TIMEOUT_SECONDS + "-second budget");
        assertThat(moduleHelp)
                .contains(AdHocNodeExecutionService.TIMEOUT_SECONDS + "-second budget");
    }

    @Test
    @DisplayName("the schema a provider validates against says run_inputs carries objects, matching its own prose")
    void theEmittedSchemaAgreesWithTheDescription() {
        // ToolParameter.type() is the layer ABOVE the one that matters: what a strict provider
        // checks the call against is the JSON Schema, and every array parameter used to be emitted
        // as an array of STRINGS. run_inputs is the first that carries objects, so a schema saying
        // otherwise can get the call refused or its entries stringified before the tool is reached
        // - and the description telling the agent to "send real objects" cannot override it.
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> runInputs = (Map<String, Object>) properties.get("run_inputs");

        assertThat(runInputs).containsEntry("type", "array");
        assertThat(runInputs.get("items")).isEqualTo(Map.of("type", "object"));

        // And the change is a no-op for the array parameters that were always strings, which is
        // what makes it safe to make in a shared generator.
        @SuppressWarnings("unchecked")
        Map<String, Object> interfaceIds = (Map<String, Object>) properties.get("interface_ids");
        assertThat(interfaceIds.get("items")).isEqualTo(Map.of("type", "string"));
    }

}
