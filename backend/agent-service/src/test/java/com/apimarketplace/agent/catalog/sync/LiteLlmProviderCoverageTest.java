package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.cloud.CloudRelaySupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant guard for the catalog-sync provider coverage.
 *
 * <p>A provider absent from {@link LiteLlmFeedParser#PROVIDER_MAP} is rejected
 * by the parser without any error: the sync reports OK, the admin sees
 * "0 added", and that provider's model list stays frozen on whatever
 * {@code application.yml} hardcodes forever. Moonshot (Kimi), Qwen and Z.AI
 * (GLM) sat in exactly that hole; this test is what stops the next one.
 */
@DisplayName("LiteLlmFeedParser - every executable provider is mapped")
class LiteLlmProviderCoverageTest {

    /**
     * OpenRouter is executable but is an aggregator, not a LiteLLM native
     * provider: {@link OpenRouterFeedParser} refreshes it from its own
     * {@code /api/v1/models} feed and stamps {@code provider=openrouter}.
     */
    private static final Set<String> NOT_LITELLM_NATIVE = Set.of("openrouter");

    @Test
    @DisplayName("Every relay-executable provider except openrouter is a PROVIDER_MAP value")
    void everyExecutableProviderIsMapped() {
        // CloudRelaySupport is the anchor because it is the closest thing to a
        // canonical list of directly-executable cloud providers. It is not
        // identical to its neighbours: ai.agent.providers.* is a SUPERSET (it
        // also declares the 4 CLI bridges, which are derived rather than
        // fetched), and OpenAICompatibleProviderFactory.knownProviders is a
        // SUBSET (only the 7 OpenAI-compatible ones; anthropic/openai/google/
        // mistral/deepseek have native providers). So this guard would not
        // catch a provider added to the YAML but never to the relay list -
        // add new providers to both, or widen this anchor.
        Set<String> unmapped = new TreeSet<>();
        for (String provider : CloudRelaySupport.supportedProviders()) {
            if (NOT_LITELLM_NATIVE.contains(provider)) continue;
            if (!LiteLlmFeedParser.PROVIDER_MAP.containsValue(provider)) {
                unmapped.add(provider);
            }
        }

        assertThat(unmapped)
                .as("providers the relay can execute but the LiteLLM parser drops - "
                        + "their catalog would never refresh; add a PROVIDER_MAP entry "
                        + "keyed by the LiteLLM provider name")
                .isEmpty();
    }

    @Test
    @DisplayName("Every Chinese provider we can execute is mapped under its LiteLLM key")
    void chineseProvidersAreMappedUnderTheirLiteLlmKeys() {
        // LiteLLM names Qwen after Alibaba's API brand, not the model family.
        // minimax was the last one still unmapped: its 6 chat models passed
        // every other filter and were dropped purely for want of this entry.
        assertThat(LiteLlmFeedParser.PROVIDER_MAP)
                .containsEntry("moonshot", "moonshot")
                .containsEntry("dashscope", "qwen")
                .containsEntry("zai", "zai")
                .containsEntry("minimax", "minimax")
                .containsEntry("deepseek", "deepseek");
    }

    @Test
    @DisplayName("PROVIDER_MAP is exactly the 13 expected LiteLLM keys - nothing silently added or lost")
    void providerMapIsExactlyTheExpectedKeys() {
        // Pins the map in both directions: everyExecutableProviderIsMapped
        // catches a MISSING provider, this catches an accidentally ADDED key
        // (e.g. mapping "vertex_ai-anthropic_models" onto anthropic, which
        // would inject Vertex-hosted twins under the native provider and
        // duplicate every row). xai is asserted here rather than in its own
        // test: grok refreshes off this single entry, and the whole 40-model
        // grok line rides on it.
        assertThat(LiteLlmFeedParser.PROVIDER_MAP)
                .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry("anthropic", "anthropic"),
                        Map.entry("openai", "openai"),
                        Map.entry("gemini", "google"),
                        Map.entry("mistral", "mistral"),
                        Map.entry("deepseek", "deepseek"),
                        Map.entry("xai", "xai"),
                        Map.entry("perplexity", "perplexity"),
                        Map.entry("cohere", "cohere"),
                        Map.entry("cohere_chat", "cohere"),
                        Map.entry("moonshot", "moonshot"),
                        Map.entry("dashscope", "qwen"),
                        Map.entry("zai", "zai"),
                        Map.entry("minimax", "minimax")));
    }

    @Test
    @DisplayName("No sync-excluded provider is also mapped by the parser")
    void excludedProvidersAreNotMapped() {
        // EXCLUDED_PROVIDERS holds the CLI bridges only: their rows are derived
        // from cloud entries by BridgeModelDeriver, so a parser mapping would
        // let a feed write them directly and fight the deriver.
        for (String excluded : ModelCatalogSyncService.EXCLUDED_PROVIDERS) {
            assertThat(LiteLlmFeedParser.PROVIDER_MAP)
                    .as("provider '%s' is both sync-excluded and parser-mapped", excluded)
                    .doesNotContainValue(excluded);
        }
    }

    @Test
    @DisplayName("zai is no longer sync-excluded - the GLM line ships in the LiteLLM feed")
    void zaiIsNotExcludedAnymore() {
        assertThat(ModelCatalogSyncService.EXCLUDED_PROVIDERS)
                .doesNotContain("zai")
                .containsExactlyInAnyOrder("claude-code", "codex", "gemini-cli", "mistral-vibe");
    }
}
