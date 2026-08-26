package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.BundleGenerationPriceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether this installation can pay for a generation out of its own pocket.
 *
 * <p>The answer is acted on by BUILDING something: told the platform pays, an
 * author wires a node and leaves the payer unstated, and a wrong answer is
 * discovered when that node fails on its first run. So the two ways of being
 * wrong are pinned here separately: claiming the platform pays when billing
 * would refuse, and claiming it does not when the check simply did not answer.
 */
class PlatformSalesResolverTest {

    private static final String INTEGRATION = "seedance";
    private static final String TOOL = "11111111-1111-1111-1111-111111111111";

    private CredentialClient credentials;
    private PlatformSalesResolver resolver;

    @BeforeEach
    void setUp() {
        credentials = mock(CredentialClient.class);
        resolver = new PlatformSalesResolver(credentials);
    }

    private static PlatformSalesResolver.ModelRef ref(String modelId) {
        return new PlatformSalesResolver.ModelRef(INTEGRATION, TOOL, modelId);
    }

    private static BundleGenerationPriceDto price(String modelId) {
        return new BundleGenerationPriceDto(INTEGRATION, TOOL, modelId, "second",
                BigDecimal.ZERO, BigDecimal.ONE, null, null);
    }

    private void published(BundleGenerationPriceDto... prices) {
        when(credentials.fetchPublishedGenerationPrices(any(), any())).thenReturn(List.of(prices));
    }

    @Test
    @DisplayName("a key AND a published price means either payer works")
    void bothHalvesPresent() {
        published(price("seedance-2.0"));
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);

