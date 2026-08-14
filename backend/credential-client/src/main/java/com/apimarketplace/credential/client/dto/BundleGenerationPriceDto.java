package com.apimarketplace.credential.client.dto;

import java.math.BigDecimal;

/**
 * One published generation price, in the shape it travels in the signed
 * API-catalog bundle (V430).
 *
 * <p><b>Why an integration name and not a credential id.</b> The price lives in
 * {@code auth.pricing_version_entry}, attached to a platform credential whose id
 * is a serial: it means something different on every install, so it cannot cross
 * one. The integration name can - it is the same key {@code apis.platform_credential_name}
 * stores on the catalog side and the same one {@code /platform/by-name} resolves,
 * so the receiving install re-attaches the price to the credential its own
 * billing path will bill against.
 *
 * <p>The {@code apiToolId} carries across for the opposite reason: catalog tool
 * UUIDs are STABLE by contract (the api-migrations seed pins them), which is
 * what makes a price row addressable at all.
 */
public record BundleGenerationPriceDto(
        String integrationName,
        String apiToolId,
        String modelId,
        String priceUnit,
        BigDecimal baseCredits,
        BigDecimal unitCredits,
        BigDecimal minCredits,
        BigDecimal maxCredits) {
}
