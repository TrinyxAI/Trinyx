package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.OpenAICompatibleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the third catalog source: the per-provider {@code /models}
 * discovery pass.
 *
 * <p>The scenario these pin is the one that motivated the whole feature. On
 * 2026-08-19 Z.AI's own API served glm-5.2 / glm-5.3 / glm-5v-turbo while
 * LiteLLM's {@code zai} block still ended at glm-5.1, so no sync could ever
 * surface them. Discovery closes that gap: the vendor endpoint says which
 * models exist, OpenRouter's entry for the same id supplies the price, and the
 * row is then shaped exactly like a feed row.
 *
 * <p>The two invariants worth pinning are the provenance ({@code priceFrom} +
 * the exact {@code openRouterId}, so a rate stays traceable) and the no-donor
 * case: a model OpenRouter does not carry must land UNPRICED rather than with a
 * guessed rate, because unpriced is not free - the billing path would otherwise
 * charge it at the platform default.
 */
@DisplayName("NativeModelDiscoveryService - vendor endpoint as the existence authority")
class NativeModelDiscoveryServiceTest {

    private LLMProviderFactory providerFactory;
    private NativeModelDiscoveryService service;

    @BeforeEach
    void setUp() {
        providerFactory = mock(LLMProviderFactory.class);
        // Default: no provider registered. Each test wires the ones it needs.
        when(providerFactory.findProvider(anyString())).thenReturn(Optional.empty());
        service = new NativeModelDiscoveryService(providerFactory);
    }

    private OpenAICompatibleProvider providerServing(String name, String... modelIds) {
        OpenAICompatibleProvider provider = mock(OpenAICompatibleProvider.class);
        when(provider.listRemoteModelIds()).thenReturn(Optional.of(List.of(modelIds)));
        when(providerFactory.findProvider(name)).thenReturn(Optional.of(provider));
        return provider;
    }

    @Test
    @DisplayName("Emits the vendor ids no feed carries - the glm-5.2 / 5.3 gap")
    void emitsIdsMissingFromTheFeeds() {
        providerServing("zai", "glm-5.1", "glm-5.2", "glm-5.3", "glm-5v-turbo");

        // What LiteLLM's zai block actually carried that day.
        List<Map<String, Object>> feed = List.of(feedRow("zai", "glm-5.1"));

        var result = service.discover(feed, Set.of(), List.of());

        assertThat(ids(result.models())).containsExactlyInAnyOrder(
                "glm-5.2", "glm-5.3", "glm-5v-turbo");
        assertThat(result.discoveredByProvider()).containsEntry("zai", 3);
        assertThat(result.models().get(0).get("provider")).isEqualTo("zai");
        assertThat(result.models().get(0).get("source")).isEqualTo("discovery");
        assertThat(result.models().get(0).get("mode")).isEqualTo("chat");
    }

    @Test
    @DisplayName("A discovered row is priced from OpenRouter's entry for the same vendor id")
    void takesThePriceFromOpenRouter() {
        providerServing("zai", "glm-5.3");

        List<Map<String, Object>> openRouter = List.of(openRouterRow(
                "z-ai/glm-5.3", "1.4000", "4.4000"));

        var result = service.discover(List.of(), Set.of(), openRouter);

        Map<String, Object> row = result.models().get(0);
        assertThat(row.get("priceInput")).isEqualTo("1.4000");
        assertThat(row.get("priceOutput")).isEqualTo("4.4000");
    }

    @Test
    @DisplayName("The price records exactly which OpenRouter entry it came from")
    void stampsThePriceProvenance() {
        // "Is that really OpenRouter's price?" has to stay answerable per row,
        // months later, without re-deriving anything - hence the donor's exact
        // aggregator id next to the rate it supplied.
        providerServing("zai", "glm-5.3");

        var result = service.discover(List.of(), Set.of(),
                List.of(openRouterRow("z-ai/glm-5.3", "1.4000", "4.4000")));

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.models().get(0).get("feedMetadata");
        assertThat(meta).containsEntry("priceFrom", "openrouter");
        assertThat(meta).containsEntry("openRouterId", "z-ai/glm-5.3");
    }

