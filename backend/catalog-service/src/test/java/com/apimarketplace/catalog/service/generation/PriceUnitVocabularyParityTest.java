package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.credential.client.dto.FrozenMarkupDto;
import com.apimarketplace.credential.client.dto.PriceDimensions;
import com.apimarketplace.credential.client.dto.ResolvedScopeMarkupDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The price-unit vocabulary is restated in several modules that cannot import
 * one another (auth owns publishing, credential-client carries the wire value,
 * catalog validates the seed, the frontend renders it). Each restatement is
 * defensible; what is not defensible is that adding or renaming a unit means
 * editing all of them and nothing links them, so a miss produces a silent
 * mis-charge rather than a compile error.
 *
 * <p>This pins the two ends this module CAN see at once: the units a seed may
 * declare, and what the client JAR believes each of them measures. It exists
 * because the one real divergence found so far, a quote and an invoice
 * disagreeing by 60x on a per-minute row, lived in exactly this kind of gap.
 */
class PriceUnitVocabularyParityTest {

    /**
     * Dimensions as the platform defines them: two units of the same dimension
     * describe one physical thing at different scales, so a measurement
     * converts between them. Units of different dimensions do not.
     */
    private static final Set<String> TIME = Set.of("second", "minute");

    @Test
    @DisplayName("every unit a seed may declare is one the client JAR can reason about")
    void everySeedUnitIsUnderstoodDownstream() {
        // A unit the DTO does not recognise makes canPriceMeasurementIn answer
        // "no contradiction visible" for everything, which turns the dimension
        // guard off for that unit without saying so.
        for (String unit : GenerationSpec.Price.UNITS) {
            if ("call".equals(unit)) continue; // flat, compatible with anything by definition
            ResolvedScopeMarkupDto published = new ResolvedScopeMarkupDto();
            published.setPriceUnit(unit);
            // Its own dimension is always acceptable...
            assertThat(published.canPriceMeasurementIn(unit))
                    .as("a %s rate must price a %s measurement", unit, unit)
                    .isTrue();
            // ...and a text measurement is not, unless the unit IS textual.
            boolean textual = "character".equals(unit);
            assertThat(published.canPriceMeasurementIn("character"))
                    .as("a %s rate against a character measurement", unit)
                    .isEqualTo(textual);
        }
    }

    @Test
    @DisplayName("both pricing paths route the dimension question through the ONE shared rule")
    void bothPricingPathsShareOneDimensionRule() {
        // WHAT THIS CAN AND CANNOT PROVE. Both DTOs delegate to the same
        // PriceDimensions method, so comparing their answers to each other is
        // f(x) == f(x) and proves nothing: it would pass with the rule inverted
        // in both. The first version of this test did exactly that.
        //
        // What is worth pinning is that they still DELEGATE. A future edit that
        // re-inlines the switch into one DTO (which is how the duplication
        // arose in the first place) is the real risk, and it shows up as one
        // DTO disagreeing with the shared rule, not with the other DTO.
        for (String published : GenerationSpec.Price.UNITS) {
            for (String measured : Set.of("call", "second", "image", "character")) {
                boolean shared = PriceDimensions.canPriceMeasurementIn(published, measured);

                ResolvedScopeMarkupDto direct = new ResolvedScopeMarkupDto();
                direct.setPriceUnit(published);
                FrozenMarkupDto relayed = new FrozenMarkupDto();
                relayed.setPriceUnit(published);

                assertThat(direct.canPriceMeasurementIn(measured))
                        .as("direct path, %s rate vs %s measurement", published, measured)
                        .isEqualTo(shared);
                assertThat(relayed.canPriceMeasurementIn(measured))
                        .as("relay, %s rate vs %s measurement", published, measured)
                        .isEqualTo(shared);
            }
        }
    }

