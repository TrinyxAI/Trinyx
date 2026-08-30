package com.apimarketplace.agent.registry;

import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentToolDefinition record.
 */
@DisplayName("AgentToolDefinition")
class AgentToolDefinitionTest {

    private AgentToolDefinition createSampleTool() {
        ToolParameter queryParam = ToolParameter.builder()
                .name("query")
                .type("string")
                .description("Search query")
                .required(true)
                .build();

        ToolParameter limitParam = ToolParameter.builder()
                .name("limit")
                .type("integer")
                .description("Max results")
                .required(false)
                .enumValues(List.of("5", "10", "20"))
                .build();

        return AgentToolDefinition.builder()
                .name("catalog")
                .description("Search the API catalog")
                .category(ToolCategory.CATALOG)
                .parameters(List.of(queryParam, limitParam))
                .requiredParameters(List.of("query"))
                .inputSchema(Map.of("type", "object"))
                .outputSchema(Map.of("type", "array"))
                .examples(List.of("{\"query\":\"email\"}"))
                .helpText("Search for APIs by keyword")
                .requiresAuth(false)
                .tags(List.of("search", "discovery"))
                .build();
    }

    @Nested
    @DisplayName("isParameterRequired()")
    class IsParameterRequiredTests {

        @Test
        @DisplayName("should return true for required parameter")
        void shouldReturnTrueForRequired() {
            AgentToolDefinition tool = createSampleTool();
            assertThat(tool.isParameterRequired("query")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-required parameter")
        void shouldReturnFalseForNonRequired() {
            AgentToolDefinition tool = createSampleTool();
            assertThat(tool.isParameterRequired("limit")).isFalse();
        }

        @Test
        @DisplayName("should return false when requiredParameters is null")
        void shouldReturnFalseWhenNull() {
            AgentToolDefinition tool = AgentToolDefinition.builder()
                    .name("test").description("test").category(ToolCategory.UTILITY)
                    .build();
            assertThat(tool.isParameterRequired("any")).isFalse();
        }
    }

    @Nested
    @DisplayName("getParameter()")
    class GetParameterTests {

        @Test
        @DisplayName("should return parameter by name")
        void shouldReturnParameterByName() {
            AgentToolDefinition tool = createSampleTool();
            ToolParameter param = tool.getParameter("query");

            assertThat(param).isNotNull();
            assertThat(param.name()).isEqualTo("query");
            assertThat(param.type()).isEqualTo("string");
        }

        @Test
        @DisplayName("should return null for unknown parameter")
        void shouldReturnNullForUnknown() {
            AgentToolDefinition tool = createSampleTool();
            assertThat(tool.getParameter("nonexistent")).isNull();
        }

        @Test
        @DisplayName("should return null when parameters is null")
        void shouldReturnNullWhenParametersNull() {
            AgentToolDefinition tool = AgentToolDefinition.builder()
                    .name("test").description("test").category(ToolCategory.UTILITY)
                    .build();
            assertThat(tool.getParameter("any")).isNull();
        }
    }

    @Nested
    @DisplayName("toSummary()")
    class ToSummaryTests {

        @Test
        @DisplayName("should include all key fields")
        void shouldIncludeKeyFields() {
            AgentToolDefinition tool = createSampleTool();
            Map<String, Object> summary = tool.toSummary();

            assertThat(summary.get("name")).isEqualTo("catalog");
            assertThat(summary.get("description")).isEqualTo("Search the API catalog");
            assertThat(summary.get("category")).isEqualTo("catalog");
            assertThat(summary.get("requiresAuth")).isEqualTo(false);
            assertThat(summary.get("parameterCount")).isEqualTo(2);
        }

        @Test
        @DisplayName("should include parameters with details")
        void shouldIncludeParameterDetails() {
            AgentToolDefinition tool = createSampleTool();
            Map<String, Object> summary = tool.toSummary();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> params = (List<Map<String, Object>>) summary.get("parameters");
            assertThat(params).hasSize(2);
            assertThat(params.get(0).get("name")).isEqualTo("query");
            assertThat(params.get(0).get("required")).isEqualTo(true);
        }

        @Test
        @DisplayName("should carry an array's item type, since every consumer rebuilds its schema from this")
        void shouldCarryItemTypeOverTheWire() {
            // This payload is how the tool reaches the services that talk to a model, and each of
            // them builds its own JSON Schema from it. Dropping the item type here strands the
            // declaration at the tool: an array of objects is still advertised to the model as an
            // array of strings, however carefully the tool declared it.
            AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("t").description("T").category(ToolCategory.WORKFLOW)
                .parameters(List.of(
                    ToolParameter.builder().name("rows").type("array").description("Rows")
                        .required(false).itemType("object").build(),
                    ToolParameter.builder().name("tags").type("array").description("Tags")
                        .required(false).build()))
                .build();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> params =
                (List<Map<String, Object>>) tool.toSummary().get("parameters");

            assertThat(params.get(0)).containsEntry("itemType", "object");
            // Absent rather than "string": the readers default it, and an always-present key
            // would change every existing tool's payload for nothing.
            assertThat(params.get(1)).doesNotContainKey("itemType");
        }

        @Test
        @DisplayName("should include enum values in parameters when present")
        void shouldIncludeEnumValues() {
            AgentToolDefinition tool = createSampleTool();
            Map<String, Object> summary = tool.toSummary();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> params = (List<Map<String, Object>>) summary.get("parameters");
            Map<String, Object> limitParam = params.get(1);
            assertThat(limitParam).containsKey("enum");
        }
    }

    @Nested
    @DisplayName("toMcpFormat()")
    class ToMcpFormatTests {

        @Test
        @DisplayName("should return MCP tool format")
        void shouldReturnMcpFormat() {
            AgentToolDefinition tool = createSampleTool();
            Map<String, Object> mcp = tool.toMcpFormat();

            assertThat(mcp.get("name")).isEqualTo("catalog");
            assertThat(mcp.get("description")).isEqualTo("Search the API catalog");
            assertThat(mcp).containsKey("inputSchema");
        }

        @Test
        @DisplayName("should use default schema when inputSchema is null")
        void shouldUseDefaultSchema() {
            AgentToolDefinition tool = AgentToolDefinition.builder()
                    .name("test").description("test").category(ToolCategory.UTILITY)
                    .build();
            Map<String, Object> mcp = tool.toMcpFormat();

            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) mcp.get("inputSchema");
            assertThat(schema.get("type")).isEqualTo("object");
        }
    }
}