        assertThat(resolver.resolve(List.of(ref("seedance-2.0"))))
                .containsEntry(ref("seedance-2.0"), PlatformSalesResolver.Verdict.PLATFORM_OR_OWN_KEY);
    }

    @Test
    @DisplayName("regression: a key WITHOUT a published price is not a platform sale")
    void aKeyWithoutAPriceIsNotEnough() {
        // Billing refuses a resold generation that carries no published price
        // (PLATFORM_NOT_AVAILABLE). An earlier version asked only whether the
        // key existed and so promised the platform would pay for models it then
        // refused, which is acted on by building a node that fails on first run.
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);
        // Another MODEL of the same endpoint is priced; this one is not.
        published(price("another-model"));

        assertThat(resolver.resolve(List.of(ref("seedance-2.0"))))
                .containsEntry(ref("seedance-2.0"), PlatformSalesResolver.Verdict.OWN_KEY_ONLY);
    }

    @Test
    @DisplayName("a published price without a usable key is not one either")
    void aPriceWithoutAKeyIsNotEnough() {
        published(price("seedance-2.0"));
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(false);

        assertThat(resolver.resolve(List.of(ref("seedance-2.0"))))
                .containsEntry(ref("seedance-2.0"), PlatformSalesResolver.Verdict.OWN_KEY_ONLY);
    }

    @Test
    @DisplayName("an API that names no platform credential is never resold, and costs no call")
    void noIntegrationMeansOwnKey() {
        PlatformSalesResolver.ModelRef none = new PlatformSalesResolver.ModelRef(null, TOOL, "m");

        assertThat(resolver.resolve(List.of(none)))
                .containsEntry(none, PlatformSalesResolver.Verdict.OWN_KEY_ONLY);
        verify(credentials, never()).fetchPublishedGenerationPrices(any(), any());
        verify(credentials, never()).platformCredentialAvailable(anyString());
    }

    @Test
    @DisplayName("regression: a lookup that did not answer is UNKNOWN, never a confident claim")
    void aFailedLookupIsUnknown() {
        // Both underlying calls fail SOFT: an unreachable auth-service answers
        // "no prices". Read as "nothing is sold", that tells every reader to go
        // find their own key during an outage; read as "sold", it invites a node
        // that cannot run. Neither may be said.
        // A key EXISTS, so the price question genuinely arises - and the client
        // swallows a transport failure into the same empty list it returns when
        // nothing is published.
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);
        when(credentials.fetchPublishedGenerationPrices(any(), any())).thenReturn(List.of());

        assertThat(resolver.resolve(List.of(ref("seedance-2.0"))))
                .containsEntry(ref("seedance-2.0"), PlatformSalesResolver.Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("regression: no platform key at all is a CONFIDENT own_key_only, not unknown")
    void aSelfHostedInstallGetsAnAnswer() {
        // The deployment this field exists to describe. Asking the price first
        // made every model 'unknown' forever here, which is the one answer the
        // help gives no instruction for.
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(false);
        when(credentials.fetchPublishedGenerationPrices(any(), any())).thenReturn(List.of());

        assertThat(resolver.resolve(List.of(ref("seedance-2.0"))))
                .containsEntry(ref("seedance-2.0"), PlatformSalesResolver.Verdict.OWN_KEY_ONLY);
    }

    @Test
    @DisplayName("an ENDPOINT-WIDE price covers every model of that endpoint, as billing resolves it")
    void anEndpointWidePriceCounts() {
        // Billing takes the per-model row first, else the endpoint-wide one.
        // Matching only the exact model reported 'not sold' for a model that is.
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);
        published(new BundleGenerationPriceDto(INTEGRATION, TOOL, null, "call",
                BigDecimal.ZERO, BigDecimal.ONE, null, null));

        assertThat(resolver.resolve(List.of(ref("any-model-of-that-endpoint"))))
                .containsEntry(ref("any-model-of-that-endpoint"),
                        PlatformSalesResolver.Verdict.PLATFORM_OR_OWN_KEY);
    }

    @Test
    @DisplayName("the key is asked ONCE per integration, not once per model")
    void theKeyCheckIsPerIntegration() {
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);
        published(price("a"), price("b"), price("c"));

        resolver.resolve(List.of(ref("a"), ref("b"), ref("c")));

        // A cold cache on a fifty-model catalogue otherwise means fifty
        // synchronous round trips on the agent's own help request.
        verify(credentials, times(1)).platformCredentialAvailable(INTEGRATION);
    }

    @Test
    @DisplayName("a throwing lookup is UNKNOWN too, and does not break the listing")
    void aThrowingLookupIsUnknown() {
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);
        when(credentials.fetchPublishedGenerationPrices(any(), any()))
                .thenThrow(new IllegalStateException("auth down"));

        assertThat(resolver.resolve(List.of(ref("seedance-2.0"))))
                .containsEntry(ref("seedance-2.0"), PlatformSalesResolver.Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("regression: UNKNOWN is never cached, so an outage does not outlive itself")
    void unknownIsNotCached() {
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);
        when(credentials.fetchPublishedGenerationPrices(any(), any())).thenReturn(List.of());
        resolver.resolve(List.of(ref("seedance-2.0")));

        published(price("seedance-2.0"));

        // Cached, a five minute TTL would keep answering "unknown" long after
        // the blip that caused it.
        assertThat(resolver.resolve(List.of(ref("seedance-2.0"))))
                .containsEntry(ref("seedance-2.0"), PlatformSalesResolver.Verdict.PLATFORM_OR_OWN_KEY);
    }

    @Test
    @DisplayName("a settled answer is cached, so a listing costs one lookup and not one per model")
    void settledAnswersAreCached() {
        published(price("seedance-2.0"));
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);

        resolver.resolve(List.of(ref("seedance-2.0")));
        resolver.resolve(List.of(ref("seedance-2.0")));

        verify(credentials, times(1)).fetchPublishedGenerationPrices(any(), any());
    }

    @Test
    @DisplayName("a whole list is resolved in ONE lookup, not one per model")
    void theListIsResolvedInBulk() {
        when(credentials.platformCredentialAvailable(INTEGRATION)).thenReturn(true);
        published(price("a"), price("b"));

        Map<PlatformSalesResolver.ModelRef, PlatformSalesResolver.Verdict> out =
                resolver.resolve(List.of(ref("a"), ref("b"), ref("c")));

        assertThat(out).hasSize(3);
        assertThat(out.get(ref("c"))).isEqualTo(PlatformSalesResolver.Verdict.OWN_KEY_ONLY);
        verify(credentials, times(1)).fetchPublishedGenerationPrices(any(), any());
    }

    @Test
    @DisplayName("an empty request asks nothing")
    void emptyInputAsksNothing() {
        assertThat(resolver.resolve(List.of())).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
        verify(credentials, never()).fetchPublishedGenerationPrices(any(), any());
    }
}
