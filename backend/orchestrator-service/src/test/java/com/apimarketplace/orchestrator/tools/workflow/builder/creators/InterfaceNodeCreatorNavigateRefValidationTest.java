package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.orchestrator.tools.interface_.InterfaceNodeConfig;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@code checkActionMappingReferences} resolves a {@code navigate} action mapping.
 *
 * <p>A navigate event always targets an INTERFACE. The canonical ref is
 * {@code interface:<label>:navigate}, but the builder's Action Mappings dropdown used to
 * emit {@code trigger:<label>:navigate} for an interface target, so that shape sits in
 * published and cloned plans. Resolving it by prefix reported "trigger '&lt;page&gt;' not
 * found" for a page switch that works - a false warning that sends an agent off to
 * "fix" a correct mapping, or to rewrite it into something that really is broken.
 */
@DisplayName("InterfaceNodeCreator - navigate action mapping resolution")
class InterfaceNodeCreatorNavigateRefValidationTest {

    private static WorkflowBuilderSession sessionWith(String triggerLabel, String interfaceLabel) {
        WorkflowBuilderSession session = WorkflowBuilderSession.create("tenant-1", "conv-1", "Test", "desc");
        if (triggerLabel != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("label", triggerLabel);
            t.put("type", "form");
            session.getTriggers().add(t);
        }
        if (interfaceLabel != null) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("label", interfaceLabel);
            i.put("type", "interface");
            session.getInterfaces().add(i);
        }
        return session;
    }

    @SuppressWarnings("unchecked")
    private static List<String> check(WorkflowBuilderSession session, Map<String, String> actionMapping)
            throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("label", "Page");
        params.put("html_template", "<div></div>");
        params.put("action_mapping", actionMapping);
        InterfaceNodeConfig config = InterfaceNodeConfig.fromParams(params);

        Method m = InterfaceNodeCreator.class.getDeclaredMethod(
                "checkActionMappingReferences", InterfaceNodeConfig.class, WorkflowBuilderSession.class);
        m.setAccessible(true);
        // The method reads only the config and the session; its collaborators are never
        // touched, so nulls keep the test to the resolution rule under test.
        return (List<String>) m.invoke(new InterfaceNodeCreator(null, null, null, null), config, session);
    }

    @Test
    @DisplayName("Accepts the canonical interface-prefixed page switch")
    void acceptsCanonicalInterfaceNavigate() throws Exception {
        List<String> warnings = check(sessionWith(null, "Details"),
                Map.of("#link", "interface:details:navigate"));

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("Accepts a trigger-prefixed page switch: the builder emitted that shape, and it is persisted")
    void acceptsTriggerPrefixedNavigate() throws Exception {
        List<String> warnings = check(sessionWith(null, "Details"),
                Map.of("#link", "trigger:details:navigate"));

        // Pre-fix this resolved 'details' against TRIGGER labels and reported
        // "trigger 'details' not found" for a mapping that switches pages correctly.
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("Still reports a navigate whose target interface does not exist, whatever the prefix")
    void reportsNavigateToAMissingInterface() throws Exception {
        List<String> triggerPrefixed = check(sessionWith("Details", null),
                Map.of("#link", "trigger:ghost_page:navigate"));
        List<String> interfacePrefixed = check(sessionWith("Details", null),
                Map.of("#link", "interface:ghost_page:navigate"));

        // A trigger LABELLED "Details" must not make a navigate to a missing page resolve:
        // the relaxation is about which namespace to search, not about skipping the check.
        assertThat(triggerPrefixed).singleElement().asString().contains("interface 'ghost_page' not found");
        assertThat(interfacePrefixed).singleElement().asString().contains("interface 'ghost_page' not found");
    }

    @Test
    @DisplayName("Leaves the real trigger events resolving against triggers")
    void triggerEventsStillResolveAgainstTriggers() throws Exception {
        List<String> ok = check(sessionWith("Search", null),
                Map.of("#form", "trigger:search:submit"));
        List<String> missing = check(sessionWith("Search", null),
                Map.of("#form", "trigger:ghost:submit"));

        assertThat(ok).isEmpty();
        assertThat(missing).singleElement().asString().contains("trigger 'ghost' not found");
    }
}
