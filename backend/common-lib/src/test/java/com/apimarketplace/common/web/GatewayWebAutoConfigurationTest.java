package com.apimarketplace.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GatewayWebAutoConfigurationTest {

    private static final String TEST_SECRET =
            "test-only-gateway-secret-with-at-least-thirty-two-characters";

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            GatewayRedisNonceStoreAutoConfiguration.class,
                            GatewayWebAutoConfiguration.class))
                    .withPropertyValues(
                            "deployment.mode=microservice",
                            "gateway.filter.verification-enabled=true",
                            "gateway.filter.secret-key=" + TEST_SECRET,
                            "gateway.filter.accept-v1=false");

    @Test
    void loadsWithoutSpringDataRedisAndUsesMemoryOnlyWhenDistributedStoreIsNotRequired() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("org.springframework.data.redis"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GatewayNonceStore.class);
                    assertThat(context.getBean(GatewayNonceStore.class))
                            .isInstanceOf(InMemoryGatewayNonceStore.class);
                    assertThat(context.getBean(GatewayFilterProperties.class).isAcceptV1())
                            .isFalse();
                });
    }

    @Test
    void createsDistributedStoreFromCanonicalConnectionFactory() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        contextRunner
                .withBean(RedisConnectionFactory.class, () -> connectionFactory)
                .withPropertyValues("gateway.filter.require-distributed-nonce-store=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GatewayNonceStore.class);
                    assertThat(context.getBean(GatewayNonceStore.class))
                            .isInstanceOf(RedisGatewayNonceStore.class);
                    assertThat(context.getBean(GatewayNonceStore.class).distributed()).isTrue();
                    assertThat(context).hasSingleBean(GatewayAuthenticationFilter.class);
                });
    }

    @Test
    void ignoresMultipleWorkloadSpecificStringRedisTemplates() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        contextRunner
                .withBean(RedisConnectionFactory.class, () -> connectionFactory)
                .withBean("stringRedisTemplate", StringRedisTemplate.class,
                        () -> new StringRedisTemplate(connectionFactory))
                .withBean("webSearchRedisTemplate", StringRedisTemplate.class,
                        () -> new StringRedisTemplate(connectionFactory))
                .withPropertyValues("gateway.filter.require-distributed-nonce-store=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(StringRedisTemplate.class)).hasSize(2);
                    assertThat(context.getBean(GatewayNonceStore.class))
                            .isInstanceOf(RedisGatewayNonceStore.class);
                });
    }

    @Test
    void refusesStartupWhenDistributedStoreIsRequiredButOnlyMemoryIsAvailable() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("org.springframework.data.redis"))
                .withPropertyValues("gateway.filter.require-distributed-nonce-store=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "Gateway HMAC v2 requires a distributed nonce store in this environment");
                });
    }
}
