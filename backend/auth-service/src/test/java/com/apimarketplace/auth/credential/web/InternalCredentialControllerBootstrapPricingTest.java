package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
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
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V428 wire contract of {@code POST /pricing-versions/bootstrap}, the single
 * seeding path the catalog importer uses. Before this the request could only
 * carry one flat amount per endpoint, so the starting prices declared on
 * generation models had no way to reach the database at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCredentialController - pricing-version bootstrap accepts unit and per-model prices")
class InternalCredentialControllerBootstrapPricingTest {

    @Mock
    private InternalCredentialService credentialService;
    @Mock
    private CredentialService userCredentialService;
    @Mock
    private PlatformCredentialService platformCredentialService;
    @Mock
    private PlatformCredentialPricingService pricingService;
    @Mock
    private PricingVersionService pricingVersionService;
    @Mock
    private CredentialEncryptionService encryptionService;

    @InjectMocks
    private InternalCredentialController controller;

    private static final Long CRED_ID = 12L;
    private static final String TOOL_ID = "624c2566-d8d1-457c-a77b-0b29fcafdeac";

    private void stubPublished() {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(500L);
        v.setPlatformCredentialId(CRED_ID);
        v.setVersion(1);
        when(pricingService.bootstrapV1IfAbsent(eq(CRED_ID), any(), anyList(), any())).thenReturn(v);
    }

    @SuppressWarnings("unchecked")
    private List<PriceSpec> capturePrices() {
        ArgumentCaptor<List<PriceSpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(pricingService).bootstrapV1IfAbsent(eq(CRED_ID), any(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("a generation entry reaches the service with its model, unit, rate and floor intact")
    void entryCarriesTheFullPriceShape() {
        stubPublished();

        ResponseEntity<Map<String, Object>> response = controller.bootstrapPricingVersion(
                new InternalCredentialController.BootstrapPricingRequest(
                        CRED_ID, null, null,
                        List.of(new InternalCredentialController.BootstrapPriceEntry(
                                TOOL_ID, "seedance-2.0", "second",
                                BigDecimal.ZERO, new BigDecimal("60"),
                                new BigDecimal("8"), new BigDecimal("900"))),
                        "ApiMigrationImporter"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("created", true);
        PriceSpec price = capturePrices().get(0);
        assertThat(price.apiToolId()).isEqualTo(UUID.fromString(TOOL_ID));
        assertThat(price.modelId()).isEqualTo("seedance-2.0");
        assertThat(price.priceUnit()).isEqualTo("second");
        assertThat(price.unitCredits()).isEqualByComparingTo("60");
        assertThat(price.baseCredits()).isEqualByComparingTo("0");
        assertThat(price.minCredits()).isEqualByComparingTo("8");
        assertThat(price.maxCredits()).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("the legacy perToolOverrides map still publishes the same flat price, so an old caller keeps working")
    void legacyFlatMapStillWorks() {
        stubPublished();

        controller.bootstrapPricingVersion(
                new InternalCredentialController.BootstrapPricingRequest(
                        CRED_ID, new BigDecimal("34"),
                        Map.of(TOOL_ID, new BigDecimal("133")),
                        null, "ApiMigrationImporter"));

        PriceSpec price = capturePrices().get(0);
        assertThat(price.apiToolId()).isEqualTo(UUID.fromString(TOOL_ID));
        // No model, no unit, nothing per-unit: bills 133 exactly as before V428.
        assertThat(price.modelId()).isNull();
        assertThat(price.priceUnit()).isEqualTo("call");
        assertThat(price.baseCredits()).isEqualByComparingTo("133");
        assertThat(price.unitCredits()).isEqualByComparingTo("0");
        assertThat(price.minCredits()).isNull();
        assertThat(price.maxCredits()).isNull();
    }

    @Test
    @DisplayName("both fields in one request are merged rather than one silently replacing the other")
    void mergesLegacyMapAndEntries() {
        stubPublished();
        String otherTool = "709dcd2c-52eb-4f34-9c8c-2389d5f7af6e";

        controller.bootstrapPricingVersion(
                new InternalCredentialController.BootstrapPricingRequest(
                        CRED_ID, null,
                        Map.of(otherTool, new BigDecimal("9")),
                        List.of(new InternalCredentialController.BootstrapPriceEntry(
                                TOOL_ID, "seedance-2.0", "second",
                                null, new BigDecimal("60"), null, null)),
                        "ApiMigrationImporter"));

        assertThat(capturePrices()).hasSize(2)
                .extracting(PriceSpec::modelId)
                .containsExactly(null, "seedance-2.0");
    }

    @Test
    @DisplayName("an entry with an unusable apiToolId is dropped, and the remaining prices are still published")
    void skipsUnusableToolIdWithoutLosingTheRest() {
        // Catalog drift on one endpoint must not cost the whole provider its
        // prices - the bootstrap runs once and never overwrites, so a request
        // rejected wholesale would leave the credential unpriced for good.
        stubPublished();

        controller.bootstrapPricingVersion(
                new InternalCredentialController.BootstrapPricingRequest(
                        CRED_ID, null, null,
                        List.of(new InternalCredentialController.BootstrapPriceEntry(
                                        "not-a-uuid", "ghost", "second",
                                        null, BigDecimal.ONE, null, null),
                                new InternalCredentialController.BootstrapPriceEntry(
                                        TOOL_ID, "seedance-2.0", "second",
                                        null, new BigDecimal("60"), null, null)),
                        "ApiMigrationImporter"));

        assertThat(capturePrices()).hasSize(1)
                .singleElement()
                .extracting(PriceSpec::modelId).isEqualTo("seedance-2.0");
    }

    @Test
    @DisplayName("a request without a credentialId is refused before any publish is attempted")
    void refusesMissingCredentialId() {
        ResponseEntity<Map<String, Object>> response = controller.bootstrapPricingVersion(
                new InternalCredentialController.BootstrapPricingRequest(
                        null, null, null, null, "ApiMigrationImporter"));

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        verify(pricingService, never()).bootstrapV1IfAbsent(any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("a rejected price list answers 400 with the reason instead of a 500")
    void surfacesValidationErrorAsBadRequest() {
        when(pricingService.bootstrapV1IfAbsent(eq(CRED_ID), any(), anyList(), any()))
                .thenThrow(new IllegalArgumentException("duplicate price for toolId=x, modelId=dup"));

        ResponseEntity<Map<String, Object>> response = controller.bootstrapPricingVersion(
                new InternalCredentialController.BootstrapPricingRequest(
                        CRED_ID, null, null,
                        List.of(new InternalCredentialController.BootstrapPriceEntry(
                                TOOL_ID, "dup", "second", null, BigDecimal.ONE, null, null)),
                        "ApiMigrationImporter"));

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody()).containsKey("error");
    }
}