    @Test
    @DisplayName("Cache rates and tier travel with the price, so the row lands in the right bucket")
    void carriesTheDerivedPricingFields() {
        providerServing("zai", "glm-5.3");

        Map<String, Object> donor = openRouterRow("z-ai/glm-5.3", "1.4000", "4.4000");
        donor.put("priceFloorInput", "1.4000");
        donor.put("priceFloorOutput", "4.4000");
        donor.put("priceCacheRead", "0.1100");
        donor.put("supportsPromptCaching", true);
        donor.put("tier", "mid");

        var result = service.discover(List.of(), Set.of(), List.of(donor));

        Map<String, Object> row = result.models().get(0);
        assertThat(row.get("priceCacheRead")).isEqualTo("0.1100");
        assertThat(row.get("supportsPromptCaching")).isEqualTo(true);
        // tier is a deterministic function of the output price; leaving it
        // behind would file a 4.40 model under "unknown".
        assertThat(row.get("tier")).isEqualTo("mid");
    }

    @Test
    @DisplayName("No OpenRouter entry: the row lands unpriced rather than with a guessed rate")
    void staysUnpricedWhenOpenRouterHasNoEntry() {
        // The unpriced-enable guard then keeps it out of the picker until an
        // admin prices it. Unpriced is not free - ModelPricingService would
        // otherwise bill it at the platform default rate.
        providerServing("minimax", "MiniMax-M4");

        var result = service.discover(List.of(), Set.of(), List.of());

        Map<String, Object> row = result.models().get(0);
        assertThat(row.get("modelId")).isEqualTo("MiniMax-M4");
        assertThat(row).doesNotContainKeys("priceInput", "priceOutput", "tier", "contextWindow");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) row.get("feedMetadata");
        assertThat(meta).containsEntry("priceFrom", "none");
        assertThat(meta).containsEntry("capabilitiesFrom", "none");
    }

    @Test
    @DisplayName("Capabilities ARE borrowed from the aggregator row for the same vendor id")
    void copiesCapabilitiesFromTheOpenRouterTwin() {
        providerServing("moonshot", "kimi-k3");

        Map<String, Object> donor = openRouterRow("moonshotai/kimi-k3", "0.6000", "2.5000");
        donor.put("contextWindow", 262144);
        donor.put("supportsTools", true);
        donor.put("supportsVision", true);
        donor.put("supportsReasoning", true);

        var result = service.discover(List.of(), Set.of(), List.of(donor));

        Map<String, Object> row = result.models().get(0);
        assertThat(row.get("modelId")).isEqualTo("kimi-k3");
        assertThat(row.get("contextWindow")).isEqualTo(262144);
        assertThat(row.get("supportsTools")).isEqualTo(true);
        assertThat(row.get("supportsVision")).isEqualTo(true);
        // The same donor supplies the price, so a discovered row is complete
        // enough to be indistinguishable from a feed row downstream.
        assertThat(row.get("priceInput")).isEqualTo("0.6000");
        assertThat(row.get("priceOutput")).isEqualTo("2.5000");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) row.get("feedMetadata");
        assertThat(meta).containsEntry("capabilitiesFrom", "openrouter");
    }

    @Test
    @DisplayName("Gap-fill only: a model a feed already covers is left to the feed")
    void suppressesIdsTheFeedAlreadyCovers() {
        providerServing("zai", "glm-4.7", "glm-5.1");

        var result = service.discover(
                List.of(feedRow("zai", "glm-4.7"), feedRow("zai", "glm-5.1")),
                Set.of(), List.of());

        assertThat(result.models()).isEmpty();
        assertThat(result.discoveredByProvider()).isEmpty();
    }

    @Test
    @DisplayName("Gap-fill only: a model already in the catalog is left alone")
    void suppressesIdsAlreadyInTheCatalog() {
        providerServing("zai", "glm-5.2");

        // An admin already added and priced it by hand - discovery must not
        // re-emit it, or the merge would overwrite that curated row with a
        // price-free one.
        var result = service.discover(List.of(), Set.of(NativeModelDiscoveryService.key("zai", "glm-5.2")), List.of());

        assertThat(result.models()).isEmpty();
    }

    @Test
    @DisplayName("A provider with no key is skipped and reported, not failed")
    void skipsUnconfiguredProviders() {
        OpenAICompatibleProvider unkeyed = mock(OpenAICompatibleProvider.class);
        when(unkeyed.listRemoteModelIds()).thenReturn(Optional.empty());
        when(providerFactory.findProvider("qwen")).thenReturn(Optional.of(unkeyed));

        var result = service.discover(List.of(), Set.of(), List.of());

        assertThat(result.models()).isEmpty();
        assertThat(result.skippedProviders()).containsExactly("qwen");
    }

    @Test
    @DisplayName("One vendor being down does not stop the others")
    void oneBrokenProviderDoesNotBlockTheRest() {
        OpenAICompatibleProvider broken = mock(OpenAICompatibleProvider.class);
        when(broken.listRemoteModelIds()).thenReturn(Optional.empty());
        when(providerFactory.findProvider("zai")).thenReturn(Optional.of(broken));
        providerServing("moonshot", "kimi-k3");

        var result = service.discover(List.of(), Set.of(), List.of());

        assertThat(ids(result.models())).containsExactly("kimi-k3");
        assertThat(result.skippedProviders()).containsExactly("zai");
    }

    @Test
    @DisplayName("Native-SDK providers and bridges are never probed - they have no such endpoint")
    void ignoresNonOpenAiCompatibleProviders() {
        // findProvider returns empty for everything by default, which is what a
        // bridge / native-SDK provider looks like to this service.
        var result = service.discover(List.of(), Set.of(), List.of());

        assertThat(result.models()).isEmpty();
        assertThat(result.skippedProviders()).isEmpty();
        verify(providerFactory, never()).findProvider("claude-code");
    }

    @Test
    @DisplayName("A vendor listing the same id twice yields one row")
    void deduplicatesWithinAProviderListing() {
        providerServing("zai", "glm-5.3", "glm-5.3");

        var result = service.discover(List.of(), Set.of(), List.of());

        assertThat(result.models()).hasSize(1);
        assertThat(result.discoveredByProvider()).containsEntry("zai", 1);
    }

    @Test
    @DisplayName("Blank ids in a sloppy vendor response are dropped")
    void ignoresBlankIds() {
        OpenAICompatibleProvider provider = mock(OpenAICompatibleProvider.class);
        when(provider.listRemoteModelIds())
                .thenReturn(Optional.of(Arrays.asList("glm-5.3", "", "   ")));
        when(providerFactory.findProvider("zai")).thenReturn(Optional.of(provider));

        var result = service.discover(List.of(), Set.of(), List.of());

        assertThat(ids(result.models())).containsExactly("glm-5.3");
    }

    @Test
    @DisplayName("The aggregator namespace map does not confuse two vendors")
    void doesNotBorrowCapabilitiesAcrossVendors() {
        providerServing("minimax", "glm-5.3");

        // A z-ai row must never donate to a minimax model of the same id.
        Map<String, Object> donor = openRouterRow("z-ai/glm-5.3", "1.4", "4.4");
        donor.put("contextWindow", 999999);

        var result = service.discover(List.of(), Set.of(), List.of(donor));

        assertThat(result.models().get(0)).doesNotContainKey("contextWindow");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<String> ids(List<Map<String, Object>> models) {
        return models.stream().map(m -> (String) m.get("modelId")).toList();
    }

    private static Map<String, Object> feedRow(String provider, String modelId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("priceInput", "1.0000");
        m.put("priceOutput", "2.0000");
        return m;
    }

    private static Map<String, Object> openRouterRow(String fullId, String in, String out) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", "openrouter");
        m.put("modelId", fullId);
        m.put("priceInput", in);
        m.put("priceOutput", out);
        return m;
    }
}
