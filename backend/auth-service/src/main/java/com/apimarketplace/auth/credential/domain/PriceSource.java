package com.apimarketplace.auth.credential.domain;

import java.util.Locale;

/**
 * Who decided a {@link PricingVersionEntry} (V430).
 *
 * <p>This is the price list's answer to the same question
 * {@code agent.model_config_overrides.user_modified_fields} answers for the LLM
 * catalog: which parts of a distributed snapshot belong to the install and must
 * survive the next snapshot. There, the unit is a field; here it is a whole row,
 * because a price is six numbers that only mean anything together (a unit, a
 * fixed part, a variable part and two clamps).
 */
public enum PriceSource {

    /**
     * Published on this install: the admin screens, or the catalog importer's
     * starting-price bootstrap. A bundle never overwrites one of these.
     *
     * <p>Also what every row written before V430 reads as, which is deliberate:
     * a price that is already live was decided by somebody here, and the safe
     * reading of an unknown provenance is "do not reprice it behind their back".
     */
    ADMIN("admin"),

    /**
     * Written by an applied API-catalog bundle. The next bundle owns it, so a
     * cloud price change keeps reaching every model the install has not tuned.
     */
    BUNDLE("bundle");

    private final String wire;

    PriceSource(String wire) {
        this.wire = wire;
    }

    /** Value stored in {@code auth.pricing_version_entry.source}. */
    public String wire() {
        return wire;
    }

    /**
     * Lenient read: anything unrecognised (including null) is {@link #ADMIN}.
     *
     * <p>Lenient in the direction that PRESERVES a price rather than replaces
     * one. A value this build does not understand is more likely a row written
     * by a newer build than a row a bundle may take over, and guessing wrong
     * towards ADMIN costs an unapplied price update, while guessing wrong the
     * other way silently reverts an administrator's decision.
     */
    public static PriceSource fromWire(String raw) {
        if (raw == null) return ADMIN;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return BUNDLE.wire.equals(v) ? BUNDLE : ADMIN;
    }
}
