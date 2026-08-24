package com.apimarketplace.publication.config;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.CeCloudLinkHeartbeatScheduler;
import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.SnapshotCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link RemoteMarketplaceConfig}.
 *
 * <p>The configuration is annotated
 * {@code @ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")} with NO
 * {@code matchIfMissing}, so it defaults to false: the CE remote-marketplace beans
 * {@link RemoteMarketplaceService} remains gated by {@code marketplace.mode=remote}, while
 * {@link CloudLinkService} and {@link CeCloudLinkHeartbeatScheduler} are independently gated by
 * {@code cloud-link.enabled=true}. This permits paid-monolith + local marketplace without loading
 * CE-side link beans in Cloud microservices.
 */
@DisplayName("RemoteMarketplaceConfig - marketplace.mode=remote bean gating")
class RemoteMarketplaceConfigBeanGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RemoteMarketplaceConfigDependencies.class)
            .withUserConfiguration(RemoteMarketplaceConfig.class)
            .withUserConfiguration(CloudLinkConfig.class)
            // A non-default, non-blank key so CloudLinkService can construct when the gate opens.
            .withPropertyValues("cloud-link.encryption-key=unit-test-encryption-key");

    @Test
    @DisplayName("Property unset - remote-marketplace beans are ABSENT (matchIfMissing defaults to false)")
    void beansAbsentWhenPropertyUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CloudLinkService.class);
            assertThat(context).doesNotHaveBean(RemoteMarketplaceService.class);
            assertThat(context).doesNotHaveBean(CeCloudLinkHeartbeatScheduler.class);
        });
    }

    @Test
    @DisplayName("local marketplace can enable cloud-link without remote marketplace")
    void localMarketplaceCanEnableCloudLinkIndependently() {
        contextRunner
                .withPropertyValues("marketplace.mode=local", "cloud-link.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CloudLinkService.class);
                    assertThat(context).doesNotHaveBean(RemoteMarketplaceService.class);
                    assertThat(context).hasSingleBean(CeCloudLinkHeartbeatScheduler.class);
                });
    }

    @Test
    @DisplayName("remote marketplace and cloud-link enabled wire both feature sets")
    void beansPresentWhenBothFeaturesEnabled() {
        contextRunner
                .withPropertyValues("marketplace.mode=remote", "cloud-link.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CloudLinkService.class);
                    assertThat(context).hasSingleBean(RemoteMarketplaceService.class);
                    assertThat(context).hasSingleBean(CeCloudLinkHeartbeatScheduler.class);
                });
    }

    /** Supplies the config's collaborators so its beans can wire when the gate opens. */
    @Configuration(proxyBeanMethods = false)
    static class RemoteMarketplaceConfigDependencies {
        @Bean
        CeCloudLinkRepository cloudLinkRepository() {
            return mock(CeCloudLinkRepository.class);
        }

        @Bean
        PublicationReceiptRepository receiptRepository() {
            return mock(PublicationReceiptRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SnapshotCloneService snapshotCloneService() {
            return mock(SnapshotCloneService.class);
        }

        @Bean
        AuthClient authClient() {
            return mock(AuthClient.class);
        }

        @Bean
        AgentPublicationService agentPublicationService() {
            return mock(AgentPublicationService.class);
        }

        @Bean
        ResourcePublicationService resourcePublicationService() {
            return mock(ResourcePublicationService.class);
        }

        @Bean
        OrchestratorInternalClient orchestratorClient() {
            return mock(OrchestratorInternalClient.class);
        }
    }
}
