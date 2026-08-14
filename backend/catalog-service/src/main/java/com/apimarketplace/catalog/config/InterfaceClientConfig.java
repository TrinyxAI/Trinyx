package com.apimarketplace.catalog.config;

import com.apimarketplace.interfaces.client.InterfaceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Interface HTTP client wiring for catalog-service.
 *
 * <p>Used by {@code GenerationModule} to persist a finished generation as an
 * Interface entity, which is what puts the asset on the chat side panel. The
 * same client, the same endpoint and the same DTO the legacy image tool posts
 * from orchestrator-service: a second HTTP client for one more caller would be
 * a second place for the route, the timeouts and the failure posture to drift.
 *
 * <p>{@code @ConditionalOnMissingBean} mirrors this service's
 * {@link StorageClientConfig}, for the CE monolith, where catalog-service and
 * orchestrator-service share one application context and orchestrator declares
 * a bean of this type as well. Both build the same client from the same
 * {@code services.interface-url}, so either one serves.
 */
@Configuration
public class InterfaceClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public InterfaceClient interfaceClient(
            @Value("${services.interface-url:http://localhost:8089}") String interfaceServiceUrl) {
        return new InterfaceClient(interfaceServiceUrl);
    }
}
