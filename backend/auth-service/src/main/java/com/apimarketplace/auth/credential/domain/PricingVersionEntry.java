package com.apimarketplace.auth.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-endpoint markup override attached to a pricing version.
 * Absence of an entry for a tool means "use version.defaultMarkupCredits".
 *
 * <p><b>V428 - unit pricing.</b> A row now expresses a two-part price:
 * <pre>
 *   price = markupCredits (base) + unitCredits x quantity,  clamped to [minCredits, maxCredits]
 * </pre>
 * where {@link PriceUnit} says what the quantity is counted in and which call
 * parameter supplies it. A pre-V428 row carries {@code priceUnit=CALL} and
 * {@code unitCredits=0}, which collapses the formula back to the flat
 * {@code markupCredits} it has always been.
 *
 * <p><b>V428 - per-model rows.</b> One endpoint can back several generation
 * models at different prices (a standard and a fast variant submitted through
 * the same endpoint). {@code modelId} discriminates them; a NULL {@code modelId}
 * is the tool-level price, which is what every row created before V428 is.
 */
@Entity
@Table(schema = "auth", name = "pricing_version_entry")
public class PricingVersionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pricing_version_id", nullable = false)
    private Long pricingVersionId;

    @Column(name = "api_tool_id", nullable = false)
    private UUID apiToolId;

    /** Generation model this price applies to. NULL = the tool-level price. */
    @Column(name = "model_id", length = 128)
    private String modelId;

    /** Base (fixed) component of the price, in credits. Historically the whole price. */
    @Column(name = "markup_credits", nullable = false, precision = 10, scale = 6)
    private BigDecimal markupCredits;

    @Column(name = "price_unit", length = 16, nullable = false)
    private String priceUnit = PriceUnit.CALL.wire();

    /** Variable component: credits charged per {@link #priceUnit}. */
    @Column(name = "unit_credits", nullable = false, precision = 12, scale = 6)
    private BigDecimal unitCredits = BigDecimal.ZERO;

    /** Optional floor applied after the formula. NULL = no floor. */
    @Column(name = "min_credits", precision = 12, scale = 6)
    private BigDecimal minCredits;

    /** Optional ceiling applied after the formula. NULL = no ceiling. */
    @Column(name = "max_credits", precision = 12, scale = 6)
    private BigDecimal maxCredits;

    /**
     * Who decided this price (V430). {@code admin} = published on this install,
     * {@code bundle} = written by an applied signed API-catalog bundle. Read by
     * the bundle apply path, which replaces its own rows and preserves the
     * administrator's, the same guarantee {@code user_modified_fields} gives the
     * LLM catalog.
     */
    @Column(name = "source", length = 16, nullable = false)
    private String source = PriceSource.ADMIN.wire();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }
    public UUID getApiToolId() { return apiToolId; }
    public void setApiToolId(UUID apiToolId) { this.apiToolId = apiToolId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public BigDecimal getMarkupCredits() { return markupCredits; }
    public void setMarkupCredits(BigDecimal markupCredits) { this.markupCredits = markupCredits; }
    public String getPriceUnit() { return priceUnit; }
    public void setPriceUnit(String priceUnit) { this.priceUnit = priceUnit; }
    public BigDecimal getUnitCredits() { return unitCredits; }
    public void setUnitCredits(BigDecimal unitCredits) { this.unitCredits = unitCredits; }
    public BigDecimal getMinCredits() { return minCredits; }
    public void setMinCredits(BigDecimal minCredits) { this.minCredits = minCredits; }
    public BigDecimal getMaxCredits() { return maxCredits; }
    public void setMaxCredits(BigDecimal maxCredits) { this.maxCredits = maxCredits; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    /** Typed view of {@link #priceUnit}, lenient on unknown values. */
    public PriceUnit unit() {
        return PriceUnit.fromWire(priceUnit);
    }

    /** Typed view of {@link #source}, lenient on unknown values. */
    public PriceSource origin() {
        return PriceSource.fromWire(source);
    }
}
