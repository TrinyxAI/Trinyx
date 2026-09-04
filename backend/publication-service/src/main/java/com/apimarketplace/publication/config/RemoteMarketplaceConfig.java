package com.apimarketplace.publication.config;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.publication.service.EditableWorkflowTwinService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.SnapshotCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the remote marketplace feature. Cloud-link lifecycle has its own
 * {@link CloudLinkConfig} so a paid-monolith can keep marketplace local while linking to
 * the Trinyx Cloud control plane.
 */
@Configuration
@ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")
public class RemoteMarketplaceConfig {

    @Value("${marketplace.cloud-api-url:https://cloud.trinyx.fr/api}")
    private String cloudApiUrl;

    @Bean
    public RemoteMarketplaceService remoteMarketplaceService(
            SnapshotCloneService snapshotCloneService,
            PublicationReceiptRepository receiptRepository,
            ObjectProvider<CloudLinkService> cloudLinkService,
            ObjectMapper objectMapper,
            AuthClient authClient,
            AgentPublicationService agentPublicationService,
            ResourcePublicationService resourcePublicationService,
            OrchestratorInternalClient orchestratorClient,
            ObjectProvider<EditableWorkflowTwinService> editableWorkflowTwinService) {
        return new RemoteMarketplaceService(
                cloudApiUrl, snapshotCloneService, receiptRepository, cloudLinkService.getIfAvailable(),
                objectMapper, authClient,
                agentPublicationService, resourcePublicationService, orchestratorClient,
                // Backs the ON-DEMAND editable copy (and owns its WORKFLOW-quota check).
                // Acquire itself no longer needs it: it stopped minting a copy per install.
                editableWorkflowTwinService.getIfAvailable());
    }
}
