package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.CredentialSource;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WHICH account a catalog step runs on.
 *
 * <p>Two halves, and neither is worth anything without the other. The first is
 * that a step written before this existed is decided exactly as it was: these
 * tests assert the markers byte for byte, because "the old system still works"
 * is the whole licence for this change. The second is that a step choosing its
 * account at RUN time refuses rather than degrades, which is the only behaviour
 * here that is genuinely new.
 */
@DisplayName("StepCredentialSelection - which account a step runs on")
class StepCredentialSelectionTest {

    private static Step step(Long selectedCredentialId,
                             CredentialSource source,
                             Long platformCredentialId,
                             String credentialSelector) {
        return new Step("tool-1", "mcp", "Publish", null, Map.of(), null, null, "node-1",
                selectedCredentialId, source, platformCredentialId, credentialSelector);
    }

    private static Map<String, Object> markersOf(StepCredentialSelection selection) {
        Map<String, Object> markers = new HashMap<>();
        selection.applyTo(markers);
        return markers;
    }

    @Nested
    @DisplayName("a step written before the selector existed")
    class StaticMode {

        @Test
        @DisplayName("with nothing pinned, emits only the user source, as it always did")
        void nothingPinned() {
            StepCredentialSelection selection =
                    StepCredentialSelection.resolve(step(null, CredentialSource.USER, null, null), null);

            assertThat(selection.isFailure()).isFalse();
            // Byte for byte: one key. An extra marker here would change what the
            // catalog resolves for every existing workflow.
            assertThat(markersOf(selection))
                    .containsExactly(Map.entry("__credentialSource__", "user"));
        }

        @Test
        @DisplayName("with a pinned id, emits that id and nothing that makes it strict")
        void pinnedId() {
            StepCredentialSelection selection =
                    StepCredentialSelection.resolve(step(42L, CredentialSource.USER, null, null), null);

            Map<String, Object> markers = markersOf(selection);
            assertThat(markers).containsOnly(
                    Map.entry("__credentialSource__", "user"),
                    Map.entry("__selectedCredentialId__", 42L));
            // The absence is the point: an author-time pin keeps the forgiving
            // fallback it has always had, so a credential deleted months later
            // does not break the workflow.
            assertThat(markers).doesNotContainKey("__credentialSelectionStrict__");
        }

        @Test
        @DisplayName("on the platform pool, emits the platform markers unchanged")
        void platformPool() {
            StepCredentialSelection selection =
                    StepCredentialSelection.resolve(step(null, CredentialSource.PLATFORM, 7L, null), null);

            assertThat(markersOf(selection)).containsOnly(
                    Map.entry("__credentialSource__", "platform"),
                    Map.entry("__platformCredentialId__", 7L));
        }

        @Test
        @DisplayName("adds nothing to the step output, so existing runs look identical")
        void describesNothing() {
            StepCredentialSelection selection =
                    StepCredentialSelection.resolve(step(42L, CredentialSource.USER, null, null), null);

            assertThat(selection.describe(null)).isNull();
        }
    }

    @Nested
    @DisplayName("a step that chooses its account at run time")
    class DynamicMode {

        @Test
        @DisplayName("a resolved name travels as a name, because that is what a table row holds")
        void resolvedName() {
            Step publish = step(null, CredentialSource.USER, null, "{{item.ig_account}}");

            StepCredentialSelection selection = StepCredentialSelection.resolve(publish, "Client A");

            assertThat(selection.isFailure()).isFalse();
            assertThat(markersOf(selection)).containsOnly(
                    Map.entry("__credentialSource__", "user"),
                    Map.entry("__selectedCredentialName__", "Client A"),
                    Map.entry("__credentialSelectionStrict__", true));
        }

        @Test
        @DisplayName("a resolved positive whole number travels as an id, so the picker output still works")
        void resolvedId() {
            Step publish = step(null, CredentialSource.USER, null, "{{trigger.output.account}}");

            StepCredentialSelection selection = StepCredentialSelection.resolve(publish, "42");

            assertThat(markersOf(selection)).containsOnly(
                    Map.entry("__credentialSource__", "user"),
                    Map.entry("__selectedCredentialId__", 42L),
                    Map.entry("__credentialSelectionStrict__", true));
        }

        @Test
        @DisplayName("a selector wins over a pin left behind by the inspector auto-fill")
        void selectorWinsOverAStalePin() {
            // The node inspector writes selectedCredentialId on first render, with
            // no user action, so a pin is NOT evidence that anyone chose it. Only
            // the selector is a deliberate gesture, so only the selector decides.
            Step publish = step(99L, CredentialSource.USER, null, "{{item.account}}");

            StepCredentialSelection selection = StepCredentialSelection.resolve(publish, "Client B");

            Map<String, Object> markers = markersOf(selection);
            assertThat(markers).doesNotContainKey("__selectedCredentialId__");
            assertThat(markers).containsEntry("__selectedCredentialName__", "Client B");
        }

        @Test
        @DisplayName("records which account served, so the run can be read back afterwards")
        void describesTheChoice() {
            Step publish = step(null, CredentialSource.USER, null, "{{item.account}}");

            Map<String, Object> described =
                    StepCredentialSelection.resolve(publish, "Client A").describe("{{item.account}}");

            assertThat(described).containsExactly(
                    Map.entry("selector", "{{item.account}}"),
                    Map.entry("resolved_credential_name", "Client A"));
        }
    }

