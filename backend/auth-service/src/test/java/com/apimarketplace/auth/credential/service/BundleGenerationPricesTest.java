package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSource;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import com.apimarketplace.auth.credential.repository.WorkflowRunPricingPinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rule that decides what a signed API-catalog bundle may do to an install's
 * published generation prices.
 *
 * <p>Everything here is about one question: a bundle arrives every fifteen
 * minutes, forever, and the install's administrator can publish prices too. Who
 * wins, per row, and when is nothing written at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlatformCredentialPricingService.applyBundlePrices - bundle vs administrator, per row")
class BundleGenerationPricesTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private PlatformCredentialPricingVersionRepository versionRepo;
    @Mock private PricingVersionEntryRepository entryRepo;
    @Mock private PlatformCredentialRepository credentialRepo;
    @Mock private WorkflowRunPricingPinRepository pinRepo;

    private PlatformCredentialPricingService service;

    private static final Long CRED_ID = 10L;
    private static final Long LIVE_VERSION_ID = 500L;
    private static final Long NEW_VERSION_ID = 501L;
    private static final UUID TOOL = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_TOOL = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BUNDLE_CREATED_BY = "api-catalog-bundle v42";

    @BeforeEach
    void setUp() {
        service = new PlatformCredentialPricingService(
                jdbc, versionRepo, entryRepo, credentialRepo, pinRepo, new MarkupPolicy());
        when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(credential()));
        when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(1);
        when(versionRepo.save(any(PlatformCredentialPricingVersion.class))).thenAnswer(inv -> {
            PlatformCredentialPricingVersion v = inv.getArgument(0);
            v.setId(NEW_VERSION_ID);
            return v;
        });
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static PlatformCredential credential() {
        return new PlatformCredential(CRED_ID, "seedance", "Seedance", AuthType.API_KEY,
                null, null, null, null, null, null, null, null, null, null, null,
                true, null, BigDecimal.ZERO, 100, null, null, null, null);
    }

    private static PlatformCredentialPricingVersion liveVersion() {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(LIVE_VERSION_ID);
        v.setPlatformCredentialId(CRED_ID);
        v.setVersion(1);
        v.setDefaultMarkupCredits(new BigDecimal("0.10"));
        return v;
    }

    /** A published row, with the DECIMAL(12,6) scale Postgres hands back. */
    private static PricingVersionEntry row(UUID tool, String modelId, String unit,
                                           String base, String perUnit, PriceSource source) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setPricingVersionId(LIVE_VERSION_ID);
        e.setApiToolId(tool);
        e.setModelId(modelId);
        e.setPriceUnit(unit);
        e.setMarkupCredits(new BigDecimal(base).setScale(6));
        e.setUnitCredits(new BigDecimal(perUnit).setScale(6));
        e.setSource(source.wire());
        return e;
    }

    private static PriceSpec bundlePrice(UUID tool, String modelId, String unit,
                                          String base, String perUnit) {
        return new PriceSpec(tool, modelId, unit, new BigDecimal(base), new BigDecimal(perUnit),
                null, null, PriceSource.BUNDLE);
    }

    private void liveRows(PricingVersionEntry... rows) {
        when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(liveVersion()));
        when(entryRepo.findByPricingVersionId(LIVE_VERSION_ID)).thenReturn(List.of(rows));
    }

    @SuppressWarnings("unchecked")
    private List<PricingVersionEntry> publishedRows() {
        ArgumentCaptor<List<PricingVersionEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(entryRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    private static PricingVersionEntry find(List<PricingVersionEntry> rows, UUID tool, String modelId) {
        return rows.stream()
                .filter(r -> tool.equals(r.getApiToolId())
                        && java.util.Objects.equals(modelId, r.getModelId()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("no published row for " + tool + "/" + modelId));
    }

    // ── branches ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A never-priced install gets the cloud's price, marked as the bundle's, "
            + "so the generation it can see is one it can actually sell")
    void neverPricedInstallReceivesTheCloudPrice() {
        // This is the whole point of carrying prices in the bundle: an install
        // that never ran the importer has no pricing_version_entry row at all,
        // and an unpriced generation is refused by design.
        when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());
        when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);

        var result = service.applyBundlePrices(CRED_ID,
                List.of(bundlePrice(TOOL, "seedance-2.0", "second", "0", "60")), BUNDLE_CREATED_BY);

        assertThat(result.published()).isTrue();
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.preserved()).isZero();
        PricingVersionEntry published = find(publishedRows(), TOOL, "seedance-2.0");
        assertThat(published.getUnitCredits()).isEqualByComparingTo("60");
        assertThat(published.getPriceUnit()).isEqualTo("second");
        // Marked as the bundle's, which is what lets the NEXT bundle update it.
        assertThat(published.origin()).isEqualTo(PriceSource.BUNDLE);
    }

    @Test
    @DisplayName("A price the administrator published here is preserved, and the bundle's version "
            + "of that row is dropped")
    void locallyTunedPriceSurvivesTheBundle() {
        // The user_modified_fields guarantee, expressed per row: the operator
        // charges 90 for this model, the cloud says 60, and the operator's
        // decision is the one that stands.
        liveRows(row(TOOL, "seedance-2.0", "second", "0", "90", PriceSource.ADMIN));

        var result = service.applyBundlePrices(CRED_ID,
                List.of(bundlePrice(TOOL, "seedance-2.0", "second", "0", "60")), BUNDLE_CREATED_BY);

        assertThat(result.preserved()).isEqualTo(1);
        assertThat(result.applied()).isZero();
        // Nothing else changed, so the bundle publishes NOTHING rather than a
        // version that re-states what is already live.
        assertThat(result.published()).isFalse();
        verify(versionRepo, never()).save(any());
    }

    @Test
    @DisplayName("The administrator tuning ONE model does not freeze the others: an untouched "
            + "bundle row still takes the cloud's new price")
    void tuningOneModelDoesNotFreezeTheRest() {
        // Version-level provenance would answer "this install has local edits,
        // send it nothing" and the second model would never be repriced again.
        liveRows(row(TOOL, "seedance-2.0", "second", "0", "90", PriceSource.ADMIN),
                 row(TOOL, "seedance-2.0-fast", "second", "0", "30", PriceSource.BUNDLE));

        var result = service.applyBundlePrices(CRED_ID, List.of(
                bundlePrice(TOOL, "seedance-2.0", "second", "0", "60"),
                bundlePrice(TOOL, "seedance-2.0-fast", "second", "0", "35")), BUNDLE_CREATED_BY);

        assertThat(result.published()).isTrue();
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.preserved()).isEqualTo(1);
        List<PricingVersionEntry> rows = publishedRows();
        assertThat(find(rows, TOOL, "seedance-2.0").getUnitCredits()).isEqualByComparingTo("90");
        assertThat(find(rows, TOOL, "seedance-2.0").origin()).isEqualTo(PriceSource.ADMIN);
        assertThat(find(rows, TOOL, "seedance-2.0-fast").getUnitCredits()).isEqualByComparingTo("35");
    }

    @Test
    @DisplayName("Re-applying the same prices publishes nothing, so a 15-minute scheduler does not "
            + "mint a pricing version per tick")
    void unchangedPricesPublishNothing() {
        // The scale difference is the trap: Postgres hands back 60.000000 and
        // the bundle carries "60". BigDecimal.equals calls those different, so a
        // naive comparison would report a change on every single tick.
        liveRows(row(TOOL, "seedance-2.0", "second", "0", "60", PriceSource.BUNDLE));

        var result = service.applyBundlePrices(CRED_ID,
                List.of(bundlePrice(TOOL, "seedance-2.0", "second", "0", "60")), BUNDLE_CREATED_BY);

        assertThat(result.published()).isFalse();
        assertThat(result.applied()).isEqualTo(1);
        verify(versionRepo, never()).save(any());
    }

    @Test
    @DisplayName("A price the bundle does not mention is carried forward, never removed")
    void unmentionedPricesAreCarriedForward() {
        // A bundle is a snapshot of the GENERATION prices, not of the price
        // list. Treating silence as deletion would unprice endpoints that are
        // live and being sold.
        liveRows(row(OTHER_TOOL, null, "call", "34", "0", PriceSource.ADMIN),
                 row(TOOL, "seedance-2.0", "second", "0", "60", PriceSource.BUNDLE));

        service.applyBundlePrices(CRED_ID,
                List.of(bundlePrice(TOOL, "seedance-2.0", "second", "0", "75")), BUNDLE_CREATED_BY);

        List<PricingVersionEntry> rows = publishedRows();
        assertThat(rows).hasSize(2);
        assertThat(find(rows, OTHER_TOOL, null).getMarkupCredits()).isEqualByComparingTo("34");
        assertThat(find(rows, OTHER_TOOL, null).origin()).isEqualTo(PriceSource.ADMIN);
    }

    @Test
    @DisplayName("A bundle that carries no prices changes nothing - the older-cloud shape")
    void aBundleWithNoPricesChangesNothing() {
        // Every bundle built before this feature, and any cloud with nothing
        // published to carry. Silence must not be read as "there are no prices".
        liveRows(row(TOOL, "seedance-2.0", "second", "0", "60", PriceSource.BUNDLE));

        assertThat(service.applyBundlePrices(CRED_ID, List.of(), BUNDLE_CREATED_BY).published()).isFalse();
        assertThat(service.applyBundlePrices(CRED_ID, null, BUNDLE_CREATED_BY).published()).isFalse();

        verify(versionRepo, never()).save(any());
        // Not even the advisory lock is taken: there is nothing to serialise.
        verify(jdbc, never()).execute(any(String.class));
    }

    @Test
    @DisplayName("The version-wide default is carried forward - a catalog bundle prices "
            + "generations, not the install's ordinary calls")
    void versionDefaultIsCarriedForward() {
        liveRows(row(TOOL, "seedance-2.0", "second", "0", "60", PriceSource.BUNDLE));

        service.applyBundlePrices(CRED_ID,
                List.of(bundlePrice(TOOL, "seedance-2.0", "second", "0", "75")), BUNDLE_CREATED_BY);

        ArgumentCaptor<PlatformCredentialPricingVersion> captor =
                ArgumentCaptor.forClass(PlatformCredentialPricingVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getDefaultMarkupCredits()).isEqualByComparingTo("0.10");
        // The history says which bundle wrote it.
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(BUNDLE_CREATED_BY);
    }

    @Test
    @DisplayName("findLatestPublishedPrices answers only about the endpoints it was asked about, "
            + "so a bundle never carries the owner's ordinary rates")
    void publisherReadIsScopedToTheEndpointsAsked() {
        liveRows(row(TOOL, "seedance-2.0", "second", "0", "60", PriceSource.ADMIN),
                 row(OTHER_TOOL, null, "call", "34", "0", PriceSource.ADMIN));

        List<PricingVersionEntry> scoped =
                service.findLatestPublishedPrices(CRED_ID, Set.of(TOOL));
        assertThat(scoped).hasSize(1);
        assertThat(scoped.get(0).getApiToolId()).isEqualTo(TOOL);

        // An empty ask means "nothing", never "everything": the caller is
        // naming the generation endpoints it found, and finding none must not
        // publish the platform owner's whole price list to every install.
        assertThat(service.findLatestPublishedPrices(CRED_ID, Set.of())).isEmpty();
    }
}
