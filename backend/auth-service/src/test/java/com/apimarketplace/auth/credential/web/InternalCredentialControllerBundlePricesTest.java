package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PriceSource;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService.BundlePriceApplyResult;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wire contract of the two endpoints that let a generation price travel in the
 * signed API-catalog bundle: the publisher read and the consumer apply.
 *
 * <p>The one idea both sides turn on: the key is the INTEGRATION NAME, because
 * the platform credential's id is a serial that means something different on
 * every install.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalCredentialController - publishing and applying bundle-carried generation prices")
class InternalCredentialControllerBundlePricesTest {

    @Mock private InternalCredentialService credentialService;
    @Mock private CredentialService userCredentialService;
    @Mock private PlatformCredentialService platformCredentialService;
    @Mock private PlatformCredentialPricingService pricingService;
    @Mock private PricingVersionService pricingVersionService;
    @Mock private CredentialEncryptionService encryptionService;

    @InjectMocks private InternalCredentialController controller;

    private static final Long SEEDANCE_ID = 12L;
    private static final String TOOL_ID = "624c2566-d8d1-457c-a77b-0b29fcafdeac";

    private static PlatformCredential credential(Long id, String integrationName) {
        return new PlatformCredential(id, integrationName, integrationName, AuthType.API_KEY,
                null, null, null, null, null, null, null, null, null, null, null,
                true, null, BigDecimal.ZERO, 100, null, null, null, null);
    }

    private static PricingVersionEntry entry(String modelId, String unit, String base, String perUnit) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setApiToolId(UUID.fromString(TOOL_ID));
        e.setModelId(modelId);
        e.setPriceUnit(unit);
        e.setMarkupCredits(new BigDecimal(base).setScale(6));
        e.setUnitCredits(new BigDecimal(perUnit).setScale(6));
        e.setSource(PriceSource.ADMIN.wire());
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> prices(ResponseEntity<Map<String, Object>> resp) {
        return (List<Map<String, Object>>) resp.getBody().get("prices");
    }

