package com.apimarketplace.orchestrator.config;

import com.apimarketplace.credential.client.CredentialClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the Credential HTTP client.
 * Connects orchestrator-service to auth-service for credential operations.
 */
@Configuration
public class CredentialClientConfig {

    @Bean
    public CredentialClient credentialClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authUrl,
            @Value("${internal.s2s.service-secret:}") String serviceSecret) {
        return new CredentialClient(authUrl, serviceSecret, "orchestrator-service");
    }
}
