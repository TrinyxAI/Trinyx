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
 * <p>The controller is annotated
 * {@code @ConditionalOnProperty(name = "cloud-link.enabled", havingValue = "true")} with NO
 * {@code matchIfMissing}, so it defaults to false: the CE-side cloud-account link surface
 * (status/connect/disconnect, LLM-source, usage mirrors) is wired ONLY when
 * {@code cloud-link.enabled=true} (the cloud-linked CE/paid monolith). On a Cloud microservice
 * (property unset, or {@code cloud-link.enabled=false}) the controller MUST NOT exist, otherwise its
 * routes would expose cloud-link management on an install that has no cloud link configured. These
 * tests pin that contract so a regression in the condition (flipping {@code matchIfMissing} or
 * renaming the property) is caught.
 */
@DisplayName("CloudLinkController - cloud-link.enabled=true bean gating")
class CloudLinkControllerBeanGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(CloudLinkService.class, () -> mock(CloudLinkService.class))
            .withUserConfiguration(CloudLinkControllerImport.class);

    @Test
    @DisplayName("Property unset - controller bean is ABSENT (matchIfMissing defaults to false)")
    void beanAbsentWhenPropertyUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CloudLinkController.class);
        });
    }

    @Test
    @DisplayName("cloud-link.enabled=false - controller bean is ABSENT (no cloud-link surface on a non-remote install)")
    void beanAbsentWhenDisabled() {
        contextRunner
                .withPropertyValues("cloud-link.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CloudLinkController.class);
                });
    }

    @Test
    @DisplayName("cloud-link.enabled=true - controller bean IS wired exactly once (cloud-linked CE/paid monolith)")
    void beanPresentWhenEnabled() {
        contextRunner
                .withPropertyValues("cloud-link.enabled=true")
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
