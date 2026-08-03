package com.apimarketplace.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The catalog service's {@link WebClient.Builder}, raising the in-memory codec limit well
 * above Spring's 262,144-byte default.
 *
 * <p>This bean used to live on {@code MappingGeneratorConfig}, next to the AI mapping-generator
 * beans. When that dead subtree was deleted the builder went with it, which was a mistake: it was
 * not part of the mapping feature at all. {@code CatalogApplication} component-scans
 * {@code com.apimarketplace.sse}, so {@code WebClientSseConsumer} injects a
 * {@code WebClient.Builder} on the LIVE streaming tool-execution path. Without this bean Spring
 * Boot's auto-configured builder takes over silently and the context still starts, so no test
 * catches it: the failure only appears in production, as a DataBufferLimitException on the first
 * streamed tool response larger than 256 KB.
 *
 * <p>Kept as its own configuration class rather than folded into an unrelated one, so the next
 * person deleting a feature does not carry it away again.
 */
@Configuration
public class CatalogWebClientConfig {

    /** Matches the limit the deleted MappingGeneratorConfig used. */
    private static final int MAX_IN_MEMORY_BYTES = 50 * 1024 * 1024;

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES));
    }
}
