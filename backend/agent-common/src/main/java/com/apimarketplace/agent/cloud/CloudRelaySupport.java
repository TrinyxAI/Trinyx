package com.apimarketplace.agent.cloud;

import java.util.Set;

/**
 * Provider names that the Cloud relay can execute using Cloud-side credentials.
 */
public final class CloudRelaySupport {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
            "anthropic",
            "openai",
            "google",
            "mistral",
            "deepseek",
            "xai",
            "perplexity",
            "cohere",
            "zai",
            "openrouter",
            "qwen",
            "moonshot"
    );

    private CloudRelaySupport() {
    }

    /**
     * The full set, immutable. Exposed so the catalog-sync feed parsers can
     * assert they map every provider the relay can actually execute: a
     * provider the relay supports but no feed parser recognises has a model
     * list frozen at whatever configuration hardcodes.
     */
    public static Set<String> supportedProviders() {
        return SUPPORTED_PROVIDERS;
    }

    public static boolean isSupportedProvider(String providerName) {
        return providerName != null
                && SUPPORTED_PROVIDERS.contains(providerName.trim().toLowerCase());
    }
}
