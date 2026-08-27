package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The add-node surface for choosing an account at run time.
 *
 * <p>Everything an `mcp` node is given that is not reserved becomes a TOOL
 * PARAMETER and is forwarded to the provider. So a selector that is not reserved
 * is wrong twice over: it never reaches the node (the plan parser reads it at step
 * level, not inside params), and it travels to the provider as an argument it
 * never declared. Neither shows up as an error. The node is created, the run is
 * green, and the account is the default one.
 *
 * <p>The reserved list is therefore load-bearing, and it is asserted here by
 * reflection rather than through a full session build, because what has to hold is
 * a property of the list itself.
 */
@DisplayName("McpCreator - the run-time account selector is reserved, not a tool param")
class McpCreatorCredentialSelectorTest {

    @SuppressWarnings("unchecked")
    private static Set<String> reservedParams() throws Exception {
        Field field = McpCreator.class.getDeclaredField("RESERVED_PARAMS");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    @Test
    @DisplayName("both spellings are reserved, so neither is forwarded to the provider")
    void bothSpellingsAreReserved() throws Exception {
        assertThat(reservedParams())
                .contains("credential_selector", "credentialSelector");
    }

    @Test
    @DisplayName("the reserved list still covers the fields it covered before")
    void previousReservationsSurvive() throws Exception {
        // The no-regression half: this list decides what reaches the provider for
        // EVERY catalog step, so losing an entry silently starts sending a field
        // upstream that was never meant to leave the platform.
        assertThat(reservedParams()).contains(
                "label", "name", "connect_after", "connect_after_loop", "interface_id",
                "type", "action", "session_id", "params", "parameters");
    }

    // ── what the creator actually DOES with the value ────────────────────────
    //
    // The reserved-list assertions above are necessary and not sufficient: they
    // would stay green if the lift itself regressed to dropping values. buildStepNode
    // is private, so it is reached by reflection rather than by standing up a whole
    // session - what has to hold is a property of that method, and the alternative is
    // no coverage of the fix at all.

    private static Map<String, Object> buildStepNode(Map<String, Object> parameters) throws Exception {
        java.lang.reflect.Method method = McpCreator.class.getDeclaredMethod(
                "buildStepNode", Map.class, String.class, String.class,
                Class.forName("com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession"));
        method.setAccessible(true);
        Object session = Class
                .forName("com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession")
                .getMethod("builder").invoke(null);
        Object built = session.getClass().getMethod("build").invoke(session);
        // Null collaborators on purpose: none of them is touched on the path under
        // test, and wiring five mocks would only hide which of them the method needs.
        McpCreator creator = new McpCreator(null, null, null, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) method.invoke(
                creator, parameters, "Publish", "instagram/publish", built);
        return node;
    }

    @Test
    @DisplayName("a NUMBER is kept, because the docs say an id works and modify accepts one")
    void numericValueIsKept() throws Exception {
        Map<String, Object> node = buildStepNode(new java.util.HashMap<>(Map.of(
                "label", "Publish", "credential_selector", 42)));

        assertThat(node).containsEntry("credentialSelector", "42");
    }

    @Test
    @DisplayName("a BLANK is kept, so dynamic-and-unfilled is expressible on this surface too")
    void blankValueIsKept() throws Exception {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("label", "Publish");
        params.put("credentialSelector", "   ");

        assertThat(buildStepNode(params)).containsEntry("credentialSelector", "");
    }

    @Test
    @DisplayName("camelCase wins over snake_case, the same way modify resolves the pair")
    void camelCaseWins() throws Exception {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("label", "Publish");
        params.put("credentialSelector", "{{item.camel}}");
        params.put("credential_selector", "{{item.snake}}");

        assertThat(buildStepNode(params)).containsEntry("credentialSelector", "{{item.camel}}");
    }

    @Test
    @DisplayName("a step that says nothing about it gets no selector at all")
    void absentStaysAbsent() throws Exception {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("label", "Publish");

        assertThat(buildStepNode(params)).doesNotContainKey("credentialSelector");
    }
}
