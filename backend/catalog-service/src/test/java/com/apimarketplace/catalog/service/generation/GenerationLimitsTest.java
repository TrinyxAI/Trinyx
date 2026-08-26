package com.apimarketplace.catalog.service.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a surface is told a parameter accepts.
 *
 * <p>Two sources feed one shape: the descriptor's own constraints, and the
 * values the catalogue already knows the underlying parameter takes. The rules
 * between them are the whole point, because getting either backwards is
 * expensive: a widened list offers values the model refuses (a paid failure),
 * and a silently truncated one reads as exhaustive (a caller concludes a voice
 * does not exist).
 */
class GenerationLimitsTest {

    /** A model that accepts a voice and a prompt, restricting only what is named. */
    private static GenerationSpec.Model model(Map<String, GenerationSpec.Constraint> constraints) {
        return new GenerationSpec.Model(
                "m-1", null, "M1",
                java.util.Set.of("prompt", "voice"),
                java.util.Set.of("prompt"),
                constraints,
                Map.of(),
                new GenerationSpec.Price("call", null, BigDecimal.ONE, null, null));
    }

    private static GenerationSpec.Constraint allowed(Object... values) {
        return new GenerationSpec.Constraint(List.of(values), null, null, null);
    }

    @Test
    @DisplayName("a parameter the descriptor leaves open takes the catalogue's list")
    void inheritsWhenUnrestricted() {
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of()), Map.of("voice", List.of("asteria", "luna")), 0);

        assertThat(limits).containsOnlyKeys("voice");
        assertThat(asMap(limits.get("voice"))).containsEntry("allowed", List.of("asteria", "luna"));
    }

    @Test
    @DisplayName("regression: a list the descriptor states itself is NOT widened by the catalogue")
    void declaredWins() {
        // The endpoint takes eleven voices and this model accepts two of them.
        // Letting the catalogue win would offer the other nine, and the refusal
        // would arrive from the provider, after the call was billed.
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of("voice", allowed("alloy", "echo"))),
                Map.of("voice", List.of("alloy", "echo", "fable", "nova")),
                0);

        assertThat(asMap(limits.get("voice"))).containsEntry("allowed", List.of("alloy", "echo"));
    }

    @Test
    @DisplayName("an inherited list JOINS a declared bound on the same parameter")
    void mergesIntoAnExistingEntry() {
        // A bound and a list are different facts about one parameter, and the
        // declared bound is enforced. Replacing the entry instead of adding to
        // it would drop a limit that refuses a call before it is billed.
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of("voice", new GenerationSpec.Constraint(
                        List.of(), new BigDecimal("1"), new BigDecimal("9"), null))),
                Map.of("voice", List.of("asteria")),
                0);

        Map<String, Object> voice = asMap(limits.get("voice"));
        assertThat(voice).containsEntry("min", new BigDecimal("1"));
        assertThat(voice).containsEntry("max", new BigDecimal("9"));
        assertThat(voice).containsEntry("allowed", List.of("asteria"));
        assertThat(voice).containsEntry("allowedEnforced", false);
    }

    @Test
    @DisplayName("an inherited list is marked advisory; a declared one is not")
    void marksWhichListIsEnforced() {
        // The two fail in opposite directions: a declared value outside the
        // list is refused before billing, an inherited one is dispatched. A
        // surface that cannot tell them apart either forbids what the platform
        // accepts, or offers a closed choice where none exists.
        Map<String, Object> declared = GenerationLimits.describe(
                model(Map.of("voice", allowed("alloy"))), Map.of(), 0);
        assertThat(asMap(declared.get("voice"))).doesNotContainKey("allowedEnforced");

        Map<String, Object> inherited = GenerationLimits.describe(
                model(Map.of()), Map.of("voice", List.of("asteria")), 0);
        assertThat(asMap(inherited.get("voice"))).containsEntry("allowedEnforced", false);
    }

    @Test
    @DisplayName("a list exactly the size of the cap is whole, not a sample")
    void atTheCapIsNotTruncated() {
        // `>` and `>=` differ by one entry here, and the wrong one stamps a
        // complete list as a sample.
        List<String> twelve = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) twelve.add("v" + i);

        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of()), Map.of("voice", twelve), 12);

        Map<String, Object> voice = asMap(limits.get("voice"));
        assertThat((List<?>) voice.get("allowed")).hasSize(12);
        assertThat(voice).doesNotContainKey("allowedTruncated");
    }

    @Test
    @DisplayName("a null inherited map is the same as none")
    void toleratesNoInheritance() {
        // The record is public and its canonical constructor does not normalise
        // null, so a hand-built model must not take the surface down.
        assertThat(GenerationLimits.describe(model(Map.of()), null, 0)).isEmpty();
    }

    @Test
    @DisplayName("past the cap the list is a sample, and says so")
    void capsAndSaysSo() {
        // A hundred voices in a payload an agent reads as tokens would crowd
        // out the models it describes. What is left has to be readable AS a
        // sample, or the caller concludes the missing values do not exist.
        List<String> hundred = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) hundred.add("voice-" + i);

        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of()), Map.of("voice", hundred), 12);

        Map<String, Object> voice = asMap(limits.get("voice"));
        assertThat((List<?>) voice.get("allowed")).hasSize(12);
        assertThat(voice).containsEntry("allowedTruncated", true);
        assertThat(voice).containsEntry("allowedCount", 100);
    }

    @Test
    @DisplayName("a list that fits is not marked as truncated")
    void underTheCapIsWhole() {
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of()), Map.of("voice", List.of("a", "b")), 12);

        Map<String, Object> voice = asMap(limits.get("voice"));
        assertThat(voice).containsEntry("allowed", List.of("a", "b"));
        assertThat(voice).doesNotContainKey("allowedTruncated");
    }

    @Test
    @DisplayName("no cap means the whole list, for a surface that pays in bytes")
    void uncapped() {
        List<String> fifty = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) fifty.add("v" + i);

        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of()), Map.of("voice", fifty), 0);

        assertThat((List<?>) asMap(limits.get("voice")).get("allowed")).hasSize(50);
        assertThat(asMap(limits.get("voice"))).doesNotContainKey("allowedTruncated");
    }

    @Test
    @DisplayName("a constraint that restricts nothing publishes nothing")
    void emptyConstraintIsNotPublished() {
        // An empty entry claims a restriction and names none, which is worse
        // than saying nothing at all.
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of("voice", new GenerationSpec.Constraint(List.of(), null, null, null))),
                Map.of(), 0);

        assertThat(limits).isEmpty();
    }

    @Test
    @DisplayName("an empty inherited list is not published either")
    void emptyInheritedIsIgnored() {
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of()), Map.of("voice", List.of()), 0);

        assertThat(limits).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    @DisplayName("a fetchable parameter is MARKED, and its enforced limit survives the marking")
    void dynamicMarkingMergesRatherThanReplaces() {
        // A parameter can carry both: a list the descriptor enforces AND values
        // only the account can name. Replacing instead of merging would drop the
        // enforced one, which is the half that refuses a value for free.
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of("voice", allowed("alloy", "echo"))),
                Map.of(), 0, java.util.Set.of("voice"));

        assertThat(asMap(limits.get("voice")))
                .containsEntry("allowed", List.of("alloy", "echo"))
                .containsEntry("optionsAvailable", true);
    }

    @Test
    @DisplayName("a parameter with no other limit still gets an entry, so a surface can find the flag")
    void dynamicMarkingCreatesAnEntryWhenThereIsNoOtherLimit() {
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of()), Map.of(), 0, java.util.Set.of("voice"));

        assertThat(asMap(limits.get("voice"))).containsExactly(
                org.assertj.core.api.Assertions.entry("optionsAvailable", true));
    }

    @Test
    @DisplayName("nothing is marked when nothing is fetchable, so no surface asks for free")
    void nothingIsMarkedWithoutADescriptor() {
        Map<String, Object> limits = GenerationLimits.describe(
                model(Map.of("voice", allowed("alloy"))), Map.of(), 0, java.util.Set.of());

        assertThat(asMap(limits.get("voice"))).doesNotContainKey("optionsAvailable");
    }
}
