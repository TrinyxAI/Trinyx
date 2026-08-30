package com.apimarketplace.orchestrator.services.code;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class PistonRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(CodeExecutionConfiguration.class);

    @Test
    void cloudCanDisableCodeExecutionWithoutCreatingAnyExecutor() {
        contextRunner
            .withPropertyValues(
                "piston.enabled=false",
                "piston.embedded=false",
                "piston.url=")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(CodeExecutor.class);
                assertThat(context).doesNotHaveBean(PistonClient.class);
                assertThat(context).doesNotHaveBean(EmbeddedCodeExecutor.class);
            });
    }

    @Test
    void remotePistonFailsStartupWhenEnabledWithoutUrl() {
        contextRunner
            .withPropertyValues(
                "piston.enabled=true",
                "piston.embedded=false",
                "piston.url=")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "piston.url must be configured when piston.enabled=true and piston.embedded=false");
            });
    }

    @Test
    void remotePistonAcceptsAndRetainsExactConfiguredUrl() {
        contextRunner
            .withPropertyValues(
                "piston.enabled=true",
                "piston.embedded=false",
                "piston.url=https://piston-staging.example.invalid")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(CodeExecutor.class);
                assertThat(context).hasSingleBean(PistonClient.class);
                assertThat(context).doesNotHaveBean(EmbeddedCodeExecutor.class);
                assertThat(context.getBean(PistonClient.class).configuredPistonUrl())
                    .isEqualTo("https://piston-staging.example.invalid");
            });
    }

    @Test
    void ceKeepsItsExistingEmbeddedExecutorWithoutRemoteFallback() {
        contextRunner
            .withPropertyValues(
                "piston.enabled=true",
                "piston.embedded=true",
                "piston.url=")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(CodeExecutor.class);
                assertThat(context).hasSingleBean(EmbeddedCodeExecutor.class);
                assertThat(context).doesNotHaveBean(PistonClient.class);
            });
    }

    @Test
    void remotePistonRejectsNonHttpEndpoint() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> PistonClient.requireRemoteUrl("file:///tmp/piston")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("absolute HTTP(S) URL");
    }

    @Configuration(proxyBeanMethods = false)
    @Import({PistonClient.class, EmbeddedCodeExecutor.class})
    static class CodeExecutionConfiguration {
    }
}
