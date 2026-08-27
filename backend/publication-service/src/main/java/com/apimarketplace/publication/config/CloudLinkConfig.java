package com.apimarketplace.publication.config;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.apimarketplace.publication.service.CeCloudLinkHeartbeatScheduler;
import com.apimarketplace.publication.service.CloudLinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CE/paid-monolith Cloud-link lifecycle. Remote CE always exposes the capability,
 * matching LiveContext; cloud-link.enabled=true additionally enables it for Trinyx
 * paid-monolith while that deployment keeps its Marketplace local.
 */
@Configuration
@Conditional(CloudLinkAvailableCondition.class)
public class CloudLinkConfig {

    @Value("${marketplace.cloud-api-url:https://cloud.trinyx.fr/api}")
    private String cloudApiUrl;

    @Value("${cloud-link.keycloak-url:https://auth.trinyx.fr/realms/trinyx}")
    private String keycloakUrl;

    @Value("${cloud-link.client-id:trinyx-frontend}")
    private String clientId;

    @Value("${cloud-link.redirect-uri:http://localhost:8080/api/cloud-link/callback}")
    private String redirectUri;

    @Value("${cloud-link.encryption-key:}")
    private String encryptionKey;

    @Value("${ce.version:dev}")
    private String ceVersion;

    @Value("${billing.authority.mode:native}")
    private String billingAuthorityMode;

    @Bean
    public CloudLinkService cloudLinkService(
            CeCloudLinkRepository cloudLinkRepository,
            ObjectMapper objectMapper,
            AuthClient authClient) {
        return new CloudLinkService(
                cloudLinkRepository, keycloakUrl, clientId, redirectUri, encryptionKey,
                cloudApiUrl, ceVersion, objectMapper, authClient,
                "paid-monolith-authority".equalsIgnoreCase(billingAuthorityMode));
    }

    @Bean
    public CeCloudLinkHeartbeatScheduler ceCloudLinkHeartbeatScheduler(
            CeCloudLinkRepository cloudLinkRepository,
            CloudLinkService cloudLinkService) {
        return new CeCloudLinkHeartbeatScheduler(cloudLinkRepository, cloudLinkService);
    }
}
