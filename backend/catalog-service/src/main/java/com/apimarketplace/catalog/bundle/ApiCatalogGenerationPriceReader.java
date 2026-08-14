package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.GenerationPriceRow;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.BundleGenerationPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Cloud-side: read the published generation prices that belong in a bundle.
 *
 * <p><b>Why this is an HTTP hop and not a join.</b> The descriptor lives in
 * {@code catalog.api_tools} and the price lives in
 * {@code auth.pricing_version_entry}: two schemas owned by two services, and a
 * service only queries its own. So the two halves of a generation's economics
 * meet here, over {@code credential-client}, rather than in a SQL statement that
 * would be the exact cross-schema read the architecture forbids.
 *
 * <p><b>Scope.</b> Only endpoints that carry a generation descriptor in THIS
 * snapshot, and only the integrations those endpoints belong to. The platform
 * owner's rates for the 700 ordinary catalog endpoints are their own commercial
 * business and are never distributed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiCatalogGenerationPriceReader {

    private final CredentialClient credentialClient;

    /**
     * The prices to carry, or an empty list when there are none to carry.
     *
     * <p>Empty is a legitimate answer with three ordinary causes: the catalog
     * has no generation endpoint, the owner has published no price for one, or
     * auth-service was unreachable. All three produce a bundle that carries no
     * price update, which is exactly what every bundle built before V430 did,
     * so the failure mode is "prices are not refreshed this build", never
     * "prices are wrong". A wrong price would be signed and distributed, so the
     * asymmetry is deliberate.
     */
    public List<GenerationPriceRow> read(ApiCatalogSnapshotReader.Snapshot snapshot) {
        if (snapshot == null) return List.of();
        var toolIds = snapshot.generationToolIds();
        var integrations = snapshot.generationIntegrationNames();
        if (toolIds.isEmpty() || integrations.isEmpty()) {
            log.debug("API catalog bundle: no priced generation endpoints to carry "
                    + "({} generation endpoint(s), {} integration(s))", toolIds.size(), integrations.size());
            return List.of();
        }

        List<BundleGenerationPriceDto> published =
                credentialClient.fetchPublishedGenerationPrices(integrations, toolIds);
        List<GenerationPriceRow> rows = new ArrayList<>(published.size());
        for (BundleGenerationPriceDto p : published) {
            if (p == null || p.integrationName() == null || p.apiToolId() == null) continue;
            rows.add(new GenerationPriceRow(
                    p.integrationName(), p.apiToolId(), p.modelId(),
                    p.priceUnit() == null ? "call" : p.priceUnit(),
                    plain(p.baseCredits()), plain(p.unitCredits()),
                    p.minCredits() == null ? null : plain(p.minCredits()),
                    p.maxCredits() == null ? null : plain(p.maxCredits())));
        }
        log.info("API catalog bundle: carrying {} published generation price(s) across {} integration(s)",
                rows.size(), integrations.size());
        return rows;
    }

    /**
     * Plain decimal text, never scientific notation. These bytes are signed and
     * compared for equality on the far side, so the rendering has to be the one
     * thing about the number that cannot vary.
     */
    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }
}
