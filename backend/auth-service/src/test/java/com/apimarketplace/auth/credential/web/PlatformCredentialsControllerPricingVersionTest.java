package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialsController pricing version endpoints")
class PlatformCredentialsControllerPricingVersionTest {

    @Mock
    private PlatformCredentialService service;

    @Mock
    private PlatformCredentialPricingService pricingService;

    @Mock
    private CredentialService credentialService;

    @InjectMocks
    private PlatformCredentialsController controller;

    @Test
    @DisplayName("latest pricing response strips database DECIMAL scale from defaults and overrides")
    void latestPricingResponseStripsPersistedDecimalScale() {
        UUID toolId = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(42L, 7, new BigDecimal("0.250000"));
        when(pricingService.findLatest(42L)).thenReturn(Optional.of(version));
        when(pricingService.findOverrides(version.getId()))
                .thenReturn(Map.of(toolId, new BigDecimal("0.750000")));

        ResponseEntity<?> response = controller.getLatestPricingVersion("ADMIN", 42L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("defaultMarkupCredits")).isEqualTo("0.25");
        @SuppressWarnings("unchecked")
        Map<String, String> overrides = (Map<String, String>) body.get("overrides");
        assertThat(overrides).containsEntry(toolId.toString(), "0.75");
    }

    @Test
    @DisplayName("latest pricing response keeps an explicit JSON null default for override-only versions")
    void latestPricingResponseKeepsExplicitNullDefault() {
        PlatformCredentialPricingVersion version = pricingVersion(42L, 8, null);
        when(pricingService.findLatest(42L)).thenReturn(Optional.of(version));
        when(pricingService.findOverrides(version.getId())).thenReturn(Map.of());

        ResponseEntity<?> response = controller.getLatestPricingVersion("ADMIN", 42L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("defaultMarkupCredits");
        assertThat(body.get("defaultMarkupCredits")).isSameAs(NullNode.getInstance());
    }

    // ========== READ: the admin has to be able to SEE what is published ==========

    @Test
    @DisplayName("pricing response reports two models of one endpoint as two distinct prices")
    void pricingResponseKeepsBothModelsOfAnEndpoint() {
        // The read defect: an endpoint-keyed map let the second model overwrite
        // the first, so the admin was shown one price for two models.
        UUID videoTool = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(42L, 9, null);
        when(pricingService.findLatest(42L)).thenReturn(Optional.of(version));
        when(pricingService.findPrices(version.getId())).thenReturn(List.of(
                entry(videoTool, "seedance-2.0", "second", "0", "60", null, null),
                entry(videoTool, "seedance-2.0-fast", "second", "0", "30", null, null)));

        List<Map<String, Object>> prices = pricesOf(controller.getLatestPricingVersion("ADMIN", 42L));

        assertThat(prices).hasSize(2);
        assertThat(prices).extracting(p -> p.get("modelId"))
                .containsExactly("seedance-2.0", "seedance-2.0-fast");
        assertThat(prices).extracting(p -> p.get("unitCredits")).containsExactly("60", "30");
        assertThat(prices).allSatisfy(p -> {
            assertThat(p.get("apiToolId")).isEqualTo(videoTool.toString());
            assertThat(p.get("priceUnit")).isEqualTo("second");
        });
    }

    @Test
    @DisplayName("a unit-only price is reported with its rate and unit, never as a free call")
    void unitOnlyPriceIsNotReportedAsFree() {
        UUID videoTool = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(42L, 10, null);
        when(pricingService.findLatest(42L)).thenReturn(Optional.of(version));
        when(pricingService.findPrices(version.getId())).thenReturn(List.of(
                entry(videoTool, null, "second", "0", "60", "5", "900")));
        // The service already refuses to describe this row as one flat number.
        when(pricingService.findOverrides(version.getId())).thenReturn(Map.of());

        ResponseEntity<?> response = controller.getLatestPricingVersion("ADMIN", 42L);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();

        Map<String, Object> price = pricesOf(response).get(0);
        assertThat(price.get("baseCredits")).isEqualTo("0");
        assertThat(price.get("unitCredits")).isEqualTo("60");
        assertThat(price.get("priceUnit")).isEqualTo("second");
        assertThat(price.get("minCredits")).isEqualTo("5");
        assertThat(price.get("maxCredits")).isEqualTo("900");
        // The legacy map cannot express it, so it says nothing rather than "0".
        @SuppressWarnings("unchecked")
        Map<String, String> overrides = (Map<String, String>) body.get("overrides");
        assertThat(overrides).isEmpty();
    }

    @Test
    @DisplayName("a flat legacy price still appears in the legacy overrides map, unchanged")
    void flatPriceStillAppearsInTheLegacyMap() {
        UUID toolId = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(42L, 11, new BigDecimal("0.10"));
        when(pricingService.findLatest(42L)).thenReturn(Optional.of(version));
        when(pricingService.findOverrides(version.getId()))
                .thenReturn(Map.of(toolId, new BigDecimal("0.750000")));
        when(pricingService.findPrices(version.getId())).thenReturn(List.of(
                entry(toolId, null, "call", "0.75", "0", null, null)));

        ResponseEntity<?> response = controller.getLatestPricingVersion("ADMIN", 42L);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, String> overrides = (Map<String, String>) body.get("overrides");
        assertThat(overrides).containsEntry(toolId.toString(), "0.75");

        Map<String, Object> price = pricesOf(response).get(0);
        assertThat(price.get("modelId")).isNull();
        assertThat(price.get("priceUnit")).isEqualTo("call");
        assertThat(price.get("baseCredits")).isEqualTo("0.75");
        assertThat(price.get("unitCredits")).isEqualTo("0");
        assertThat(price.get("minCredits")).isNull();
    }

    // ========== WRITE: the admin has to be able to CHANGE it ==========

    @Test
    @DisplayName("a legacy flat overrides body publishes the same flat amount it always did")
    void legacyFlatBodyPublishesUnchanged() {
        stubPublish();

        controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                "defaultMarkupCredits", "0.05",
                "overrides", Map.of(TOOL_A.toString(), "0.75")));

