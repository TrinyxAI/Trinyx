package com.apimarketplace.credential.client.dto;

import java.util.Locale;

/**
 * Whether a published price and a measured call are talking about the same
 * thing.
 *
 * <p>A positive amount is not proof that the two can be multiplied. A row
 * published "per image" and a call measured in seconds both look ordinary on
 * their own, a rate and a bare 10; multiplied, a ten second clip is charged ten
 * times the per-image rate. The publish-time guard cannot catch it, because it
 * only arms on a RE-publish and has nothing to compare a first row against, so
 * every path that turns a price and a measurement into an amount has to ask
 * this question itself.
 *
 * <p><b>Why the rule lives here and not in the enum that owns it.</b> The
 * authority on what a unit measures is {@code PriceUnit} in auth-service, which
 * owns publishing. It cannot be imported by a consumer: this is the client JAR
 * a service reads the wire value through, and a service does not depend on
 * another service's domain. So the mapping is restated, once, in the one place
 * both DTOs of this JAR can share, rather than copied into each caller that
 * needs it. {@code PriceUnitVocabularyParityTest} pins this restatement against
 * an explicit unit-to-dimension table (it cannot import the enum either), so
 * adding a unit or moving one between dimensions fails a test instead of
 * silently mis-charging.
 *
 * <p>Answers TRUE whenever it cannot tell. A missing unit on either side, a
 * flat price (a per-call rate applies to a call of any size, which is what flat
 * means), or a spelling neither side knows are all "no contradiction visible",
 * and refusing on ignorance would take out calls that were fine.
 */
public final class PriceDimensions {

    private PriceDimensions() {}

    /** True when a call measured in {@code measuredUnit} can be billed at a {@code priceUnit} rate. */
    public static boolean canPriceMeasurementIn(String priceUnit, String measuredUnit) {
        if (measuredUnit == null || measuredUnit.isBlank()) return true;
        if (priceUnit == null || priceUnit.isBlank()) return true;
        String published = dimensionOf(priceUnit);
        String measured = dimensionOf(measuredUnit);
        if (published == null || measured == null) return true;
        // A flat rate is charged per call and is therefore compatible with any
        // measurement: it simply ignores it.
        if ("call".equals(published)) return true;
        return published.equals(measured);
    }

    /** What a unit measures, or null when the name is not one this platform prices by. */
    private static String dimensionOf(String unit) {
        return switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "call" -> "call";
            case "second", "minute" -> "duration";
            case "image" -> "image";
            case "character" -> "character";
            default -> null;
        };
    }
}
