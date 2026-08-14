package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceUnit;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V428 quantity-based pricing.
 *
 * <p>The formula decides what a customer is actually charged, so the cases
 * that matter most are the ones where it must NOT change: every price
 * published before V428 has to keep billing the exact same amount after the
 * upgrade, and a missing or nonsensical quantity must never inflate a bill.
 */
class UnitPricingTest {

    private final MarkupPolicy policy = new MarkupPolicy();

    private static PlatformCredentialPricingVersion version(String defaultMarkup) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setDefaultMarkupCredits(new BigDecimal(defaultMarkup));
        return v;
    }

    private static PricingVersionEntry entry(String base, String unit, String perUnit,
                                              String min, String max) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setMarkupCredits(new BigDecimal(base));
        e.setPriceUnit(unit);
        e.setUnitCredits(new BigDecimal(perUnit));
        if (min != null) e.setMinCredits(new BigDecimal(min));
        if (max != null) e.setMaxCredits(new BigDecimal(max));
        return e;
    }

    @Nested
    @DisplayName("backward compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("a pre-V428 flat row bills its exact amount, untouched by the new formula")
        void flatRowIsUnchanged() {
            PricingVersionEntry legacy = entry("12", "call", "0", null, null);

            assertThat(policy.resolveEffectivePrice(version("5"), Optional.of(legacy), BigDecimal.ONE))
                    .isEqualByComparingTo("12");
            // and with no quantity supplied at all, which is how every existing
            // caller reaches it through the 2-arg overload
            assertThat(policy.resolveEffectivePrice(version("5"), Optional.of(legacy), null))
                    .isEqualByComparingTo("12");
        }

        @Test
        @DisplayName("with no per-tool row the version default still applies, unscaled")
        void versionDefaultIsNotScaled() {
            // A version carries no unit, so a quantity must not multiply it.
            assertThat(policy.resolveEffectivePrice(version("7"), Optional.empty(), new BigDecimal("30")))
                    .isEqualByComparingTo("7");
        }

        @Test
        @DisplayName("a null version yields zero rather than failing the call")
        void nullVersionIsZero() {
            assertThat(policy.resolveEffectivePrice(null, Optional.empty(), BigDecimal.TEN))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("the formula")
    class Formula {

        @Test
        @DisplayName("per-second video: a 10 s clip at 60 credits/s bills 600")
        void perSecondVideo() {
            PricingVersionEntry video = entry("0", "second", "60", null, null);
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(video), new BigDecimal("10")))
                    .isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("a base and a rate add up rather than replacing each other")
        void baseAndRateCombine() {
            PricingVersionEntry e = entry("50", "second", "10", null, null);
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(e), new BigDecimal("5")))
                    .isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("per-character speech: a 500-char prompt at 0.2 credits/char bills 100")
        void perCharacterSpeech() {
            PricingVersionEntry tts = entry("0", "character", "0.2", "1", null);
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(tts), new BigDecimal("500")))
                    .isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("the floor lifts a tiny call so a one-character request still bills something")
        void floorApplies() {
            PricingVersionEntry tts = entry("0", "character", "0.2", "1", null);
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(tts), BigDecimal.ONE))
                    .isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("the ceiling caps a runaway quantity, protecting the customer from a surprise bill")
        void ceilingApplies() {
            PricingVersionEntry tts = entry("0", "character", "0.2", null, "500");
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(tts), new BigDecimal("100000")))
                    .isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("a zero quantity charges the base only, never a negative amount")
        void zeroQuantityChargesBaseOnly() {
            PricingVersionEntry e = entry("25", "second", "60", null, null);
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(e), BigDecimal.ZERO))
                    .isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("a negative quantity is treated as zero rather than crediting the customer")
        void negativeQuantityIsClampedToZero() {
            PricingVersionEntry e = entry("25", "second", "60", null, null);
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(e), new BigDecimal("-10")))
                    .isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("a NULL quantity is not a zero quantity: it bills one unit, never nothing")
        void nullQuantityIsNotZeroQuantity() {
            PricingVersionEntry e = entry("25", "second", "60", null, null);

            // ZERO says "I measured it and the call was empty" - base only.
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(e), BigDecimal.ZERO))
                    .isEqualByComparingTo("25");
            // NULL says "I had no way to measure it", which is every path that
            // predates unit pricing. Reading that as zero would charge the fixed
            // part alone, and for a pure per-second rate the fixed part is
            // nothing: the platform key would go out free.
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(e), null))
                    .isEqualByComparingTo("85");
        }

        @Test
        @DisplayName("both entry points give the SAME answer for a missing quantity")
        void bothEntryPointsAgree() {
            // These two disagreed before: one charged the base, the other one
            // unit, so the identical row billed differently depending on which
            // caller read it.
            PricingVersionEntry e = entry("0", "second", "60", null, null);

            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(e), null))
                    .isEqualByComparingTo(
                            policy.resolveEffectivePriceWithoutQuantity(version("0"), Optional.of(e)));
        }
    }

    @Nested
    @DisplayName("the published unit is the one that scales the quantity")
    class PublishedUnitScalesTheQuantity {

        // The caller measures a call ONCE, in platform units (seconds, assets,
        // characters). Only the published row knows what its rate is charged
        // per, so it is the row that converts. Before this, the catalog scaled
        // the quantity by the unit the SEED declared while auth applied the rate
        // of the unit an admin had PUBLISHED, and nothing reconciled the two.

        @Test
        @DisplayName("a per-minute rate on a 60 second call bills the rate ONCE, not sixty times")
        void perMinuteRateBillsOncePerMinute() {
            // The exact defect: the same money as 8 credits/second, republished
            // as 480 credits/minute. The call is measured in seconds either way.
            PricingVersionEntry perMinute = entry("0", "minute", "480", null, null);

            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(perMinute),
                    new BigDecimal("60"))).isEqualByComparingTo("480");
        }

        @Test
        @DisplayName("a per-minute rate keeps the fraction, so a 30 second clip costs half a minute")
        void perMinuteRateChargesAFractionOfAMinute() {
            PricingVersionEntry perMinute = entry("0", "minute", "480", null, null);

            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(perMinute),
                    new BigDecimal("30"))).isEqualByComparingTo("240");
        }

        @Test
        @DisplayName("the same money published per second or per minute charges the same amount")
        void thePriceIsTheSameWhicheverUnitExpressesIt() {
            // 8 credits per second and 480 credits per minute are one price. If
            // the published unit only decorated the rate, these two would differ
            // by a factor of 60 for an identical call.
            BigDecimal perSecond = policy.resolveEffectivePrice(version("0"),
                    Optional.of(entry("0", "second", "8", null, null)), new BigDecimal("90"));
            BigDecimal perMinute = policy.resolveEffectivePrice(version("0"),
                    Optional.of(entry("0", "minute", "480", null, null)), new BigDecimal("90"));

            assertThat(perSecond).isEqualByComparingTo("720");
            assertThat(perMinute).isEqualByComparingTo(perSecond);
        }

        @Test
        @DisplayName("a clamp is applied to the converted amount, not to the raw measurement")
        void clampsApplyAfterConversion() {
            PricingVersionEntry perMinute = entry("0", "minute", "480", null, "500");

            // 120 s = 2 minutes = 960, capped at 500. Reading the measurement as
            // 120 minutes would have hit the cap on every call instead.
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(perMinute),
                    new BigDecimal("120"))).isEqualByComparingTo("500");
            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(perMinute),
                    new BigDecimal("30"))).isEqualByComparingTo("240");
        }

        @Test
        @DisplayName("billableQuantity reports the number of PUBLISHED units charged, not the measurement")
        void billableQuantityIsReportedInThePublishedUnit() {
            // What a surface prints beside the unit it was quoted in. Echoing
            // the 60 seconds back next to "per minute" would contradict itself.
            assertThat(policy.billableQuantity(
                    Optional.of(entry("0", "minute", "480", null, null)), new BigDecimal("60")))
                    .isEqualByComparingTo("1");
            assertThat(policy.billableQuantity(
                    Optional.of(entry("0", "second", "8", null, null)), new BigDecimal("60")))
                    .isEqualByComparingTo("60");
            // A flat row charges one call whatever the caller measured.
            assertThat(policy.billableQuantity(
                    Optional.of(entry("5", "call", "3", null, null)), new BigDecimal("60")))
                    .isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a per-second and a per-character row bill exactly what they billed before")
        void matchingUnitsAreUnchanged() {
            // Nothing about a row whose unit already matched the measurement may
            // move: these are the amounts the pre-change code charged.
            assertThat(policy.resolveEffectivePrice(version("0"),
                    Optional.of(entry("0", "second", "60", null, null)), new BigDecimal("10")))
                    .isEqualByComparingTo("600");
            assertThat(policy.resolveEffectivePrice(version("0"),
                    Optional.of(entry("0", "character", "0.2", "1", null)), new BigDecimal("500")))
                    .isEqualByComparingTo("100");
            assertThat(policy.resolveEffectivePrice(version("0"),
                    Optional.of(entry("0", "image", "40", null, null)), new BigDecimal("3")))
                    .isEqualByComparingTo("120");
            assertThat(policy.resolveEffectivePrice(version("5"),
                    Optional.of(entry("12", "call", "0", null, null)), new BigDecimal("42")))
                    .isEqualByComparingTo("12");
        }

        @Test
        @DisplayName("a flat row does not multiply by the size of the call it is handed")
        void aFlatRowIgnoresTheMeasurement() {
            // A per-call rate is charged once per call by definition. Multiplying
            // it by a measurement would be the same class of error in reverse.
            PricingVersionEntry flatWithRate = entry("0", "call", "25", null, null);

            assertThat(policy.resolveEffectivePrice(version("0"), Optional.of(flatWithRate),
                    new BigDecimal("60"))).isEqualByComparingTo("25");
        }
    }

    @Nested
    @DisplayName("republishing a row in another unit")
    class UnitChanges {

        @Test
        @DisplayName("a per-call row cannot be republished per second, because nothing measures seconds for it")
        void refusesCallToSecond() {
            // The undercharge mirror of the per-minute defect: the caller keeps
            // reporting one call, so every request would bill a single second.
            assertThatThrownBy(() ->
                    policy.validateUnitChange(PriceUnit.CALL, PriceUnit.SECOND, "toolId=t, modelId=m"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot publish toolId=t, modelId=m per second")
                    .hasMessageContaining("this price is per call")
                    .hasMessageContaining("billed a single second")
                    .hasMessageContaining("Keep the flat 'call' price");
        }

        @Test
        @DisplayName("a per-second row cannot be republished per character")
        void refusesSecondToCharacter() {
            assertThatThrownBy(() ->
                    policy.validateUnitChange(PriceUnit.SECOND, PriceUnit.CHARACTER, "toolId=t"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("measured in seconds")
                    .hasMessageContaining("nothing would measure characters");
        }

        @Test
        @DisplayName("per second to per minute is allowed: one measurement, another scale")
        void allowsSecondToMinute() {
            assertThatCode(() ->
                    policy.validateUnitChange(PriceUnit.SECOND, PriceUnit.MINUTE, "toolId=t"))
                    .doesNotThrowAnyException();
            assertThatCode(() ->
                    policy.validateUnitChange(PriceUnit.MINUTE, PriceUnit.SECOND, "toolId=t"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("any row may collapse to a flat per-call price, which reads no measurement")
        void allowsAnythingToCall() {
            assertThatCode(() ->
                    policy.validateUnitChange(PriceUnit.SECOND, PriceUnit.CALL, "toolId=t"))
                    .doesNotThrowAnyException();
            assertThatCode(() ->
                    policy.validateUnitChange(PriceUnit.CHARACTER, PriceUnit.CALL, "toolId=t"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a row with no unit on either side is nothing to validate")
        void nullsAreNotAnOpinion() {
            assertThatCode(() -> policy.validateUnitChange(null, PriceUnit.SECOND, "toolId=t"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> policy.validateUnitChange(PriceUnit.SECOND, null, "toolId=t"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("PriceUnit quantity resolution")
    class Quantities {

        @Test
        @DisplayName("CALL always counts one, whatever it is handed")
        void callIsAlwaysOne() {
            assertThat(PriceUnit.CALL.quantityOf(null)).isEqualByComparingTo("1");
            assertThat(PriceUnit.CALL.quantityOf(999)).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("SECOND reads the raw value, parsing a string the same as a number")
        void secondReadsRawValue() {
            assertThat(PriceUnit.SECOND.quantityOf(10)).isEqualByComparingTo("10");
            assertThat(PriceUnit.SECOND.quantityOf("10")).isEqualByComparingTo("10");
            assertThat(PriceUnit.SECOND.quantityOf(7.5)).isEqualByComparingTo("7.5");
        }

        @Test
        @DisplayName("MINUTE keeps the fraction: 90 seconds is 1.5 minutes, not a rounded-up 2")
        void minuteKeepsFraction() {
            assertThat(PriceUnit.MINUTE.quantityOf(90)).isEqualByComparingTo("1.5");
            assertThat(PriceUnit.MINUTE.quantityOf(30)).isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("CHARACTER counts the characters it is handed, the same number the builder measured")
        void characterCountsTheMeasurement() {
            // The measurement is the prompt's LENGTH, taken by the caller that
            // holds the prompt. The enum used to be documented as reading the
            // prompt itself and would have parsed "hello" as a number, i.e. as
            // zero characters: two readings of one rule, and the wrong one here.
            assertThat(PriceUnit.CHARACTER.quantityOf(11)).isEqualByComparingTo("11");
            assertThat(PriceUnit.CHARACTER.quantityOf("500")).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("an unparseable or missing value counts zero rather than being guessed")
        void unusableValueIsZero() {
            assertThat(PriceUnit.SECOND.quantityOf("abc")).isEqualByComparingTo("0");
            assertThat(PriceUnit.SECOND.quantityOf(null)).isEqualByComparingTo("0");
            assertThat(PriceUnit.SECOND.quantityOf("")).isEqualByComparingTo("0");
            assertThat(PriceUnit.SECOND.quantityOf(-5)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("units of the same dimension can price each other's measurement, units of another cannot")
        void dimensionsDecideCompatibility() {
            // Seconds and minutes are one measurement at two scales.
            assertThat(PriceUnit.MINUTE.canPriceMeasurementFor(PriceUnit.SECOND)).isTrue();
            assertThat(PriceUnit.SECOND.canPriceMeasurementFor(PriceUnit.MINUTE)).isTrue();
            // A flat price reads no measurement, so it fits anything.
            assertThat(PriceUnit.CALL.canPriceMeasurementFor(PriceUnit.SECOND)).isTrue();
            assertThat(PriceUnit.CALL.canPriceMeasurementFor(PriceUnit.CHARACTER)).isTrue();
            // Nothing measures seconds for a call, or characters for a video.
            assertThat(PriceUnit.SECOND.canPriceMeasurementFor(PriceUnit.CALL)).isFalse();
            assertThat(PriceUnit.CHARACTER.canPriceMeasurementFor(PriceUnit.SECOND)).isFalse();
            assertThat(PriceUnit.IMAGE.canPriceMeasurementFor(PriceUnit.CHARACTER)).isFalse();
        }

        @Test
        @DisplayName("an unrecognised stored unit degrades to CALL so the base still bills")
        void unknownUnitDegradesToCall() {
            assertThat(PriceUnit.fromWire("megapixel")).isEqualTo(PriceUnit.CALL);
            assertThat(PriceUnit.fromWire(null)).isEqualTo(PriceUnit.CALL);
            assertThat(PriceUnit.fromWire("  ")).isEqualTo(PriceUnit.CALL);
            assertThat(PriceUnit.fromWire("SECOND")).isEqualTo(PriceUnit.SECOND);
        }
    }

    @Nested
    @DisplayName("a caller with no quantity")
    class WithoutQuantity {

        @Test
        @DisplayName("a per-unit row billed through the flat path charges ONE unit, never zero")
        void unitPricedRowNeverBillsZero() {
            // Before V428 every price was flat, so the flat path could read the
            // base and be right. An admin can now put a per-second rate on an
            // endpoint some other path also calls; reading the base there would
            // charge 0 and give the platform key away silently.
            PricingVersionEntry perSecond = entry("0", "second", "60", null, null);

            assertThat(policy.resolveEffectivePriceWithoutQuantity(version("0"), Optional.of(perSecond)))
                    .isEqualByComparingTo("60");
        }

        @Test
        @DisplayName("a flat row is unaffected, so nothing that billed correctly starts billing more")
        void flatRowIsUnaffected() {
            PricingVersionEntry flat = entry("12", "call", "0", null, null);

            assertThat(policy.resolveEffectivePriceWithoutQuantity(version("5"), Optional.of(flat)))
                    .isEqualByComparingTo("12");
            assertThat(policy.resolveEffectivePriceWithoutQuantity(version("7"), Optional.empty()))
                    .isEqualByComparingTo("7");
        }

        @Test
        @DisplayName("the mismatch is detectable, so a caller can log it instead of hiding it")
        void mismatchIsDetectable() {
            assertThat(policy.isUnitPricedWithoutQuantity(
                    Optional.of(entry("0", "second", "60", null, null)))).isTrue();
            assertThat(policy.isUnitPricedWithoutQuantity(
                    Optional.of(entry("12", "call", "0", null, null)))).isFalse();
            assertThat(policy.isUnitPricedWithoutQuantity(Optional.empty())).isFalse();
        }
    }

    @Nested
    @DisplayName("entity defaults")
    class EntityDefaults {

        @Test
        @DisplayName("a freshly built row is flat and free, so a half-filled row cannot overcharge")
        void freshRowIsFlatAndFree() {
            PricingVersionEntry e = new PricingVersionEntry();
            assertThat(e.getPriceUnit()).isEqualTo("call");
            assertThat(e.getUnitCredits()).isEqualByComparingTo("0");
            assertThat(e.unit()).isEqualTo(PriceUnit.CALL);
        }
    }
}
