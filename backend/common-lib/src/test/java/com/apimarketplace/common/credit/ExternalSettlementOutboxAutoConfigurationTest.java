package com.apimarketplace.common.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExternalSettlementOutboxAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            RedisAutoConfiguration.class,
                            ExternalSettlementOutboxAutoConfiguration.class))
                    .withPropertyValues(
                            "billing.authority.mode=external-paid-monolith")
                    .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void webSearchTemplateCannotSuppressCanonicalFinancialOutbox() {
        RedisConnectionFactory canonicalFactory = mock(RedisConnectionFactory.class);
        RedisConnectionFactory webSearchFactory = mock(RedisConnectionFactory.class);
        StringRedisTemplate webSearch = new StringRedisTemplate(webSearchFactory);

        contextRunner
                .withBean("redisConnectionFactory", RedisConnectionFactory.class,
                        () -> canonicalFactory)
                .withBean("webSearchRedisTemplate", StringRedisTemplate.class,
                        () -> webSearch)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("webSearchRedisTemplate");
                    assertThat(context.containsBean("stringRedisTemplate")).isFalse();
                    assertThat(context).hasSingleBean(ExternalSettlementIntentStore.class);

                    ExternalSettlementIntentStore store =
                            context.getBean(ExternalSettlementIntentStore.class);
                    assertThat(store).isInstanceOf(RedisExternalSettlementIntentStore.class);
                    assertThat(store.durable()).isTrue();

                    StringRedisTemplate financialTemplate =
                            (StringRedisTemplate) ReflectionTestUtils.getField(store, "redis");
                    assertThat(financialTemplate)
                            .isNotNull()
                            .isNotSameAs(webSearch);
                    assertThat(financialTemplate.getConnectionFactory())
                            .isSameAs(canonicalFactory)
                            .isNotSameAs(webSearchFactory);
                });
    }

    @Test
    void doesNotChooseAmongAmbiguousConnectionFactories() {
        RedisConnectionFactory first = mock(RedisConnectionFactory.class);
        RedisConnectionFactory second = mock(RedisConnectionFactory.class);

        contextRunner
                .withBean("firstRedisConnectionFactory", RedisConnectionFactory.class,
                        () -> first)
                .withBean("secondRedisConnectionFactory", RedisConnectionFactory.class,
                        () -> second)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(ExternalSettlementIntentStore.class);
                });
    }

    @Test
    void doesNotUseWebSearchTemplateWithoutCanonicalConnectionFactory() {
        RedisConnectionFactory webSearchFactory = mock(RedisConnectionFactory.class);
        StringRedisTemplate webSearch = new StringRedisTemplate(webSearchFactory);

        contextRunner
                .withBean("webSearchRedisTemplate", StringRedisTemplate.class,
                        () -> webSearch)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(ExternalSettlementIntentStore.class);
                });
    }
}
