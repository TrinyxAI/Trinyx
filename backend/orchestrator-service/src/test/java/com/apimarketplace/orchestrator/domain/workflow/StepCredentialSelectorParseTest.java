package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stored plan reaches execution through this parser, so a field it does not
 * read does not exist at run time however carefully the rest of the chain
 * handles it.
 *
 * <p>Two spellings have to work, and it is not cosmetic: the builder writes
 * camelCase while an agent writing a plan by hand naturally writes snake_case, so
 * accepting only one makes the feature work from one surface and vanish from the
 * other, with no error on either.
 */
@DisplayName("WorkflowPlanParser - the run-time credential selector")
class StepCredentialSelectorParseTest {

    private static Map<String, Object> planWithStep(Map<String, Object> step) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", "11111111-1111-1111-1111-111111111111");
        plan.put("name", "Publisher");
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step);
        plan.put("mcps", steps);
        return plan;
    }

    private static Map<String, Object> baseStep() {
        Map<String, Object> step = new HashMap<>();
        step.put("id", "instagram/publish");
        step.put("label", "Publish");
        step.put("type", "mcp");
        return step;
    }

    private static Step parsedStep(Map<String, Object> step) {
        WorkflowPlan plan = WorkflowPlan.fromMap(planWithStep(step), "tenant-1");
        assertThat(plan.getMcps()).hasSize(1);
        return plan.getMcps().get(0);
    }

    @Test
    @DisplayName("reads the camelCase spelling the builder writes")
    void readsCamelCase() {
        Map<String, Object> step = baseStep();
        step.put("credentialSelector", "{{item.ig_account}}");

        assertThat(parsedStep(step).credentialSelector()).isEqualTo("{{item.ig_account}}");
    }

    @Test
    @DisplayName("reads the snake_case spelling an agent-written plan carries")
    void readsSnakeCase() {
        Map<String, Object> step = baseStep();
        step.put("credential_selector", "{{item.ig_account}}");

        assertThat(parsedStep(step).credentialSelector()).isEqualTo("{{item.ig_account}}");
    }

    @Test
    @DisplayName("a plan written before the field existed parses to no selector")
    void absentMeansStatic() {
        // The no-regression claim, at the parse layer: every stored plan today takes
        // this branch, and a non-null here would put all of them on the strict path.
        assertThat(parsedStep(baseStep()).credentialSelector()).isNull();
    }

    @Test
    @DisplayName("a blank selector is PRESENT, because dynamic-and-unfilled is not the same as static")
    void blankIsPresent() {
        // Collapsing the two is what made clearing the field in the builder fall
        // back to the auto-filled account pin: the mode was lost on the way out, the
        // picker returned on the way back in, and a multi-account step quietly
        // became a single-account one. Present-but-blank is carried so the run can
        // say so instead of guessing an account.
        Map<String, Object> step = baseStep();
        step.put("credentialSelector", "   ");

        Step parsed = parsedStep(step);
        assertThat(parsed.credentialSelector()).isEmpty();
        assertThat(parsed.hasCredentialSelector()).isTrue();
    }

    @Test
    @DisplayName("a step keeps its selector when its params are rewritten")
    void survivesWithParams() {
        // withParams is used on the execution path; dropping the selector there would
        // lose the account between parsing and running.
        Map<String, Object> step = baseStep();
        step.put("credentialSelector", "{{item.ig_account}}");

        Step rewritten = parsedStep(step).withParams(Map.of("caption", "hi"));

        assertThat(rewritten.credentialSelector()).isEqualTo("{{item.ig_account}}");
    }

    @Test
    @DisplayName("a NUMBER an agent wrote is read as a selector, not dropped")
    void numericSelectorIsRead() {
        // The documentation says a credential id also works, so an agent-built plan
        // can legitimately carry a number. It survives only because safeString
        // stringifies it; a stricter read here would drop it silently and the step
        // would run on the default account with nothing to show for it.
        Map<String, Object> step = baseStep();
        step.put("credentialSelector", 42);

        assertThat(parsedStep(step).credentialSelector()).isEqualTo("42");
    }
}
