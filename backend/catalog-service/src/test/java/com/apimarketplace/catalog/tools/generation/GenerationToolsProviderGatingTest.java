package com.apimarketplace.catalog.tools.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Install-level spend gate for the {@code generation} tool.
 *
 * <p>Every {@code create} debits the customer's credits against the platform's own
 * provider keys, so the provider must not be a bean unless the install explicitly
 * asked for it: {@code @ConditionalOnProperty(name = "generation.enabled",
 * havingValue = "true", matchIfMissing = false)}. No bean means
 * the tool is neither listed by {@code /api/agent-tools} nor executable through it,
 * so no agent can be handed it however its toolsConfig is written.
 */
@DisplayName("GenerationToolsProvider spend gate")
class GenerationToolsProviderGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(GenerationModule.class, () -> mock(GenerationModule.class))
            .withUserConfiguration(GenerationToolsProvider.class);

    @Test
    @DisplayName("the provider is absent when generation.enabled is not set at all")
    void absentWhenPropertyMissing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(GenerationToolsProvider.class));
    }

    @Test
    @DisplayName("the provider is absent when generation.enabled=false")
    void absentWhenDisabled() {
        runner.withPropertyValues("generation.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(GenerationToolsProvider.class));
    }

    @Test
    @DisplayName("a non-true value does not open the gate")
    void absentWhenNonTrueValue() {
        runner.withPropertyValues("generation.enabled=yes")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(GenerationToolsProvider.class));
    }

    @Test
    @DisplayName("the provider is wired, and serves the generation tool, when generation.enabled=true")
    void presentWhenEnabled() {
        runner.withPropertyValues("generation.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(GenerationToolsProvider.class);
            assertThat(ctx.getBean(GenerationToolsProvider.class).getTools())
                    .singleElement()
                    .satisfies(t -> assertThat(t.name()).isEqualTo("generation"));
        });
    }
}
