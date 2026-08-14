package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code workflow(action='set_plan')} validation of the {@code generate} core type.
 *
 * <p>Without the {@code case "generate"} the exporter rejects every such plan as
 * "Unknown type 'generate'" even though add_node creates the node and the engine
 * runs it (the same regression class public_link and media each hit).
 *
 * <p>The validation is deliberately thin: a model must be named, and the
 * credential source must be one of the two real pools. Which parameters that
 * model accepts, and their bounds, are the generation catalog's answer, given
 * before the provider is called and therefore before anything is charged, so
 * re-checking them here could only produce a second, staler answer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderPlanExporter - set_plan validation of the generate core type")
class WorkflowBuilderPlanExporterGenerateValidationTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ToolSchemaFetcher toolSchemaFetcher;

    private WorkflowBuilderPlanExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher);
    }

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Make a clip")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Map<String, Object> manualTrigger() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("label", "start");
        t.put("type", "manual");
        return t;
    }

    private Map<String, Object> generateCore(Map<String, Object> params) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "g1");
        core.put("type", "generate");
        core.put("label", "Make Clip");
        if (params != null) {
            core.put("params", new LinkedHashMap<>(params));
        }
        return core;
    }

    private ToolExecutionResult setPlan(WorkflowBuilderSession session, List<Map<String, Object>> cores) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(manualTrigger())));
        plan.put("cores", new ArrayList<>(cores));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);
        return exporter.executeSetPlan(session, parameters);
    }

    @Test
    @DisplayName("a generate core with a model passes validation and lands in the session")
    void generateWithModelImportsSuccessfully() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(generateCore(Map.of(
                        "model", "seedance-2.0-fast",
                        "prompt", "a paper boat in a rain gutter",
                        "duration_seconds", 5))));

        assertThat(result.success())
                .as("a well-formed generate core must be accepted, got: " + result.error())
                .isTrue();
        assertThat(session.getCores()).hasSize(1);
        Map<String, Object> imported = session.getCores().get(0);
        assertThat(imported.get("type")).isEqualTo("generate");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) imported.get("params");
        assertThat(params)
                .containsEntry("model", "seedance-2.0-fast")
                .containsEntry("duration_seconds", 5);
    }

    @Test
    @DisplayName("a generate core WITHOUT a params map is rejected with the dedicated model error, not Unknown type")
    void generateWithoutParamsRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(generateCore(null)));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'model'");
        assertThat(result.error()).doesNotContain("Unknown type");
    }

    @Test
    @DisplayName("a blank model is rejected, and the error says how to find a real model id")
    void blankModelRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(generateCore(Map.of("model", "   ", "prompt", "hello"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("generation(action='models')");
    }

    @Test
    @DisplayName("an unknown credential_source is rejected, naming both real pools")
    void unknownCredentialSourceRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(generateCore(Map.of("model", "seedance-2.0-fast", "credential_source", "borrowed"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'platform'").contains("'user'");
    }

    @Test
    @DisplayName("a credential_id that is not an id is rejected, rather than silently running on another key")
    void unusableCredentialIdRejected() {
        // The executor reads an unusable id as "no pin" and falls back to the
        // owner's default key WITHOUT saying so, so a plan that appears to name
        // a key would quietly run on a different one. Refusing at build time is
        // what keeps the plan and the run telling the same story.
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(generateCore(Map.of(
                "model", "seedance-2.0-fast", "credential_source", "user", "credential_id", "not-an-id"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("credential_id");
        // The message has to say what to DO, and the answer is never "pick one":
        // no action here lists the owner's keys.
        assertThat(result.error()).contains("cannot choose one");
    }

    @Test
    @DisplayName("a real credential_id is accepted and KEPT, so an agent can rewrite a node without dropping the owner's choice")
    void aRealCredentialIdSurvives() {
        // Asserting only that the import succeeded would pass against an
        // exporter that quietly deleted the field, which is exactly the
        // regression the surrounding work exists to prevent: the node would run
        // on the account's default key and the plan would no longer say
        // otherwise.
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(generateCore(Map.of(
                "model", "seedance-2.0-fast", "credential_source", "user", "credential_id", 42))));

        assertThat(result.success()).isTrue();
        assertThat(session.getCores()).hasSize(1);
        Object params = session.getCores().get(0).get("params");
        assertThat(params).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> generateParams = (Map<String, Object>) params;
        assertThat(generateParams).containsEntry("credential_id", 42);
    }

    @Test
    @DisplayName("a parameter the exporter knows nothing about is ACCEPTED: the catalog is what judges it")
    void unknownGenerationParameterIsAccepted() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(generateCore(Map.of("model", "seedance-2.0-fast", "some_new_dimension", "v"))));

        assertThat(result.success())
                .as("rejecting here would block a dimension a newer seed added, for a model that accepts it")
                .isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) session.getCores().get(0).get("params");
        assertThat(params).containsEntry("some_new_dimension", "v");
    }
}