        List<PriceSpec> published = capturePublished();
        assertThat(published).singleElement().satisfies(p -> {
            assertThat(p.apiToolId()).isEqualTo(TOOL_A);
            assertThat(p.modelId()).isNull();
            assertThat(p.priceUnit()).isEqualTo("call");
            assertThat(p.baseCredits()).isEqualByComparingTo("0.75");
            assertThat(p.unitCredits()).isEqualByComparingTo("0");
            assertThat(p.minCredits()).isNull();
            assertThat(p.maxCredits()).isNull();
        });
    }

    @Test
    @DisplayName("a per-model, per-unit price body reaches the service with all six numbers intact")
    void perModelUnitPriceBodyIsPublishedInFull() {
        stubPublish();

        controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                "prices", List.of(
                        priceBody(TOOL_A, "seedance-2.0", "second", "0", "60", null, null),
                        priceBody(TOOL_A, "seedance-2.0-fast", "second", "0", "30", "1", "900"))));

        List<PriceSpec> published = capturePublished();
        assertThat(published).hasSize(2);
        assertThat(published.get(0).modelId()).isEqualTo("seedance-2.0");
        assertThat(published.get(0).priceUnit()).isEqualTo("second");
        assertThat(published.get(0).unitCredits()).isEqualByComparingTo("60");
        assertThat(published.get(1).modelId()).isEqualTo("seedance-2.0-fast");
        assertThat(published.get(1).unitCredits()).isEqualByComparingTo("30");
        assertThat(published.get(1).minCredits()).isEqualByComparingTo("1");
        assertThat(published.get(1).maxCredits()).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("a misspelt unit is REFUSED, never quietly published as a flat per-call price")
    void unknownPriceUnitIsRejected() {
        // The lenient reader resolves anything it does not recognise to "call".
        // Applied on the way IN, that turns "seconds" into a flat price: a rate
        // of 60 per second becomes 60 per call, so a ten second clip bills 60
        // instead of 600. It is silent, it is always in the undercharging
        // direction, and it also collapses an existing per-second row, because
        // the dimension guard downstream compares the ALREADY coerced unit.
        var response = controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                "prices", List.of(priceBody(TOOL_A, "seedance-2.0", "seconds", "0", "60", null, null))));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().toString())
                .contains("seconds")
                .contains("second");
        verify(pricingService, never()).publishNextVersion(anyLong(), any(), anyList(), any(), anyBoolean());
    }

    @Test
    @DisplayName("every unit the platform actually prices by is still accepted, so the check is not a wall")
    void everyKnownUnitIsAccepted() {
        // The guard must reject what is not a unit, not narrow what is. If this
        // failed, publishing a per-image or per-character price would be
        // impossible and the whole audio and image catalogue unsellable.
        for (String unit : List.of("call", "second", "minute", "image", "character")) {
            stubPublish();
            var response = controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                    "prices", List.of(priceBody(TOOL_A, "m", unit, "0", "1", null, null))));

            assertThat(response.getStatusCode().value())
                    .as("unit %s must be publishable", unit)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("an absent unit still means a flat price, which is what every pre-V428 body sends")
    void absentPriceUnitIsStillAFlatPrice() {
        stubPublish();

        controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                "prices", List.of(priceBody(TOOL_A, "m", null, "12", "0", null, null))));

        assertThat(capturePublished()).singleElement()
                .satisfies(p -> assertThat(p.priceUnit()).isEqualTo("call"));
    }

    @Test
    @DisplayName("a blank model id publishes the endpoint-wide price, which is what every pre-V428 row is")
    void blankModelIdPublishesTheEndpointWidePrice() {
        stubPublish();

        controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                "prices", List.of(priceBody(TOOL_A, "  ", "call", "12", "0", null, null))));

        assertThat(capturePublished()).singleElement()
                .satisfies(p -> assertThat(p.modelId()).isNull());
    }

    @Test
    @DisplayName("without replace, publishing carries forward the rows the request does not mention")
    void publishCarriesForwardByDefault() {
        stubPublish();

        controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                "overrides", Map.of(TOOL_A.toString(), "0.75")));

        assertThat(captureCarryForward()).isTrue();
    }

    @Test
    @DisplayName("replace:true publishes the request's rows verbatim, so a deletion sticks")
    void replaceTurnsCarryForwardOff() {
        stubPublish();

        controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                "replace", true,
                "prices", List.of(priceBody(TOOL_A, null, "call", "1", "0", null, null))));

        assertThat(captureCarryForward()).isFalse();
    }

    @Test
    @DisplayName("a full price wins over a flat override naming the same endpoint - one row, not a duplicate")
    void richPriceWinsOverFlatOverrideForTheSameEndpoint() {
        stubPublish();

        controller.publishPricingVersion("ADMIN", "admin@example.com", 42L, Map.of(
                "overrides", Map.of(TOOL_A.toString(), "0.75"),
                "prices", List.of(priceBody(TOOL_A, null, "second", "0", "60", null, null))));

        assertThat(capturePublished()).singleElement().satisfies(p -> {
            assertThat(p.priceUnit()).isEqualTo("second");
            assertThat(p.unitCredits()).isEqualByComparingTo("60");
        });
    }

    @Test
    @DisplayName("rejects a prices field that is not an array instead of silently publishing nothing")
    void rejectsNonArrayPrices() {
        ResponseEntity<?> response = controller.publishPricingVersion(
                "ADMIN", "admin@example.com", 42L, Map.of("prices", "0.75"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(response.getBody())).contains("prices must be an array");
        verifyNoInteractions(pricingService);
    }

    @Test
    @DisplayName("rejects a price row with no apiToolId")
    void rejectsPriceWithoutTool() {
        ResponseEntity<?> response = controller.publishPricingVersion(
                "ADMIN", "admin@example.com", 42L,
                Map.of("prices", List.of(Map.of("modelId", "seedance-2.0"))));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(response.getBody())).contains("requires an apiToolId");
        verifyNoInteractions(pricingService);
    }

    @Test
    @DisplayName("rejects a replace flag that is neither true nor false rather than guessing which rows survive")
    void rejectsUnparseableReplaceFlag() {
        ResponseEntity<?> response = controller.publishPricingVersion(
                "ADMIN", "admin@example.com", 42L, Map.of(
                        "replace", "maybe",
                        "overrides", Map.of(TOOL_A.toString(), "0.75")));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(response.getBody())).contains("replace must be true or false");
        verifyNoInteractions(pricingService);
    }

    // ========== helpers ==========

    private static final UUID TOOL_A = UUID.randomUUID();

    private void stubPublish() {
        when(pricingService.publishNextVersion(eq(42L), any(), anyList(), any(), anyBoolean()))
                .thenReturn(pricingVersion(42L, 2, null));
    }

    @SuppressWarnings("unchecked")
    private List<PriceSpec> capturePublished() {
        ArgumentCaptor<List<PriceSpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(pricingService).publishNextVersion(eq(42L), any(), captor.capture(), any(), anyBoolean());
        return captor.getValue();
    }

    private boolean captureCarryForward() {
        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        verify(pricingService).publishNextVersion(eq(42L), any(), anyList(), any(), captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> priceBody(UUID toolId, String modelId, String unit,
                                                  String base, String rate, String min, String max) {
        Map<String, Object> row = new HashMap<>();
        row.put("apiToolId", toolId.toString());
        row.put("modelId", modelId);
        row.put("priceUnit", unit);
        row.put("baseCredits", base);
        row.put("unitCredits", rate);
        row.put("minCredits", min);
        row.put("maxCredits", max);
        return row;
    }

    private static PricingVersionEntry entry(UUID toolId, String modelId, String unit,
                                              String base, String rate, String min, String max) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setApiToolId(toolId);
        e.setModelId(modelId);
        e.setPriceUnit(unit);
        e.setMarkupCredits(new BigDecimal(base));
        e.setUnitCredits(new BigDecimal(rate));
        e.setMinCredits(min == null ? null : new BigDecimal(min));
        e.setMaxCredits(max == null ? null : new BigDecimal(max));
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pricesOf(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        return (List<Map<String, Object>>) body.get("prices");
    }

    private static PlatformCredentialPricingVersion pricingVersion(long credentialId, int versionNo, BigDecimal defaultMarkup) {
        PlatformCredentialPricingVersion version = new PlatformCredentialPricingVersion();
        version.setId(1000L + versionNo);
        version.setPlatformCredentialId(credentialId);
        version.setVersion(versionNo);
        version.setDefaultMarkupCredits(defaultMarkup);
        version.setCreatedAt(Instant.EPOCH);
        return version;
    }
}
