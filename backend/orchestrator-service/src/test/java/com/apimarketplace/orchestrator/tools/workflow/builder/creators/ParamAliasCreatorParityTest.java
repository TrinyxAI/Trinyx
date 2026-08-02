package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity guard between {@code NodeParamsValidator.PARAM_ALIASES} (what {@code add_node} ACCEPTS)
 * and what each creator actually READS.
 *
 * <h2>Why this exists</h2>
 *
 * <p>An alias the validator accepts but the creator never reads is worse than a rejected one: the
 * param sails through validation and is then silently dropped. Audit 2026-07-31 found four node
 * types out of parity, and the agent node was the dangerous shape - its prompt is OPTIONAL, so
 * {@code add_node(type='agent', params={instruction: '...'})} produced a successful agent node
 * with NO prompt, with no error anywhere. classify / guardrail / decision failed loudly instead,
 * but on a message naming a param the caller never used.
 *
 * <p>The reverse gap (creator reads a spelling the validator rejects) only costs an
 * {@code Unknown parameter} error, but it makes the help a liar - that is how the loop node's
 * {@code loopCondition} was rejected while the help advertised it.
 *
 * <h2>How to keep this green</h2>
 *
 * <p>The CREATOR is the source of truth for what is supported. When you add an alias to
 * {@code PARAM_ALIASES}, teach the matching creator to read it (see
 * {@link CreatorBase#firstNonBlank}) and add it below. Do not "fix" a failure by deleting the
 * expectation here.
 */
@DisplayName("Param alias parity - validator accepts only what creators read")
class ParamAliasCreatorParityTest {

    /**
     * Spellings each creator genuinely reads, transcribed from the creator source.
     * agent    -> AgentCreator: firstNonBlank(prompt, instruction, message, task, input)
     * classify -> ClassifyCreator: firstNonBlank(prompt, instruction, system_prompt, content, input, text, data)
     * guardrail-> GuardrailCreator: firstNonBlank(input, content, text) + firstNonBlank(prompt, system_prompt, instruction)
     * decision -> DecisionNodeCreator: conditions, decisionConditions, cases (tryRecoverConditions), branches, rules
     * loop     -> UtilityNodeCreator: condition/loopCondition/loop_condition/expression/while,
     *             max_iterations/maxIterations/limit
     * response -> UtilityNodeCreator: message, text, content, body, response
     */
    private static final Map<String, Set<String>> CREATOR_READS = Map.of(
        "agent", Set.of("prompt", "instruction", "message", "task", "input", "withMemory", "with_memory"),
        "classify", Set.of("prompt", "instruction", "system_prompt", "content", "input", "text", "data"),
        "guardrail", Set.of("input", "content", "text", "prompt", "system_prompt", "instruction"),
        "decision", Set.of("conditions", "decisionConditions", "cases", "condition", "branches", "rules"),
        "loop", Set.of("condition", "loopCondition", "loop_condition", "expression", "while",
                       "max_iterations", "maxIterations", "limit"),
        "response", Set.of("message", "text", "content", "body", "response")
    );

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> paramAliases() {
        try {
            Field f = NodeParamsValidator.class.getDeclaredField("PARAM_ALIASES");
            f.setAccessible(true);
            return (Map<String, Map<String, String>>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "PARAM_ALIASES moved or was renamed - this parity guard must be updated with it", e);
        }
    }

    @Nested
    @DisplayName("no silent drop")
    class NoSilentDrop {

        /**
         * The dangerous direction. Every alias the validator waves through must be a spelling the
         * creator reads, otherwise the param is accepted and lost.
         */
        @Test
        @DisplayName("regression: every accepted alias is read by its creator")
        void everyAcceptedAliasIsRead() {
            Map<String, Set<String>> unread = new LinkedHashMap<>();

            paramAliases().forEach((nodeType, aliases) -> {
                Set<String> reads = CREATOR_READS.get(nodeType);
                if (reads == null) {
                    return; // type not covered by this guard yet - see class javadoc
                }
                Set<String> missing = new TreeSet<>(aliases.keySet());
                missing.removeAll(reads);
                if (!missing.isEmpty()) {
                    unread.put(nodeType, missing);
                }
            });

            assertThat(unread)
                .as("these aliases pass validation but no creator reads them, so the param is "
                    + "silently dropped - teach the creator (CreatorBase.firstNonBlank) or remove "
                    + "the alias")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("no rejected-but-supported spelling")
    class NoRejectedSupportedSpelling {

        /**
         * The reverse direction, checked on the canonical param names only: a spelling the creator
         * reads must either BE the canonical schema name or be listed as an alias, or add_node
         * answers "Unknown parameter" for something that would have worked.
         */
        @Test
        @DisplayName("loop: every spelling the creator reads is accepted")
        void loopSpellingsAreAccepted() {
            Set<String> accepted = new java.util.HashSet<>(paramAliases().get("loop").keySet());
            // canonical schema names, always accepted without an alias entry
            accepted.add("condition");
            accepted.add("max_iterations");

            Set<String> rejected = new TreeSet<>(CREATOR_READS.get("loop"));
            rejected.removeAll(accepted);

            assertThat(rejected)
                .as("UtilityNodeCreator.createLoop honours these, so add_node must not reject them")
                .isEmpty();
        }

        @Test
        @DisplayName("response: the node's own name is an accepted spelling")
        void responseSpellingIsAccepted() {
            assertThat(paramAliases().get("response"))
                .as("UtilityNodeCreator reads `response` - rejecting the spelling that matches the "
                    + "node's own name is the most surprising possible error")
                .containsKey("response");
        }
    }

    @Nested
    @DisplayName("CreatorBase.firstNonBlank")
    class FirstNonBlankTests {

        @Test
        @DisplayName("Returns the first key present and non-blank, in the order given")
        void returnsFirstNonBlankInOrder() {
            Map<String, Object> params = new HashMap<>();
            params.put("instruction", "from alias");
            params.put("task", "later alias");

            assertThat(CreatorBase.firstNonBlank(params, "prompt", "instruction", "task"))
                .isEqualTo("from alias");
        }

        @Test
        @DisplayName("The canonical name wins over an alias when both are present")
        void canonicalWinsOverAlias() {
            Map<String, Object> params = new HashMap<>();
            params.put("prompt", "canonical");
            params.put("instruction", "alias");

            assertThat(CreatorBase.firstNonBlank(params, "prompt", "instruction")).isEqualTo("canonical");
        }

        @Test
        @DisplayName("A blank value is skipped, not returned")
        void blankIsSkipped() {
            Map<String, Object> params = new HashMap<>();
            params.put("prompt", "   ");
            params.put("instruction", "real value");

            assertThat(CreatorBase.firstNonBlank(params, "prompt", "instruction")).isEqualTo("real value");
        }

        @Test
        @DisplayName("Returns null when nothing matches, and tolerates a null map")
        void nullWhenNothingMatches() {
            assertThat(CreatorBase.firstNonBlank(Map.of("other", "x"), "prompt", "instruction")).isNull();
            assertThat(CreatorBase.firstNonBlank(null, "prompt")).isNull();
        }
    }
}
