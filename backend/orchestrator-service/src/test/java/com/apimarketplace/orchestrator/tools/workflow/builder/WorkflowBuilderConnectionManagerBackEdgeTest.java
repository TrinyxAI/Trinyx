package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The agent's route to a loop: {@code workflow(action='connect', ...)}.
 *
 * <p>An agent has no canvas, so it cannot see that it just drew a connection backwards. If the
 * loop-back marker depended on the agent remembering to declare it, a forgotten flag would be
 * wired as a dependency and the target would wait for its own descendant - a deadlock with no
 * error. So the connection is classified from the graph, and these tests pin that.
 */
@DisplayName("connect - loop-back detection for the agent")
class WorkflowBuilderConnectionManagerBackEdgeTest {

    private WorkflowBuilderConnectionManager manager;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        manager = new WorkflowBuilderConnectionManager(mock(WorkflowBuilderSessionStore.class));
        session = new WorkflowBuilderSession();
        session.setSessionId("s1");
        session.setTenantId("t1");

        session.getTriggers().add(mutable(Map.of("id", "trigger:start", "label", "Start", "type", "manual")));
        session.getMcps().add(mutable(Map.of("id", "mcp:fetch", "label", "Fetch")));
        session.getMcps().add(mutable(Map.of("id", "mcp:parse", "label", "Parse")));
        session.addConnection("trigger:start", "mcp:fetch", null);
        session.addConnection("mcp:fetch", "mcp:parse", null);
    }

    private static Map<String, Object> mutable(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private Map<String, Object> connect(String from, String to, Map<String, Object> extra) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", from);
        params.put("to", to);
        params.putAll(extra);
        ToolExecutionResult result = manager.executeConnect(session, params);
        assertTrue(result.success(), "connect should succeed: " + result);
        return findEdge(from, to);
    }

    private Map<String, Object> findEdge(String from, String to) {
        for (Map<String, Object> edge : new ArrayList<>(session.getEdges())) {
            String edgeFrom = (String) edge.get("from");
            if (edgeFrom != null && edgeFrom.startsWith(from) && to.equals(edge.get("to"))) {
                return edge;
            }
        }
        return null;
    }

    @Test
    @DisplayName("connecting back to an earlier node is recognised as a loop, unprompted")
    void connectingBackwardsIsRecognisedAsALoop() {
        Map<String, Object> edge = connect("mcp:parse", "mcp:fetch", Map.of());

        assertNotNull(edge);
        assertNotNull(edge.get("backEdge"),
            "Without the marker this edge is wired as a dependency and the loop deadlocks");
    }

    @Test
    @DisplayName("an ordinary forward connection is left alone")
    void forwardConnectionIsNotMarked() {
        session.getMcps().add(mutable(Map.of("id", "mcp:store", "label", "Store")));

        Map<String, Object> edge = connect("mcp:parse", "mcp:store", Map.of());

        assertNotNull(edge);
        assertNull(edge.get("backEdge"));
    }

    @Test
    @DisplayName("max_iterations caps this loop specifically")
    void maxIterationsIsCarried() {
        Map<String, Object> edge = connect("mcp:parse", "mcp:fetch", Map.of("max_iterations", 4));

        @SuppressWarnings("unchecked")
        Map<String, Object> marker = (Map<String, Object>) edge.get("backEdge");
        assertEquals(4, marker.get("maxIterations"));
    }

    @Test
    @DisplayName("a numeric string cap is accepted - an LLM often quotes its numbers")
    void numericStringCapIsAccepted() {
        Map<String, Object> edge = connect("mcp:parse", "mcp:fetch", Map.of("max_iterations", "4"));

        @SuppressWarnings("unchecked")
        Map<String, Object> marker = (Map<String, Object>) edge.get("backEdge");
        assertEquals(4, marker.get("maxIterations"),
            "Silently ignoring a quoted number would apply the default cap instead");
    }

    @Test
    @DisplayName("loop_back=false refuses the loop and keeps a plain connection")
    void loopBackFalseOptsOut() {
        Map<String, Object> edge = connect("mcp:parse", "mcp:fetch", Map.of("loop_back", false));

        assertNotNull(edge);
        assertNull(edge.get("backEdge"));
    }

    @Test
    @DisplayName("loop_back=true declares a loop the graph cannot infer yet")
    void loopBackTrueForcesTheMarker() {
        // Wiring the closing connection FIRST: nothing makes 'store' an ancestor yet, so
        // detection alone would miss it.
        session.getMcps().add(mutable(Map.of("id", "mcp:store", "label", "Store")));

        Map<String, Object> edge = connect("mcp:parse", "mcp:store", Map.of("loop_back", true));

        assertNotNull(edge.get("backEdge"));
    }

    @Test
    @DisplayName("the response tells the agent a loop was created and how it ends")
    void responseExplainsTheLoop() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", "mcp:parse");
        params.put("to", "mcp:fetch");
        ToolExecutionResult result = manager.executeConnect(session, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        String loopBack = (String) data.get("loop_back");
        assertNotNull(loopBack, "An agent cannot see the canvas: the loop must be stated back to it");
        assertTrue(loopBack.contains("repeats"), "Actual message: " + loopBack);
    }

    @Test
    @DisplayName("the marker survives into the saved plan, and the engine reads it back as a loop")
    void markerSurvivesPersistence() {
        connect("mcp:parse", "mcp:fetch", Map.of("max_iterations", 6));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> persisted =
            (List<Map<String, Object>>) session.buildPlanMap().get("edges");

        Map<String, Object> loop = persisted.stream()
            .filter(e -> "mcp:fetch".equals(e.get("to")) && ((String) e.get("from")).startsWith("mcp:parse"))
            .findFirst()
            .orElse(null);
        assertNotNull(loop);
        assertNotNull(loop.get("backEdge"),
            "Dropping the marker on save turns the loop back into a forward edge on reload");

        // And the round-trip closes: parsing that plan yields a loop the engine recognises.
        com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan plan =
            com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.fromMap(
                session.buildPlanMap(), "tenant");
        assertEquals(1,
            com.apimarketplace.orchestrator.domain.workflow.BackEdgeSpecs.forSource(plan, "mcp:parse").size());
        assertEquals(6,
            com.apimarketplace.orchestrator.domain.workflow.BackEdgeSpecs
                .forSource(plan, "mcp:parse").get(0).maxIterationsOverride());
    }
}
