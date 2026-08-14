package com.apimarketplace.credential.client.dto;

import java.math.BigDecimal;

/**
 * Response from {@code GET /api/internal/credentials/resolve-markup} -
 * the per-call markup rate for a pinned pricing version + api tool pair.
 * Consumed on every platform-sourced MCP debit in the orchestrator.
 *
 * <p>{@code found=false} means the pricing version id was unknown (stale
 * pin or admin-cancelled pricing); callers must skip markup billing.
 */
public class FrozenMarkupDto {

    private boolean found;
    private Long pricingVersionId;
    private Long credentialId;
    private Integer version;
    private BigDecimal effectiveMarkup;

    /**
     * Unit the published row prices by: call, second, minute, image, character.
     *
     * <p>This endpoint carries NO quantity, so a caller that receives anything
     * other than {@code call} has been handed the amount for ONE unit, not the
     * amount for the call it is about to make.
     */
    private String priceUnit;

    /**
     * Credits per unit on the published row, zero for a flat price.
     *
     * <p>Sent so a caller can tell "the admin chose this flat amount" apart from
     * "this amount is a fraction of the real price because nobody here could
     * measure the call". Without it the two are indistinguishable, and a
     * per-second video is charged as one second.
     */
    private BigDecimal unitCredits;

    /**
     * True when a price was published for THIS endpoint, false when the amount
     * fell back to the credential-wide default.
     *
     * <p>A default arrives here reporting {@code "call"} and zero unit credits,
     * because a version carries no unit. That is indistinguishable from a flat
     * price the owner chose on purpose, which is fine for the ordinary
     * endpoints of an API and wrong for a generation: the same catch-all would
     * sell a video for what the owner meant a lookup to cost.
     *
     * <p>NULL is a third answer and is not the same as false: it means the
     * responding service is older than this field and did not answer at all.
     * Reading that as "the default applied" would refuse every resold
     * generation for as long as a rolling deploy has one old peer left, which
     * trades a latent mispricing for an outage. So null keeps the behaviour
     * that shipped before the flag existed, and only an explicit false refuses.
     */
    private Boolean pricedByPublishedRow;

    public FrozenMarkupDto() {
    }

    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }
    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }
    public Long getCredentialId() { return credentialId; }
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public BigDecimal getEffectiveMarkup() { return effectiveMarkup; }
    public void setEffectiveMarkup(BigDecimal effectiveMarkup) { this.effectiveMarkup = effectiveMarkup; }
    public String getPriceUnit() { return priceUnit; }
    public void setPriceUnit(String priceUnit) { this.priceUnit = priceUnit; }
    public BigDecimal getUnitCredits() { return unitCredits; }
    public void setUnitCredits(BigDecimal unitCredits) { this.unitCredits = unitCredits; }
    public Boolean getPricedByPublishedRow() { return pricedByPublishedRow; }
    public void setPricedByPublishedRow(Boolean pricedByPublishedRow) { this.pricedByPublishedRow = pricedByPublishedRow; }

    /**
     * True only when the responder said outright that this amount came from the
     * credential-wide default. A missing answer is not that statement.
     */
    public boolean isKnownToBeVersionDefault() { return Boolean.FALSE.equals(pricedByPublishedRow); }

    /**
     * True when this amount prices per unit but was resolved without a quantity,
     * so it covers ONE unit rather than the call. A caller that cannot supply a
     * quantity must refuse rather than charge it.
     */
    public boolean isPricedPerUnitWithoutQuantity() {
        return unitCredits != null && unitCredits.signum() > 0;
    }

    /**
     * Whether this published rate can price a call measured in {@code
     * measuredUnit}, in the PLATFORM's own units.
     *
     * <p>The relayed path needs this for the same reason the direct one does,
     * and it is the ONLY path where the question can still be asked: the
     * publish-time guard arms only on a re-publish, so a first-published "per
     * image" row on a model measured in seconds reaches here unchallenged.
     * Without the check the install is billed the per-image rate times a count
     * of seconds, and because the quote reads the same resolver, both ends
     * report the same wrong number and neither reveals it.
     *
     * @see PriceDimensions for the rule and why it answers TRUE when it cannot tell
     */
    public boolean canPriceMeasurementIn(String measuredUnit) {
        return PriceDimensions.canPriceMeasurementIn(priceUnit, measuredUnit);
    }
}