    @Nested
    @DisplayName("a run-time choice that does not resolve stops the step")
    class RefusesRatherThanSubstitutes {

        @Test
        @DisplayName("resolving to nothing fails instead of publishing to the default account")
        void resolvedToNothing() {
            Step publish = step(null, CredentialSource.USER, null, "{{item.ig_account}}");

            StepCredentialSelection selection = StepCredentialSelection.resolve(publish, "");

            assertThat(selection.isFailure()).isTrue();
            assertThat(selection.error())
                    .contains("Publish")
                    .contains("{{item.ig_account}}")
                    .contains("resolved to nothing");
        }

        @Test
        @DisplayName("an unresolved template is a failure, not a credential named {{...}}")
        void unresolvedTemplateComesBackVerbatim() {
            // resolveTemplateString hands back the original text when a pure
            // template resolves to null, so the literal is what arrives here. Read
            // as a name it would search for a credential called "{{item.account}}",
            // find none, and the reason would be unrecognisable in the message.
            Step publish = step(null, CredentialSource.USER, null, "{{item.account}}");

            StepCredentialSelection selection =
                    StepCredentialSelection.resolve(publish, "{{item.account}}");

            assertThat(selection.isFailure()).isTrue();
        }

        @Test
        @DisplayName("a selector on the platform pool is refused rather than half-applied")
        void selectorOnThePlatformPool() {
            // The builder cannot produce this pair, an agent-built plan can, and
            // honouring one half silently is the failure this class exists for.
            Step publish = step(null, CredentialSource.PLATFORM, 7L, "{{item.account}}");

            StepCredentialSelection selection = StepCredentialSelection.resolve(publish, "Client A");

            assertThat(selection.isFailure()).isTrue();
            assertThat(selection.error()).contains("platform credential");
        }

        @Test
        @DisplayName("a failed selection cannot be written onto the wire by mistake")
        void aFailureCannotBeApplied() {
            StepCredentialSelection selection = StepCredentialSelection.resolve(
                    step(null, CredentialSource.USER, null, "{{item.account}}"), null);

            Map<String, Object> markers = new LinkedHashMap<>();
            assertThat(selection.isFailure()).isTrue();
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () -> selection.applyTo(markers));
            assertThat(markers).isEmpty();
        }
    }

    @Nested
    @DisplayName("what counts as an id rather than a name")
    class IdOrName {

        @Test
        @DisplayName("a positive whole number is an id")
        void positiveWholeNumber() {
            Step publish = step(null, CredentialSource.USER, null, "{{item.account}}");

            assertThat(StepCredentialSelection.resolve(publish, "42").selectedCredentialId())
                    .isEqualTo(42L);
        }

        @Test
        @DisplayName("zero, a negative number and a decimal are NAMES, not ids")
        void everythingElseIsAName() {
            // Nothing here is a usable id, and reading one as such would send a
            // credential nobody owns to the catalog. They travel as names instead,
            // where "no credential is called that" is a refusal with a message that
            // names what was looked for.
            Step publish = step(null, CredentialSource.USER, null, "{{item.account}}");

            for (String value : new String[]{"0", "-1", "1e3", "42.0", " 42abc"}) {
                StepCredentialSelection selection = StepCredentialSelection.resolve(publish, value);
                assertThat(selection.selectedCredentialId())
                        .as("value %s must not be read as an id", value)
                        .isNull();
                assertThat(selection.selectedCredentialName()).isEqualTo(value.trim());
            }
        }
    }

    @Nested
    @DisplayName("dynamic mode with nothing filled in")
    class DynamicButUnfilled {

        @Test
        @DisplayName("fails the step rather than quietly behaving like a static one")
        void blankSelectorFails() {
            // The state a builder produces by toggling to dynamic and clearing the
            // field. Treated as "no selector" it would run on the account pin the
            // inspector auto-filled, which is a different account from the one the
            // author was in the middle of naming.
            Step publish = step(99L, CredentialSource.USER, null, "");

            StepCredentialSelection selection = StepCredentialSelection.resolve(publish, "");

            assertThat(selection.isFailure()).isTrue();
            assertThat(selection.error()).contains("no expression to choose it with");
        }
    }

    @Nested
    @DisplayName("a table step can never choose an account")
    class TableStepsAreExcluded {

        @Test
        @DisplayName("a find node refuses a selector rather than running the list fallback green")
        void findNodeRefusesASelector() {
            // Structurally unreachable today (parseTables builds tables through the
            // 8-arg Step constructor), which is exactly why it is pinned: if that ever
            // changed, the old code path turned the refusal into null, and the caller
            // reads null as "try the list fallback" - a GREEN node on an account that
            // could not be chosen.
            Step find = new Step("tool-1", "crud-find", "Rows", null, Map.of(), 7L, null, "node-1",
                    null, CredentialSource.USER, null, "{{item.account}}");
            com.apimarketplace.orchestrator.execution.v2.nodes.FindNode node =
                    new com.apimarketplace.orchestrator.execution.v2.nodes.FindNode(
                            "node-1", find, null, 10, null);

            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context =
                    com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext.create(
                            "run-1", "wf-run-1", "tenant-1", "item-1", 0, Map.of(), null);

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.errorMessage().orElse("")).contains("table step");
        }
    }
}
