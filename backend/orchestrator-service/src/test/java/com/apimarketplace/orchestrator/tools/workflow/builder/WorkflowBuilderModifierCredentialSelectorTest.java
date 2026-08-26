package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent surface for choosing an account at run time.
 *
 * <p>This is the shape of failure this project keeps meeting: a parameter the
 * documentation advertises and the creator never reads is silently dropped, and a
 * parameter routed into the tool params instead of onto the node is BOTH dropped
 * and forwarded to the provider as an argument it never declared. Neither shows
 * up as an error; the node is created, the run is green, and the account is the
 * default one. So the assertions here are mostly about WHERE the value lands.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("workflow(action='modify') - choosing the account at run time")
class WorkflowBuilderModifierCredentialSelectorTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
    }

    private WorkflowBuilderSession sessionWithPublishNode() {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Publisher")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "d67b712e-c76e-4bae-a17e-24fe7f73a589");
        node.put("type", "mcp");
        node.put("label", "Publish");
        node.put("params", new LinkedHashMap<>(Map.of("caption", "hello")));
        session.getMcps().add(node);
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishNode(WorkflowBuilderSession session) {
        return (Map<String, Object>) session.getMcps().stream()
                .filter(m -> "Publish".equals(m.get("label")))
                .findFirst().orElseThrow();
    }

    private ToolExecutionResult modify(WorkflowBuilderSession session, Map<String, Object> params) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Publish");
        args.put("params", params);
        return modifier.executeModifyNode(session, args);
    }

    @Test
    @DisplayName("the selector lands on the NODE, never among the tool params")
    void selectorStaysAtNodeLevel() {
        WorkflowBuilderSession session = sessionWithPublishNode();

        Map<String, Object> params = new HashMap<>();
        params.put("credentialSelector", "{{item.ig_account}}");
        assertThat(modify(session, params).success()).isTrue();

        Map<String, Object> node = publishNode(session);
        assertThat(node).containsEntry("credentialSelector", "{{item.ig_account}}");
        // Buried in params it would be invisible to the plan parser AND sent to the
        // provider as an undeclared argument.
        @SuppressWarnings("unchecked")
        Map<String, Object> toolParams = (Map<String, Object>) node.get("params");
        assertThat(toolParams).doesNotContainKey("credentialSelector");
        assertThat(toolParams).containsEntry("caption", "hello");
    }

    @Test
    @DisplayName("the snake_case spelling an agent naturally writes is normalised, not dropped")
    void snakeCaseIsNormalised() {
        WorkflowBuilderSession session = sessionWithPublishNode();

        Map<String, Object> params = new HashMap<>();
        params.put("credential_selector", "{{item.ig_account}}");
        assertThat(modify(session, params).success()).isTrue();

        Map<String, Object> node = publishNode(session);
        assertThat(node).containsEntry("credentialSelector", "{{item.ig_account}}");
        // Only the spelling the plan parser reads survives; leaving both would give
        // the node two fields that can disagree.
        assertThat(node).doesNotContainKey("credential_selector");
        @SuppressWarnings("unchecked")
        Map<String, Object> toolParams = (Map<String, Object>) node.get("params");
        assertThat(toolParams).doesNotContainKey("credential_selector");
    }

    @Test
    @DisplayName("an explicit null removes it, returning the step to a fixed account")
    void nullRemovesIt() {
        // A merge cannot express a deletion, so without this there is no way back to
        // a fixed account once a step has been made dynamic.
        WorkflowBuilderSession session = sessionWithPublishNode();
        publishNode(session).put("credentialSelector", "{{item.ig_account}}");

        Map<String, Object> params = new HashMap<>();
        params.put("credentialSelector", null);
        assertThat(modify(session, params).success()).isTrue();

        assertThat(publishNode(session)).doesNotContainKey("credentialSelector");
    }

    @Test
    @DisplayName("a step that says nothing about it keeps whatever it had")
    void untouchedStepKeepsItsSelector() {
        WorkflowBuilderSession session = sessionWithPublishNode();
        publishNode(session).put("credentialSelector", "{{item.ig_account}}");

        assertThat(modify(session, Map.of("caption", "updated")).success()).isTrue();

        assertThat(publishNode(session)).containsEntry("credentialSelector", "{{item.ig_account}}");
    }

    @Test
    @DisplayName("a null camelCase alongside a valued snake_case keeps the value, as add_node does")
    void nullCamelDoesNotBeatValuedSnake() {
        // The exact pair the two surfaces used to disagree on: add_node created the
        // step WITH the selector while modify deleted it. One input, two meanings.
        WorkflowBuilderSession session = sessionWithPublishNode();

        Map<String, Object> params = new HashMap<>();
        params.put("credentialSelector", null);
        params.put("credential_selector", "{{item.ig_account}}");
        assertThat(modify(session, params).success()).isTrue();

        assertThat(publishNode(session)).containsEntry("credentialSelector", "{{item.ig_account}}");
    }

    @Test
    @DisplayName("a blank is trimmed the way the add surface trims it")
    void blankIsTrimmed() {
        // Otherwise describe shows "   " through one door and "" through the other for
        // what is the same state.
        WorkflowBuilderSession session = sessionWithPublishNode();

        Map<String, Object> params = new HashMap<>();
        params.put("credentialSelector", "   ");
        assertThat(modify(session, params).success()).isTrue();

        assertThat(publishNode(session)).containsEntry("credentialSelector", "");
    }

    @Test
    @DisplayName("a node carrying the snake spelling can actually be cleared")
    void snakeSpellingOnTheNodeCanBeRemoved() {
        // set_plan imports node maps verbatim, so a session node can hold
        // credential_selector - which the plan parser reads as a LIVE selector. The
        // merger deletes by exact key, so before the spelling was normalised on the
        // node the delete reported success, removed a key that was not there, and left
        // the step still choosing an account at run time.
        WorkflowBuilderSession session = sessionWithPublishNode();
        publishNode(session).put("credential_selector", "{{item.ig_account}}");

        Map<String, Object> params = new HashMap<>();
        params.put("credentialSelector", null);
        assertThat(modify(session, params).success()).isTrue();

        assertThat(publishNode(session)).doesNotContainKey("credential_selector");
        assertThat(publishNode(session)).doesNotContainKey("credentialSelector");
    }

    @Test
    @DisplayName("describe shows a snake-spelled selector, so an agent can see what it must fix")
    void describeReadsTheSnakeSpelling() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "d67b712e-c76e-4bae-a17e-24fe7f73a589");
        node.put("type", "mcp");
        node.put("label", "Publish");
        node.put("credential_selector", "{{item.ig_account}}");

        NodeDescriptionBuilder.DescriptionResult described =
                new NodeDescriptionBuilder(null, null)
                        .buildDescription("mcp:publish", node, "tenant-1");

        assertThat(described.config()).containsEntry("credential_selector", "{{item.ig_account}}");
    }

    @Test
    @DisplayName("describe declares the field modifiable, not merely visible")
    void describeDeclaresItModifiable() {
        // An agent that can READ a value and is not told it can change it has to guess
        // that modify accepts it. The spelling has to match the one the node
        // documentation and every fix string use, or the agent reads two names for one
        // field and picks the wrong one.
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "d67b712e-c76e-4bae-a17e-24fe7f73a589");
        node.put("type", "mcp");
        node.put("label", "Publish");
        node.put("credentialSelector", "{{item.ig_account}}");

        NodeDescriptionBuilder.DescriptionResult described =
                new NodeDescriptionBuilder(null, null)
                        .buildDescription("mcp:publish", node, "tenant-1");

        assertThat(described.config()).containsEntry("credential_selector", "{{item.ig_account}}");
        assertThat(described.modifiableFields()).containsKey("credential_selector");
    }
}
