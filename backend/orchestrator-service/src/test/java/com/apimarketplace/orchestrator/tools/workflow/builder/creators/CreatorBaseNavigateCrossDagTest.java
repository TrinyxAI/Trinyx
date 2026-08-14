package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-DAG check must not speak about page switches.
 *
 * <p>It fires for a {@code trigger:}-prefixed ref whose label is not a trigger of this
 * DAG. The builder UI emitted {@code trigger:<page>:navigate} for a page switch, so this
 * check saw a PAGE and answered "trigger '&lt;page&gt;' belongs to a different DAG" - an
 * assertion about something that is not a trigger at all.
 *
 * <p>It used to be masked: the sibling reference check flagged the same ref as "trigger
 * not found" first, and its labels were passed here to suppress a double warning. Once
 * that check correctly stopped flagging navigate refs, the mask went with it and this
 * warning surfaced on the add_node response. The two are coupled, so this test keeps the
 * coupling from resurfacing as a user-visible false warning.
 */
@DisplayName("CreatorBase - cross-DAG check vs navigate refs")
class CreatorBaseNavigateCrossDagTest {

    /** A session where "Start" (a form trigger) feeds the interface "Home". */
    private static WorkflowBuilderSession sessionWithStartFeedingHome() {
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

    private static List<String> check(Map<String, String> actionMapping) {
        return CreatorBase.checkCrossDagReferences(
                actionMapping, "interface:home", sessionWithStartFeedingHome(), Set.of());
    }

    @Test
    @DisplayName("Says nothing about a page switch, whichever prefix carries it")
    void ignoresNavigateRefs() {
        // 'details' is an INTERFACE label. Pre-fix the trigger-prefixed form produced
        // "trigger 'details' belongs to a different DAG" on a mapping that works.
        assertThat(check(Map.of("#link", "trigger:details:navigate"))).isEmpty();
        assertThat(check(Map.of("#link", "interface:details:navigate"))).isEmpty();
    }

    @Test
    @DisplayName("Still reports a real trigger fire that reaches outside this DAG")
    void stillReportsACrossDagTriggerFire() {
        List<String> warnings = check(Map.of("#go", "trigger:other_dag_form:submit"));

        assertThat(warnings).singleElement().asString().contains("different DAG");
    }

    @Test
    @DisplayName("Leaves a trigger of this DAG alone")
    void acceptsATriggerOfThisDag() {
        assertThat(check(Map.of("#go", "trigger:start:submit"))).isEmpty();
    }
}
