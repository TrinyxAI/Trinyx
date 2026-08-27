package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.CloudLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link CloudLinkController}.
 *
 * <p>With no explicit override, remote Marketplace mode exposes the OAuth linking
 * capability and local mode does not. cloud-link.enabled=true is an additional
 * paid-monolith local + CloudLink opt-in; false never removes upstream remote capability.
 */
@DisplayName("CloudLinkController - availability gating")
class CloudLinkControllerBeanGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(CloudLinkService.class, () -> mock(CloudLinkService.class))
            .withUserConfiguration(CloudLinkControllerImport.class);

    @Test
    @DisplayName("remote marketplace exposes CloudLink when the explicit switch is absent")
    void remoteMarketplaceAutoEnablesController() {
        contextRunner
                .withPropertyValues("marketplace.mode=remote")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CloudLinkController.class);
                });
    }

    @Test
    @DisplayName("local marketplace leaves CloudLink absent when the explicit switch is absent")
    void localMarketplaceDoesNotAutoEnableController() {
        contextRunner
                .withPropertyValues("marketplace.mode=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CloudLinkController.class);
                });
    }

    @Test
    @DisplayName("remote marketplace keeps CloudLink available when the independent switch is false")
    void remoteMarketplaceWinsOverFalseLocalModeSwitch() {
        contextRunner
                .withPropertyValues("marketplace.mode=remote", "cloud-link.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CloudLinkController.class);
                });
    }

    @Test
    @DisplayName("explicit true enables CloudLink with a local marketplace")
    void explicitTrueEnablesControllerForPaidMonolith() {
        contextRunner
                .withPropertyValues("marketplace.mode=local", "cloud-link.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CloudLinkController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CloudLinkController.class)
    static class CloudLinkControllerImport {
    }
}
