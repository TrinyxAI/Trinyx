package com.apimarketplace.auth.credential.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * One price to publish, before it is attached to a version (V428).
 *
 * <p>This is the un-persisted twin of {@link PricingVersionEntry}: the same six
 * numbers, with no id and no version yet. It exists because publishing used to
 * be expressed as {@code Map<apiToolId, credits>}, which can only carry ONE
 * number per endpoint. A generation price needs six (which model, what the rate
 * is charged per, the fixed part, the variable part, and the two clamps), so a
 * map key/value pair cannot express it and the declared starting prices had
 * nowhere to land.
 *
 * <p><b>A flat price is still a price.</b> {@link #flat(UUID, BigDecimal)}
 * builds the pre-V428 shape: unit {@code call}, no per-unit rate, no clamps.
 * It resolves to exactly {@code baseCredits}, so the admin publish path and the
 * legacy image-generation bootstrap keep billing the amount they always did.
 *
 * @param apiToolId   endpoint the price applies to (never null)
 * @param modelId     generation model, or null for the endpoint-wide price
 * @param priceUnit   what {@code unitCredits} is charged per, see {@link PriceUnit}
 * @param baseCredits fixed component, stored in {@code markup_credits}
 * @param unitCredits credits per unit of {@link #priceUnit}
 * @param minCredits  optional floor applied after the formula, null for none
 * @param maxCredits  optional ceiling applied after the formula, null for none
 * @param source      who decided this price (V430), see {@link PriceSource}
 */
public record PriceSpec(UUID apiToolId,
                        String modelId,
                        String priceUnit,
                        BigDecimal baseCredits,
                        BigDecimal unitCredits,
                        BigDecimal minCredits,
                        BigDecimal maxCredits,
                        PriceSource source) {

    /**
     * The shape every caller spoke before V430: a price decided on this install.
     *
     * <p>Kept as a constructor rather than pushed onto the call sites because
     * {@link PriceSource#ADMIN} is what all of them mean. The admin screens, the
     * importer's starting-price bootstrap and the legacy flat-override path are
     * all "somebody here published this"; the only caller that means anything
     * else is the bundle apply, and it says so explicitly.
     */
    public PriceSpec(UUID apiToolId, String modelId, String priceUnit, BigDecimal baseCredits,
                     BigDecimal unitCredits, BigDecimal minCredits, BigDecimal maxCredits) {
        this(apiToolId, modelId, priceUnit, baseCredits, unitCredits, minCredits, maxCredits,
                PriceSource.ADMIN);
    }

    /**
     * Normalise at the boundary so every downstream reader sees one shape:
     * a blank model id is the tool-level price (NULL, which is what the unique
     * index keys on), an unrecognised or missing unit degrades to {@code call},
     * and the two mandatory amounts default to zero rather than to a NOT NULL
     * violation three layers later.
     */
    public PriceSpec {
        if (source == null) source = PriceSource.ADMIN;
        if (modelId != null) {
            // Lower-cased as well as trimmed, because every READER of this id
            // lower-cases before looking it up while the lookup itself is an
            // exact match. A row published as "Seedance-2.0" would then be
            // unfindable: the price resolves to the credential-wide default
            // instead, which is a different amount nobody chose.
            modelId = modelId.trim().toLowerCase(Locale.ROOT);
            if (modelId.isEmpty()) modelId = null;
        }
        priceUnit = PriceUnit.fromWire(priceUnit).wire();
        if (baseCredits == null) baseCredits = BigDecimal.ZERO;
        if (unitCredits == null) unitCredits = BigDecimal.ZERO;
    }

    /** The pre-V428 shape: a flat per-call amount on an endpoint. */
    public static PriceSpec flat(UUID apiToolId, BigDecimal credits) {
        return new PriceSpec(apiToolId, null, PriceUnit.CALL.wire(),
                credits, BigDecimal.ZERO, null, null);
    }

    /** Materialise the row for an already-persisted pricing version. */
    public PricingVersionEntry toEntry(Long pricingVersionId) {
        PricingVersionEntry entry = new PricingVersionEntry();
        entry.setPricingVersionId(pricingVersionId);
        entry.setApiToolId(apiToolId);
        entry.setModelId(modelId);
        entry.setMarkupCredits(baseCredits);
        entry.setPriceUnit(priceUnit);
        entry.setUnitCredits(unitCredits);
        entry.setMinCredits(minCredits);
        entry.setMaxCredits(maxCredits);
        entry.setSource(source.wire());
        return entry;
    }

    /**
     * True when this price says the same thing as a persisted row: same amount,
     * same unit, same clamps, same owner.
     *
     * <p>What it is FOR: the bundle apply runs on every sync tick, and a version
     * is immutable, so publishing unconditionally would mint a new pricing
     * version every fifteen minutes for the rest of the install's life. That is
     * not merely noise, it walks the version history away from the decisions it
     * is supposed to record.
     *
     * <p>Amounts compare by VALUE, not by {@code equals}: the column is
     * {@code DECIMAL(12,6)}, so a row read back from Postgres carries scale 6
     * and {@code new BigDecimal("60")} does not, and {@code BigDecimal.equals}
     * calls those two different. Every tick would then look like a change.
     */
    public boolean matches(PricingVersionEntry entry) {
        return entry != null
                && java.util.Objects.equals(modelId, entry.getModelId())
                && priceUnit.equals(entry.unit().wire())
                && source == entry.origin()
                && sameAmount(baseCredits, entry.getMarkupCredits())
                && sameAmount(unitCredits, entry.getUnitCredits())
                && sameAmount(minCredits, entry.getMinCredits())
                && sameAmount(maxCredits, entry.getMaxCredits());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }
}