    @Test
    @DisplayName("the shared rule agrees with the enum that owns the vocabulary")
    void theSharedRuleMatchesTheAuthoritativeEnum() {
        // The gap this closes: PriceDimensions restates auth's PriceUnit
        // because a service cannot import another service's domain, and its own
        // javadoc claimed this test pinned the two together while the test
        // never mentioned the enum. Catalog cannot import it either, so the
        // authority is restated HERE, once, in the smallest form that fails
        // when a unit is added or moved between dimensions: unit -> dimension.
        //
        // Kept deliberately dumb and explicit. Deriving it from anything the
        // implementation also derives from would make it agree by construction,
        // which is the failure this whole file exists to catch.
        Map<String, String> authoritativeDimensions = Map.of(
                "call", "call",
                "second", "duration",
                "minute", "duration",
                "image", "image",
                "character", "character");

        assertThat(authoritativeDimensions.keySet())
                .as("a unit a seed may declare but this table does not name means the vocabulary "
                        + "moved and one of the restatements was not updated")
                .containsExactlyInAnyOrderElementsOf(GenerationSpec.Price.UNITS);

        for (var published : authoritativeDimensions.entrySet()) {
            for (var measured : authoritativeDimensions.entrySet()) {
                boolean sameDimension = published.getValue().equals(measured.getValue());
                boolean flat = "call".equals(published.getValue());
                assertThat(PriceDimensions.canPriceMeasurementIn(published.getKey(), measured.getKey()))
                        .as("%s rate vs %s measurement", published.getKey(), measured.getKey())
                        .isEqualTo(flat || sameDimension);
            }
        }
    }

    @Test
    @DisplayName("a per-image rate cannot price a call measured in seconds, on either path")
    void aRateOfOneDimensionCannotPriceAnother() {
        // The pair that motivated the guard. Publishing arms its own check only
        // on a RE-publish, so a FIRST "per image" row on a model measured in
        // seconds is reachable, and both halves look ordinary alone: a rate,
        // and a bare 10. Multiplied, a ten second clip costs ten times the
        // per-image rate.
        FrozenMarkupDto perImage = new FrozenMarkupDto();
        perImage.setPriceUnit("image");

        assertThat(perImage.canPriceMeasurementIn("second")).isFalse();
        assertThat(perImage.canPriceMeasurementIn("image")).isTrue();

        // And it stays permissive where it cannot tell: refusing on ignorance
        // would take out calls that were fine.
        assertThat(perImage.canPriceMeasurementIn(null)).isTrue();
        assertThat(perImage.canPriceMeasurementIn("furlongs")).isTrue();
    }

    @Test
    @DisplayName("second and minute are one dimension, so a per-minute rate prices a measurement taken in seconds")
    void timeUnitsConvertIntoEachOther() {
        for (String published : TIME) {
            for (String measured : TIME) {
                ResolvedScopeMarkupDto row = new ResolvedScopeMarkupDto();
                row.setPriceUnit(published);
                assertThat(row.canPriceMeasurementIn(measured))
                        .as("%s rate vs %s measurement", published, measured)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("catalog measures every priced dimension in the platform's own unit, never in the published one")
    void everyUnitMapsToAPlatformMeasurement() {
        // The seed may publish per minute; the platform still MEASURES in
        // seconds and lets the published row convert. A unit that measured
        // itself would have a count of seconds multiplied by a per-minute rate.
        //
        // Asserted by calling the real platformUnit(), never by a copy of its
        // switch: a test that restates the implementation passes whatever the
        // implementation says, including "minute measures minutes", which is
        // precisely the drift it is here to catch.
        for (String unit : GenerationSpec.Price.UNITS) {
            String platform = modelPricedIn(unit).platformUnit();
            assertThat(Set.of("call", "second", "image", "character"))
                    .as("platform measurement for a '%s' rate", unit)
                    .contains(platform);
            if (TIME.contains(unit)) {
                assertThat(platform)
                        .as("time is measured in seconds whatever the rate is published per")
                        .isEqualTo("second");
            }
        }
    }

    @Test
    @DisplayName("a per-minute model is measured in seconds, which is the pair that makes the conversion visible")
    void aPerMinuteModelIsStillMeasuredInSeconds() {
        // Stated on its own because it is the only pair where the published
        // unit and the measured unit differ, so it is the only one where
        // collapsing the two changes an amount.
        assertThat(modelPricedIn("minute").platformUnit()).isEqualTo("second");
        assertThat(modelPricedIn("minute").measuringParam()).isEqualTo("duration_seconds");
    }

    /** A model that declares nothing except the unit it is sold by. */
    private static GenerationSpec.Model modelPricedIn(String unit) {
        return new GenerationSpec.Model(
                "m-" + unit, "upstream", "Model",
                Set.of("prompt"), Set.of("prompt"), Map.of(),
                new GenerationSpec.Price(unit, BigDecimal.ZERO, BigDecimal.ONE, null, null));
    }
}
