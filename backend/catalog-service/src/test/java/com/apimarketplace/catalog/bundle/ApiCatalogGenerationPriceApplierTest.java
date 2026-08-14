package com.apimarketplace.catalog.bundle;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.BundleGenerationPriceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The CE side of carrying prices: turn the parsed {@code generationPrices} array
 * of a VERIFIED bundle into one call to auth-service, and never let that call
 * take the catalog down with it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiCatalogGenerationPriceApplier - hand verified prices to auth, tolerate everything else")
class ApiCatalogGenerationPriceApplierTest {

    @Mock private CredentialClient credentialClient;

    private ApiCatalogGenerationPriceApplier applier;

    @BeforeEach
    void setUp() {
        applier = new ApiCatalogGenerationPriceApplier(credentialClient);
        when(credentialClient.applyCatalogBundlePrices(any(), anyList(), any()))
                .thenReturn(Optional.of(Map.of("publishedCredentials", 1)));
    }

    private static Map<String, Object> priceRow(String integration, String toolId, String modelId,
                                                 String unit, String base, String perUnit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("integrationName", integration);
        row.put("apiToolId", toolId);
        if (modelId != null) row.put("modelId", modelId);
        row.put("priceUnit", unit);
        row.put("baseCredits", base);
        row.put("unitCredits", perUnit);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<BundleGenerationPriceDto> delivered() {
        ArgumentCaptor<List<BundleGenerationPriceDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(credentialClient).applyCatalogBundlePrices(any(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("a bundle from an older cloud carries no prices, and that changes nothing")
    void olderShapeCarriesNoPricesAndIsANoOp() {
        // The compatibility posture, stated as a test: `generationPrices` is
        // absent from every bundle built before this feature, and absent means
        // "this bundle says nothing about prices" - not "there are no prices".
        ApiCatalogGenerationPriceApplier.Result result = applier.apply(null, 42L);

        assertThat(result.offered()).isZero();
        assertThat(result.delivered()).isFalse();
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("an EMPTY price array is the same statement as an absent one - it never unprices "
            + "an install that is live and selling")
    void emptyPriceArrayNeverUnprices() {
        // There is deliberately no sweep here to match the one that deprecates
        // APIs the bundle stopped listing: a price the bundle omits is a price
        // the install keeps.
        ApiCatalogGenerationPriceApplier.Result result = applier.apply(List.of(), 42L);

        assertThat(result.delivered()).isFalse();
        verify(credentialClient, never()).applyCatalogBundlePrices(any(), anyList(), any());
    }

    @Test
    @DisplayName("prices are handed to auth with their portable keys and their amounts intact")
    void pricesReachAuthWithAmountsIntact() {
        String toolId = "33333333-3333-3333-3333-333333333333";

        ApiCatalogGenerationPriceApplier.Result result = applier.apply(
                List.of(priceRow("seedance", toolId, "seedance-2.0", "second", "0", "60.5")), 42L);

        assertThat(result.offered()).isEqualTo(1);
        assertThat(result.delivered()).isTrue();
        BundleGenerationPriceDto sent = delivered().get(0);
        assertThat(sent.integrationName()).isEqualTo("seedance");
        assertThat(sent.apiToolId()).isEqualTo(toolId);
        assertThat(sent.modelId()).isEqualTo("seedance-2.0");
        assertThat(sent.priceUnit()).isEqualTo("second");
        // Parsed as an exact decimal, never through a double: the amount signed
        // has to be the amount charged.
        assertThat(sent.unitCredits()).isEqualByComparingTo("60.5");
        assertThat(sent.minCredits()).isNull();
    }

    @Test
    @DisplayName("a row with no integration or no endpoint is dropped, and the rest still land")
    void unusableRowsAreDroppedIndividually() {
        // Neither key can be defaulted: the integration is what the price hangs
        // off and the endpoint is what it prices, so a row missing either would
        // have nowhere to go.
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(priceRow(null, "33333333-3333-3333-3333-333333333333", "m", "call", "5", "0"));
        rows.add(priceRow("seedance", null, "m", "call", "5", "0"));
        rows.add(priceRow("seedance", "44444444-4444-4444-4444-444444444444", "m", "call", "5", "0"));

        ApiCatalogGenerationPriceApplier.Result result = applier.apply(rows, 42L);

        assertThat(result.offered()).isEqualTo(1);
        assertThat(delivered()).hasSize(1);
    }

    @Test
    @DisplayName("an unparseable amount becomes null rather than zero - a corrupt rate must not "
            + "publish a free generation")
    void corruptAmountIsNotReadAsFree() {
        List<BundleGenerationPriceDto> sent;
        applier.apply(List.of(priceRow("seedance", "33333333-3333-3333-3333-333333333333",
                "seedance-2.0", "second", "0", "not-a-number")), 42L);
        sent = delivered();

        assertThat(sent.get(0).unitCredits()).isNull();
    }

    @Test
    @DisplayName("auth-service being unreachable does not fail the apply - the catalog is worth "
            + "having, and the next tick re-offers the prices")
    void transportFailureIsSurvivable() {
        when(credentialClient.applyCatalogBundlePrices(any(), anyList(), any())).thenReturn(Optional.empty());

        ApiCatalogGenerationPriceApplier.Result result = applier.apply(
                List.of(priceRow("seedance", "33333333-3333-3333-3333-333333333333",
                        "seedance-2.0", "second", "0", "60")), 42L);

        assertThat(result.offered()).isEqualTo(1);
        assertThat(result.delivered()).isFalse();
    }

    @Test
    @DisplayName("a throwing client is caught too - by this point the endpoints have already landed")
    void throwingClientIsCaught() {
        when(credentialClient.applyCatalogBundlePrices(any(), anyList(), any()))
                .thenThrow(new IllegalStateException("boom"));

        ApiCatalogGenerationPriceApplier.Result result = applier.apply(
                List.of(priceRow("seedance", "33333333-3333-3333-3333-333333333333",
                        "seedance-2.0", "second", "0", "60")), 42L);

        assertThat(result.delivered()).isFalse();
    }

    @Test
    @DisplayName("the bundle stays anonymous, so the pricing history keeps naming the bundle")
    void theBundleSendsNoOrigin() {
        applier.apply(List.of(priceRow("seedance", "33333333-3333-3333-3333-333333333333",
                "seedance-2.0", "second", "0", "60")), 42L);

        verify(credentialClient).applyCatalogBundlePrices(eq(42L), anyList(), isNull());
    }

    @Test
    @DisplayName("another producer of bundle-owned prices signs its own name in the history")
    void anotherProducerNamesItself() {
        // The boot-time generation seed of a self-hosted install writes the same
        // bundle-owned rows without any bundle existing. Borrowing the bundle's
        // label would send an operator looking for a version that never was.
        applier.apply(List.of(priceRow("seedance", "33333333-3333-3333-3333-333333333333",
                "seedance-2.0", "second", "0", "60")), 2012L, "generation-seed");

        verify(credentialClient).applyCatalogBundlePrices(eq(2012L), anyList(), eq("generation-seed"));
    }
}