    // ── publisher ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Published prices come back keyed by integration name, with amounts as plain "
            + "strings so the bytes that get signed cannot drift")
    void publishedPricesAreKeyedByIntegrationName() {
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.findLatestPublishedPrices(eq(SEEDANCE_ID), anySet()))
                .thenReturn(List.of(entry("seedance-2.0", "second", "0", "60")));

        var resp = controller.publishedPrices(
                new InternalCredentialController.PublishedPricesRequest(
                        List.of("seedance"), List.of(TOOL_ID)));

        assertThat(prices(resp)).hasSize(1);
        Map<String, Object> row = prices(resp).get(0);
        assertThat(row).containsEntry("integrationName", "seedance");
        assertThat(row).containsEntry("apiToolId", TOOL_ID);
        assertThat(row).containsEntry("modelId", "seedance-2.0");
        assertThat(row).containsEntry("priceUnit", "second");
        assertThat(row.get("unitCredits")).isInstanceOf(String.class);
        assertThat(row).containsEntry("unitCredits", "60.000000");
        // Absent clamps stay absent: a zero ceiling would cap every generation
        // at nothing.
        assertThat(row).doesNotContainKey("maxCredits");
    }

    @Test
    @DisplayName("An integration with no platform credential simply yields no price - nothing is "
            + "resold for a key that does not exist")
    void unknownIntegrationYieldsNoPrice() {
        when(platformCredentialService.getRawCredential("seedance")).thenReturn(Optional.empty());

        var resp = controller.publishedPrices(
                new InternalCredentialController.PublishedPricesRequest(
                        List.of("seedance"), List.of(TOOL_ID)));

        assertThat(prices(resp)).isEmpty();
        verify(pricingService, never()).findLatestPublishedPrices(any(), anySet());
    }

    @Test
    @DisplayName("Naming no endpoint publishes nothing, never everything")
    void namingNoEndpointPublishesNothing() {
        // The caller names the generation endpoints it found. "I found none"
        // must not turn into "ship the owner's entire price list to every
        // install".
        var resp = controller.publishedPrices(
                new InternalCredentialController.PublishedPricesRequest(List.of("seedance"), List.of()));

        assertThat(prices(resp)).isEmpty();
        verify(pricingService, never()).findLatestPublishedPrices(any(), anySet());
    }

    // ── consumer ────────────────────────────────────────────────────────────

    private static InternalCredentialController.BundlePriceEntry wire(
            String integration, String modelId, String unit, String base, String perUnit) {
        return new InternalCredentialController.BundlePriceEntry(
                integration, TOOL_ID, modelId, unit,
                new BigDecimal(base), new BigDecimal(perUnit), null, null);
    }

    @SuppressWarnings("unchecked")
    private List<PriceSpec> appliedTo(Long credentialId) {
        ArgumentCaptor<List<PriceSpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(pricingService).applyBundlePrices(eq(credentialId), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("Applied prices are marked as the BUNDLE's, whatever the wire said, so the next "
            + "bundle can still update them")
    void appliedPricesAreMarkedAsTheBundles() {
        // The provenance decides whether the NEXT bundle may replace the row, so
        // it is forced here rather than trusted from a payload.
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.applyBundlePrices(eq(SEEDANCE_ID), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 501L, 2, 1, 0));

        var resp = controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(
                        42L, List.of(wire("seedance", "seedance-2.0", "second", "0", "60"))));

        PriceSpec sent = appliedTo(SEEDANCE_ID).get(0);
        assertThat(sent.source()).isEqualTo(PriceSource.BUNDLE);
        assertThat(sent.modelId()).isEqualTo("seedance-2.0");
        assertThat(sent.unitCredits()).isEqualByComparingTo("60");
        assertThat(resp.getBody()).containsEntry("publishedCredentials", 1);
        assertThat(resp.getBody()).containsEntry("appliedPrices", 1);
    }

    @Test
    @DisplayName("The published version names the bundle that wrote it")
    void publishedVersionNamesTheBundle() {
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.applyBundlePrices(eq(SEEDANCE_ID), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 501L, 2, 1, 0));

        controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(
                        42L, List.of(wire("seedance", "seedance-2.0", "second", "0", "60"))));

        ArgumentCaptor<String> createdBy = ArgumentCaptor.forClass(String.class);
        verify(pricingService).applyBundlePrices(eq(SEEDANCE_ID), anyList(), createdBy.capture());
        assertThat(createdBy.getValue()).isEqualTo("api-catalog-bundle v42");
    }

    @Test
    @DisplayName("A producer that is not a bundle names ITSELF, and its rows are still the BUNDLE's")
    void anotherProducerNamesItselfWithoutChangingTheProvenance() {
        // The boot-time generation seed of a self-hosted install writes the same
        // rows with no bundle in existence. Its version number is a seed version,
        // so borrowing this endpoint's default label would point an operator at a
        // bundle that never existed. The provenance is unaffected: BUNDLE still
        // means "not decided here", which is what keeps an admin row preserved.
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.applyBundlePrices(eq(SEEDANCE_ID), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 501L, 2, 1, 0));

        controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(
                        2012L, List.of(wire("seedance", "seedance-2.0", "second", "0", "60")),
                        "generation-seed"));

        ArgumentCaptor<String> createdBy = ArgumentCaptor.forClass(String.class);
        verify(pricingService).applyBundlePrices(eq(SEEDANCE_ID), anyList(), createdBy.capture());
        assertThat(createdBy.getValue()).isEqualTo("generation-seed v2012");
        assertThat(appliedTo(SEEDANCE_ID).get(0).source()).isEqualTo(PriceSource.BUNDLE);
    }

    @Test
    @DisplayName("A blank origin falls back to the bundle, so an older caller is unchanged")
    void aBlankOriginFallsBackToTheBundle() {
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.applyBundlePrices(eq(SEEDANCE_ID), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 501L, 2, 1, 0));

        controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(
                        42L, List.of(wire("seedance", "seedance-2.0", "second", "0", "60")), "  "));

        ArgumentCaptor<String> createdBy = ArgumentCaptor.forClass(String.class);
        verify(pricingService).applyBundlePrices(eq(SEEDANCE_ID), anyList(), createdBy.capture());
        assertThat(createdBy.getValue()).isEqualTo("api-catalog-bundle v42");
    }

    @Test
    @DisplayName("An integration this install has no platform key for is SKIPPED, not failed - "
            + "nothing is resold on a key that does not exist")
    void integrationWithoutAKeyIsSkipped() {
        when(platformCredentialService.getRawCredential("seedance")).thenReturn(Optional.empty());

        var resp = controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(
                        42L, List.of(wire("seedance", "seedance-2.0", "second", "0", "60"))));

        assertThat(resp.getBody()).containsEntry("skippedIntegrations", 1);
        assertThat(resp.getBody()).containsEntry("publishedCredentials", 0);
        assertThat((List<?>) resp.getBody().get("failures")).isEmpty();
        verify(pricingService, never()).applyBundlePrices(any(), anyList(), any());
    }

    @Test
    @DisplayName("One integration whose prices are rejected does not cost the others theirs")
    void oneRejectedIntegrationDoesNotBlockTheRest() {
        // The realistic rejection is a bundle row that would move a live price
        // to a unit nothing measures for it, which MarkupPolicy refuses.
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(platformCredentialService.getRawCredential("elevenlabs"))
                .thenReturn(Optional.of(credential(13L, "elevenlabs")));
        when(pricingService.applyBundlePrices(eq(SEEDANCE_ID), anyList(), any()))
                .thenThrow(new IllegalArgumentException("cannot publish toolId=x per second"));
        when(pricingService.applyBundlePrices(eq(13L), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 502L, 3, 1, 0));

        var resp = controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(42L, List.of(
                        wire("seedance", "seedance-2.0", "second", "0", "60"),
                        wire("elevenlabs", null, "character", "0", "2"))));

        assertThat(resp.getBody()).containsEntry("publishedCredentials", 1);
        assertThat((List<?>) resp.getBody().get("failures")).hasSize(1);
        assertThat(String.valueOf(((List<?>) resp.getBody().get("failures")).get(0)))
                .contains("seedance");
    }

    @Test
    @DisplayName("A row with an unparseable endpoint is dropped, and its integration is not "
            + "repriced on the strength of the rows that survived alone")
    void unparseableEndpointRowIsDropped() {
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));

        var resp = controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(42L, List.of(
                        new InternalCredentialController.BundlePriceEntry(
                                "seedance", "not-a-uuid", "seedance-2.0", "second",
                                BigDecimal.ZERO, new BigDecimal("60"), null, null))));

        assertThat(resp.getBody()).containsEntry("publishedCredentials", 0);
        verify(pricingService, never()).applyBundlePrices(any(), anyList(), any());
    }

    @Test
    @DisplayName("An empty or absent price list is a no-op - a bundle from an older cloud must not "
            + "touch local pricing")
    void emptyRequestIsANoOp() {
        var empty = controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(42L, List.of()));
        var missing = controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(42L, null));

        assertThat(empty.getBody()).containsEntry("publishedCredentials", 0);
        assertThat(missing.getBody()).containsEntry("publishedCredentials", 0);
        verify(pricingService, never()).applyBundlePrices(any(), anyList(), any());
    }

    @Test
    @DisplayName("A row whose unit this build cannot price is SKIPPED, never republished at a flat rate")
    void unknownUnitSkipsTheRowRatherThanFlatteningIt() {
        // The payload is signed, so an unknown unit is a NEWER cloud and not a
        // typo. That reading is right; degrading the row to 'call' was not.
        // Flattening KEEPS unitCredits and throws away what it counts, so a
        // per-second row at 60 bills 60 for a ten second clip instead of 600,
        // always in the undercharging direction, and validateUnitChange accepts
        // "-> CALL" unconditionally so the degraded row REPLACES the correct
        // live one. Skipping the row lets carry-forward keep that last
        // known-good price instead.
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.applyBundlePrices(eq(SEEDANCE_ID), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 501L, 2, 1, 0));

        controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(
                        42L, List.of(wire("seedance", "future-model", "parsec", "9", "0"))));

        verify(pricingService, never()).applyBundlePrices(eq(SEEDANCE_ID), anyList(), any());
    }

    @Test
    @DisplayName("One unpriceable row does not take its SIBLINGS down with it")
    void anUnknownUnitCostsOnlyItsOwnRow() {
        // The whole reason the old behaviour existed: dropping the integration
        // would lose prices that are perfectly readable. Both goals hold at
        // once, which is what made "degrade or drop everything" a false pair.
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.applyBundlePrices(eq(SEEDANCE_ID), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 501L, 2, 1, 0));

        controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(42L, List.of(
                        wire("seedance", "future-model", "parsec", "9", "0"),
                        wire("seedance", "seedance-2.0", "second", "0", "60"))));

        var applied = appliedTo(SEEDANCE_ID);
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).modelId()).isEqualTo("seedance-2.0");
        assertThat(applied.get(0).priceUnit()).isEqualTo("second");
    }

    @Test
    @DisplayName("A row that names NO unit is flat, which is an answer and not an unknown")
    void anAbsentUnitStillPublishesAsFlat() {
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.applyBundlePrices(eq(SEEDANCE_ID), anyList(), any()))
                .thenReturn(new BundlePriceApplyResult(true, 501L, 1, 0, 0));

        controller.applyCatalogBundlePrices(
                new InternalCredentialController.ApplyBundlePricesRequest(
                        42L, List.of(wire("seedance", "flat-model", null, "12", "0"))));

        assertThat(appliedTo(SEEDANCE_ID).get(0).priceUnit()).isEqualTo("call");
    }

    @Test
    @DisplayName("A repeated endpoint is asked about once - the publisher read is de-duplicated")
    void publisherIsAskedWithADeduplicatedSet() {
        when(platformCredentialService.getRawCredential("seedance"))
                .thenReturn(Optional.of(credential(SEEDANCE_ID, "seedance")));
        when(pricingService.findLatestPublishedPrices(eq(SEEDANCE_ID), anySet()))
                .thenReturn(List.of());

        controller.publishedPrices(new InternalCredentialController.PublishedPricesRequest(
                List.of("seedance"), List.of(TOOL_ID, TOOL_ID)));

        ArgumentCaptor<Set<UUID>> captor = ArgumentCaptor.forClass(Set.class);
        verify(pricingService).findLatestPublishedPrices(eq(SEEDANCE_ID), captor.capture());
        // A repeated endpoint is asked about once.
        assertThat(captor.getValue()).containsExactly(UUID.fromString(TOOL_ID));
    }
}
