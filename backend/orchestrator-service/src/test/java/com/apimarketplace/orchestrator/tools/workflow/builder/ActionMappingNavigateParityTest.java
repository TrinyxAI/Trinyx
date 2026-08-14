package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.tools.interface_.InterfaceNodeConfig;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.CreatorBase;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.InterfaceNodeCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The create path and the modify path must answer the SAME thing about an action mapping,
 * and the add_node response must carry no warning at all for a valid page switch.
 *
 * <p>Two failures this pins, both of which have already happened:
 *
 * <ol>
 *   <li><b>Divergence.</b> The two validators are near-clones. When only the create one
 *       learned that a `navigate` event resolves against interfaces, `add_node` accepted a
 *       legacy `trigger:&lt;page&gt;:navigate` while `modify` still answered "trigger
 *       '&lt;page&gt;' not found" - on the very path the agent is told to use to FIX a
 *       mapping, so it would go and "repair" correct data.</li>
 *   <li><b>Composition.</b> The add_node response is the UNION of the reference check and
 *       the cross-DAG check, and the second used to be masked by the first (it skipped
 *       labels the first had already flagged). Fixing the first unmasked the second, which
 *       then answered "trigger '&lt;page&gt;' belongs to a different DAG" about a page.
 *       Testing either half alone cannot see that.</li>
 * </ol>
 */
@DisplayName("Action mapping - navigate parity across the create and modify paths")
class ActionMappingNavigateParityTest {

    private static WorkflowBuilderSession session() {
        WorkflowBuilderSession session = WorkflowBuilderSession.create("tenant-1", "conv-1", "Test", "desc");
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Start");
        trigger.put("type", "form");
        session.getTriggers().add(trigger);
        for (String label : List.of("Home", "Details")) {
            Map<String, Object> iface = new LinkedHashMap<>();
            iface.put("label", label);
            iface.put("type", "interface");
            session.getInterfaces().add(iface);
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", "trigger:start");
        edge.put("to", "interface:home");
        session.getEdges().add(edge);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static List<String> viaCreate(Map<String, String> actionMapping) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("label", "Home");
        params.put("html_template", "<div></div>");
        params.put("action_mapping", actionMapping);
        Method m = InterfaceNodeCreator.class.getDeclaredMethod(
                "checkActionMappingReferences", InterfaceNodeConfig.class, WorkflowBuilderSession.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(
                new InterfaceNodeCreator(null, null, null, null),
                InterfaceNodeConfig.fromParams(params), session());
    }

    @SuppressWarnings("unchecked")
    private static List<String> viaModify(Map<String, String> actionMapping) throws Exception {
        Method m = WorkflowBuilderModifier.class.getDeclaredMethod(
                "validateActionMappingReferences", Object.class, WorkflowBuilderSession.class);
        m.setAccessible(true);
        // The validator reads only its two arguments; the store is never touched.
        return (List<String>) m.invoke(new WorkflowBuilderModifier(null), actionMapping, session());
    }

    /** Everything the add_node response would carry: reference check UNION cross-DAG check. */
    @SuppressWarnings("unchecked")
    private static List<String> viaAddNodeResponse(Map<String, String> actionMapping) throws Exception {
        List<String> warnings = new ArrayList<>(viaCreate(actionMapping));
        Method extract = InterfaceNodeCreator.class.getDeclaredMethod(
                "extractFlaggedTriggerLabels", List.class);
        extract.setAccessible(true);
        Set<String> alreadyFlagged = (Set<String>) extract.invoke(null, warnings);
        warnings.addAll(CreatorBase.checkCrossDagReferences(
                actionMapping, "interface:home", session(), alreadyFlagged));
        return warnings;
    }

    @Test
    @DisplayName("Both paths accept a page switch under either prefix")
    void bothPathsAcceptAPageSwitch() throws Exception {
        for (String ref : List.of("interface:details:navigate", "trigger:details:navigate")) {
            Map<String, String> mapping = Map.of("#link", ref);
            assertThat(viaCreate(mapping)).as("create accepts %s", ref).isEmpty();
            assertThat(viaModify(mapping)).as("modify accepts %s", ref).isEmpty();
        }
    }

    @Test
    @DisplayName("Both paths reject a page switch to an interface that does not exist")
    void bothPathsRejectAMissingPage() throws Exception {
        Map<String, String> mapping = Map.of("#link", "trigger:ghost_page:navigate");

        assertThat(viaCreate(mapping)).singleElement().asString().contains("interface 'ghost_page' not found");
        assertThat(viaModify(mapping)).singleElement().asString().contains("interface 'ghost_page' not found");
    }

    @Test
    @DisplayName("Both paths still resolve a real trigger event against triggers")
    void bothPathsStillCheckTriggerEvents() throws Exception {
        assertThat(viaCreate(Map.of("#f", "trigger:start:submit"))).isEmpty();
        assertThat(viaModify(Map.of("#f", "trigger:start:submit"))).isEmpty();

        assertThat(viaCreate(Map.of("#f", "trigger:ghost:submit")))
                .singleElement().asString().contains("trigger 'ghost' not found");
        assertThat(viaModify(Map.of("#f", "trigger:ghost:submit")))
                .singleElement().asString().contains("trigger 'ghost' not found");
    }

    @Test
    @DisplayName("The add_node response carries NO warning for a page switch, once both checks have run")
    void addNodeResponseIsSilentForAPageSwitch() throws Exception {
        // The composition is the point: the reference check going quiet is what removed
        // the mask the cross-DAG check relied on, so only the union proves the agent sees
        // nothing.
        assertThat(viaAddNodeResponse(Map.of("#link", "trigger:details:navigate"))).isEmpty();
        assertThat(viaAddNodeResponse(Map.of("#link", "interface:details:navigate"))).isEmpty();
    }

    @Test
    @DisplayName("The add_node response still reports a trigger fire that reaches outside the DAG")
    void addNodeResponseStillReportsACrossDagFire() throws Exception {
        assertThat(viaAddNodeResponse(Map.of("#go", "trigger:start:submit"))).isEmpty();
        assertThat(viaAddNodeResponse(Map.of("#go", "trigger:ghost:submit")))
                .isNotEmpty();
    }
}
