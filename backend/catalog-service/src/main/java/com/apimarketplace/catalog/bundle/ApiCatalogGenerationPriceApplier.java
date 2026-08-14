package com.apimarketplace.catalog.bundle;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.BundleGenerationPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CE-side: hand the generation prices a VERIFIED bundle carried to auth-service,
 * which owns {@code auth.pricing_version_entry}.
 *
 * <p><b>What this class does NOT decide.</b> Not whether the price is authentic
 * (the Ed25519 signature over the gzip bytes already settled that, before the
 * payload was even parsed), and not whether it may replace a local one (that is
 * the per-row {@code source} rule inside
 * {@code PlatformCredentialPricingService.applyBundlePrices}). It converts the
 * parsed rows and makes one call.
 *
 * <p><b>Never fails the catalog apply.</b> The endpoints in the bundle are worth
 * having with or without a price refresh, and the prices are re-offered on every
 * sync tick (auth publishes nothing when nothing changed), so an auth-service
 * blip costs a delay and not a failed catalog.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiCatalogGenerationPriceApplier {

    private final CredentialClient credentialClient;

    /** Summary of one apply, for the log line and the applier's own result. */
    public record Result(int offered, boolean delivered, Map<String, Object> detail) {
        static Result nothingToDo() {
            return new Result(0, false, Map.of());
        }
    }

    /**
     * Apply the {@code generationPrices} array of a verified payload.
     *
     * @param priceMaps the parsed array, or null when the bundle carried none
     *                  (every bundle built by a cloud older than V430, and any
     *                  cloud with nothing published to carry). Null and empty
     *                  are the SAME statement and both mean "this bundle says
     *                  nothing about prices" - never "this bundle says there are
     *                  no prices". A signed-but-priceless bundle must not be
     *                  able to unprice an install that is live and selling, so
     *                  there is deliberately no sweep here to match the one that
     *                  deprecates missing APIs.
     */
    public Result apply(List<Map<String, Object>> priceMaps, Long bundleVersion) {
        return apply(priceMaps, bundleVersion, null);
    }

    /**
     * The same apply, for a caller that is not the signed bundle.
     *
     * @param origin what to record as the author of the published pricing
     *               version, e.g. the boot-time generation seed. Null keeps the
     *               bundle's own label. The row's {@code source} is
     *               {@code bundle} either way, and deliberately so: it states
     *               that the price was not decided on this install, which is
     *               exactly as true of a shipped starting price as of a
     *               cloud-carried one. Only the audit label differs, and a seed
     *               that borrowed the bundle's label would send an operator
     *               looking for a bundle version that never existed.
     */
    public Result apply(List<Map<String, Object>> priceMaps, Long bundleVersion, String origin) {
        if (priceMaps == null || priceMaps.isEmpty()) {
            log.debug("API catalog bundle v{} carried no generation prices - leaving local pricing alone",
                    bundleVersion);
            return Result.nothingToDo();
        }
        List<BundleGenerationPriceDto> prices = new ArrayList<>(priceMaps.size());
        for (Map<String, Object> row : priceMaps) {
            if (row == null) continue;
            String integrationName = str(row, "integrationName");
            String apiToolId = str(row, "apiToolId");
            if (integrationName == null || apiToolId == null) continue;
            prices.add(new BundleGenerationPriceDto(
                    integrationName, apiToolId, str(row, "modelId"), str(row, "priceUnit"),
                    decimal(row, "baseCredits"), decimal(row, "unitCredits"),
                    decimal(row, "minCredits"), decimal(row, "maxCredits")));
        }
        if (prices.isEmpty()) {
            log.warn("API catalog bundle v{} carried {} price row(s), none usable (missing integration "
                    + "or endpoint) - local pricing unchanged", bundleVersion, priceMaps.size());
            return Result.nothingToDo();
        }

        try {
            Map<String, Object> detail = credentialClient
                    .applyCatalogBundlePrices(bundleVersion, prices, origin)
                    .orElse(null);
            if (detail == null) {
                log.warn("API catalog bundle v{}: {} generation price(s) could not be applied "
                        + "(auth-service unreachable) - retried on the next sync tick",
                        bundleVersion, prices.size());
                return new Result(prices.size(), false, Map.of());
            }
            log.info("API catalog bundle v{}: generation prices applied - {}", bundleVersion, detail);
            return new Result(prices.size(), true, detail);
        } catch (Exception e) {
            // The catalog itself has already landed by this point. Losing the
            // price refresh is a delay; losing the catalog would be an outage.
            log.warn("API catalog bundle v{}: applying {} generation price(s) threw - {}: {}",
                    bundleVersion, prices.size(), e.getClass().getSimpleName(), e.getMessage());
            return new Result(prices.size(), false, Map.of());
        }
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Amounts arrive as plain strings (that is how they were signed). A value
     * that will not parse is dropped rather than defaulted: guessing zero for a
     * corrupt rate would publish a free generation.
     */
    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
