package com.apimarketplace.auth.credential.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * What a published {@code unit_credits} rate is charged per (V428).
 *
 * <p>A generation's provider cost is rarely a flat per-call amount: a video
 * costs per second, speech synthesis costs per character, an image endpoint
 * costs per image returned. The unit is what lets ONE published price cover a
 * whole family of calls instead of forcing the catalog to be split into one
 * endpoint per billable size.
 *
 * <p>{@link #CALL} is the pre-V428 shape and stays the default everywhere, so
 * an existing flat price keeps billing exactly as before.
 *
 * <p><b>This enum owns the conversion, and it is the ONLY place that does.</b>
 * A caller measures its call once, in the platform's own unit for the dimension
 * being sold ({@code duration_seconds} in SECONDS, {@code n} in IMAGES, the
 * prompt in CHARACTERS) and hands that measurement over as-is. Turning that
 * measurement into the number of PUBLISHED units to charge for happens here,
 * next to the rate it multiplies, which is what makes the scaled quantity and
 * the rate provably the same unit. The alternative, a caller that pre-converts
 * into the unit it believes is published, is exactly how a per-minute rate came
 * to be multiplied by a count of seconds.
 *
 * <p>The enum deliberately does NOT name the call parameter that supplies the
 * measurement. auth-service never sees a generation's parameters, so a
 * {@code quantityParam()} here could only ever be a second, unreachable copy of
 * what {@code GenerationRequestBuilder} does in catalog-service; it had no
 * production caller and has been removed. Which parameter measures a call is
 * owned by catalog-service, where the parameters exist; how a measurement
 * converts into a published unit is owned here, where the rate lives. Each fact
 * has exactly one home.
 */
public enum PriceUnit {

    /** Flat per-call price. Quantity is always 1. The legacy shape. */
    CALL("call", Dimension.FLAT, BigDecimal.ONE),

    /** Per second of produced media. Measured in seconds, charged per second. */
    SECOND("second", Dimension.TIME, BigDecimal.ONE),

    /** Per minute of produced media. Measured in seconds, charged per 60 of them. */
    MINUTE("minute", Dimension.TIME, BigDecimal.valueOf(60)),

    /** Per asset actually requested. Measured in assets, charged per asset. */
    IMAGE("image", Dimension.COUNT, BigDecimal.ONE),

    /** Per character of input text. Measured in characters, charged per character. */
    CHARACTER("character", Dimension.TEXT, BigDecimal.ONE);

    /**
     * What a unit measures. Two units of the same dimension describe the same
     * physical thing at a different scale ({@link #SECOND} and {@link #MINUTE}),
     * so a measurement taken for one converts into the other. Units of different
     * dimensions do not: a count of seconds is not a count of characters, and
     * multiplying one by the other's rate is a silent mis-charge rather than an
     * approximation.
     *
     * <p>{@link #FLAT} is compatible with everything because it ignores the
     * measurement entirely.
     */
    public enum Dimension {
        FLAT("calls"),
        TIME("seconds"),
        COUNT("assets"),
        TEXT("characters");

        private final String measurementNoun;

        Dimension(String measurementNoun) {
            this.measurementNoun = measurementNoun;
        }

        /** What the platform measures a call in for this dimension, for error messages. */
        public String measurementNoun() {
            return measurementNoun;
        }
    }

    private final String wire;
    private final Dimension dimension;
    private final BigDecimal platformUnitsPerUnit;

    PriceUnit(String wire, Dimension dimension, BigDecimal platformUnitsPerUnit) {
        this.wire = wire;
        this.dimension = dimension;
        this.platformUnitsPerUnit = platformUnitsPerUnit;
    }

    /** Value stored in {@code auth.pricing_version_entry.price_unit}. */
    public String wire() {
        return wire;
    }

    /** What this unit measures, see {@link Dimension}. */
    public Dimension dimension() {
        return dimension;
    }

    /**
     * True when a measurement taken for {@code other} can be billed at this
     * unit's rate: either the two count the same thing (possibly at a different
     * scale), or this unit is flat and does not read the measurement at all.
     */
    public boolean canPriceMeasurementFor(PriceUnit other) {
        if (other == null || this == CALL) {
            return true;
        }
        return dimension == other.dimension;
    }

    /**
     * Lenient lookup for READING a value that is already stored. Unknown, blank
     * and null values resolve to {@link #CALL} rather than throwing: a price
     * row that somehow carries an unrecognised unit must still bill its base
     * amount instead of failing the whole call.
     *
     * <p>This must NOT be used to accept a unit somebody is publishing. It
     * turns a typo into a flat price, so {@code "seconds"} would republish a
     * per-second rate as per-call and bill a ten second clip as one call,
     * always in the undercharging direction, and the DB CHECK constraint never
     * sees the bad value because this already replaced it. Publishing goes
     * through {@link #parseStrict}.
     */
    public static PriceUnit fromWire(String value) {
        if (value == null || value.isBlank()) return CALL;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PriceUnit u : values()) {
            if (u.wire.equals(normalized)) return u;
        }
        return CALL;
    }

    /**
     * Strict lookup for a unit somebody is PUBLISHING. Absent means a flat
     * price, which is a real choice and stays {@link #CALL}; anything else that
     * is not a unit is a mistake and is refused by name, because guessing it
     * writes a price nobody chose.
     *
     * @throws IllegalArgumentException when the value is not a known unit
     */
    public static PriceUnit parseStrict(String value) {
        if (value == null || value.isBlank()) return CALL;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PriceUnit u : values()) {
            if (u.wire.equals(normalized)) return u;
        }
        StringBuilder accepted = new StringBuilder();
        for (PriceUnit u : values()) {
            if (accepted.length() > 0) accepted.append(", ");
            accepted.append(u.wire);
        }
        throw new IllegalArgumentException(
                "unknown priceUnit '" + value + "'. Accepted units are: " + accepted);
    }

    /**
     * Convert a PLATFORM measurement of a call into the number of units this
     * price is charged per.
     *
     * <p>{@link #CALL} always yields 1: a flat price does not read the size of
     * the call. {@link #MINUTE} divides the seconds it is given by 60 (kept
     * fractional, a 90 s clip bills 1.5 minutes, never a rounded-up 2). Every
     * other unit is already the platform unit for its dimension and takes the
     * measurement as-is, which includes {@link #CHARACTER}: the measurement is
     * the prompt's LENGTH, counted by the caller that holds the prompt, not the
     * prompt itself.
     *
     * <p>A null, unparseable or negative measurement yields
     * {@link BigDecimal#ZERO}, which reduces the price to its base component.
     * That is deliberate: a missing quantity must never be guessed at, and
     * charging only the base is the conservative direction for the payer.
     */
    public BigDecimal quantityOf(Object platformMeasurement) {
        if (this == CALL) return BigDecimal.ONE;
        BigDecimal parsed = toDecimal(platformMeasurement);
        if (parsed == null || parsed.signum() < 0) return BigDecimal.ZERO;
        if (platformUnitsPerUnit.compareTo(BigDecimal.ONE) == 0) return parsed;
        return parsed.divide(platformUnitsPerUnit, new MathContext(12, RoundingMode.HALF_UP));
    }

    private static BigDecimal toDecimal(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (raw instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.isEmpty()) return null;
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
