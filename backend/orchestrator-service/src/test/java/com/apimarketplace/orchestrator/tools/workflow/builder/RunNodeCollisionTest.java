package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeExecutionService;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeRequest;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeResult;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * THE test for run_node.
 *
 * <p>{@code add_node} builds a node config by flattening the whole top-level argument map into
 * it, TOP-LEVEL WINNING ({@code merged.putIfAbsent(nested)}). The workflow tool declares ~40
 * top-level parameters, and a dozen of them collide by name with real node config keys: {@code to}
 * is a send_email recipient, {@code workflow_id} a sub_workflow target, {@code query} a SQL
 * statement, {@code timeout_seconds} a node timeout. On a draft that corrupts something you can
 * still inspect. On {@code run_node} the node executes immediately, so the same collision sends
 * mail to the wrong address or runs the wrong statement, and there is nothing to inspect
 * afterwards.
 *
 * <p>run_node therefore reads its configuration from {@code params} and from nowhere else. These
 * tests fire every declared top-level parameter at it alongside a {@code params} carrying the same
 * key, and assert the node received the value from {@code params}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunNodeCollisionTest {

    @Mock private AdHocNodeExecutionService adHocNodeExecutionService;
    @Mock private NodeTypeSearchService nodeTypeSearchService;
    @Mock private WorkflowBuilderResultEnricher resultEnricher;
    @Mock private WorkflowBuilderLogger buildLogger;

    private WorkflowBuilderProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = newProviderWithOnly(adHocNodeExecutionService, nodeTypeSearchService);
        setField(provider, "resultEnricher", resultEnricher);
        setField(provider, "buildLogger", buildLogger);
        when(nodeTypeSearchService.isNodeTypeEnabled(any())).thenReturn(true);
        // Pass-through so the test sees the dispatch result rather than the enricher's.
        lenient().when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(adHocNodeExecutionService.execute(any()))
                .thenReturn(new AdHocNodeResult("http_request", AdHocNodeResult.COMPLETED,
                        Map.of("ok", true), null, 12L, null));
    }

    /**
     * The provider has a long constructor of collaborators this test does not exercise. Building
     * it reflectively keeps the test focused on the collision rule and stops it from breaking
     * every time an unrelated collaborator is added.
     */
    private static WorkflowBuilderProvider newProviderWithOnly(AdHocNodeExecutionService adHoc,
                                                               NodeTypeSearchService nodeTypes) throws Exception {
        java.lang.reflect.Constructor<?> ctor = WorkflowBuilderProvider.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        WorkflowBuilderProvider p = (WorkflowBuilderProvider) ctor.newInstance(args);
        setField(p, "adHocNodeExecutionService", adHoc);
        setField(p, "nodeTypeSearchService", nodeTypes);
        return p;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = WorkflowBuilderProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ToolExecutionContext conversationCtx() {
        return new ToolExecutionContext("tenant-1",
                Map.of("conversationId", "conv-1", "__streamId__", "stream-1"),
                Map.of(), java.util.Set.of(), null, null, "org-1", "OWNER");
    }

    private AdHocNodeRequest captureRequest(Map<String, Object> params) {
        ToolExecutionResult result = provider.execute("workflow", params, conversationCtx());
        assertThat(result).isNotNull();
        ArgumentCaptor<AdHocNodeRequest> captor = ArgumentCaptor.forClass(AdHocNodeRequest.class);
        org.mockito.Mockito.verify(adHocNodeExecutionService).execute(captor.capture());
        return captor.getValue();
    }

    /**
     * The four collisions with real teeth, named so a failure says which one broke.
     *
     * <p>`to` / send_email: mail to the wrong recipient. `workflow_id` / sub_workflow: a different
     * workflow executed. `query` / database: the wrong statement run. `timeout_seconds`: a node
     * silently given the tool's timeout.
     */
    @ParameterizedTest(name = "top-level ''{0}'' must not overwrite the node config")
    @CsvSource({
            "to,             send_email,   victim@example.com, intended@example.com",
            // sub_workflow is refused outright now (it fires a real child run), so this row proves the
            // same rule on a type that still executes.
            "workflow_id,    code,         wrong-workflow-id,  intended-workflow-id",
            "query,          database,     DROP TABLE users,   SELECT 1",
            "timeout_seconds,code,         999,                5",
            "from,           send_email,   wrong@example.com,  right@example.com",
            "name,           code,         wrong-name,         intended-name",
            "description,    code,         wrong desc,         intended desc",
            "limit,          code,         999,                7",
            "offset,         code,         999,                3",
            "title,          code,         wrong title,        intended title",
            "data,           code,         wrong-data,         intended-data"
    })
    @DisplayName("Should read node config from params only, never from a top-level parameter")
    void shouldNotLetTopLevelOverwriteNodeConfig(String collidingKey, String nodeType,
                                                 String topLevelValue, String intendedValue) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "run_node");
        params.put("type", nodeType);
        params.put(collidingKey, topLevelValue);
        params.put("params", new LinkedHashMap<>(Map.of(collidingKey, intendedValue)));

        AdHocNodeRequest request = captureRequest(params);

        assertThat(request.config().get(collidingKey))
                .as("'%s' from the tool surface must never reach the node config", collidingKey)
                .isEqualTo(intendedValue);
    }

    @Test
    @DisplayName("Should ignore a top-level key entirely when params does not declare it")
    void shouldNotSmuggleTopLevelKeysIntoConfig() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "run_node");
        params.put("type", "code");
        params.put("to", "smuggled@example.com");
        params.put("query", "DROP TABLE users");
        params.put("params", new LinkedHashMap<>(Map.of("code", "return 1;")));

        AdHocNodeRequest request = captureRequest(params);

        assertThat(request.config()).containsOnlyKeys("code");
        assertThat(request.config()).doesNotContainKeys("to", "query", "action", "type");
    }

    @Test
    @DisplayName("Should reject a params sent as a JSON string instead of running an empty node")
    void shouldRejectStringParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "run_node");
        params.put("type", "code");
        params.put("params", "{\"code\": \"return 1;\"}");

        ToolExecutionResult result = provider.execute("workflow", params, conversationCtx());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("must be an object");
        org.mockito.Mockito.verify(adHocNodeExecutionService, org.mockito.Mockito.never()).execute(any());
    }

    @Test
    @DisplayName("Should accept 'parameters' as an alias of 'params', like add_node does")
    void shouldAcceptParametersAlias() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "run_node");
        params.put("type", "code");
        params.put("parameters", new LinkedHashMap<>(Map.of("code", "return 42;")));

        AdHocNodeRequest request = captureRequest(params);

        assertThat(request.config().get("code")).isEqualTo("return 42;");
    }
}
