package com.apimarketplace.common.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
                            ExternalSettlementOutboxAutoConfiguration.class))
                    .withPropertyValues(
                            "billing.authority.mode=external-paid-monolith")
                    .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void selectsCanonicalTemplateWhenWorkloadSpecificTemplateAlsoExists() {
        RedisConnectionFactory canonicalFactory = mock(RedisConnectionFactory.class);
        RedisConnectionFactory webSearchFactory = mock(RedisConnectionFactory.class);
        StringRedisTemplate canonical = new StringRedisTemplate(canonicalFactory);
        StringRedisTemplate webSearch = new StringRedisTemplate(webSearchFactory);

        contextRunner
                .withBean("stringRedisTemplate", StringRedisTemplate.class,
                        () -> canonical)
                .withBean("webSearchRedisTemplate", StringRedisTemplate.class,
                        () -> webSearch)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(StringRedisTemplate.class))
                            .hasSize(2);
                    assertThat(context).hasSingleBean(ExternalSettlementIntentStore.class);

                    ExternalSettlementIntentStore store =
                            context.getBean(ExternalSettlementIntentStore.class);
                    assertThat(store).isInstanceOf(RedisExternalSettlementIntentStore.class);
                    assertThat(ReflectionTestUtils.getField(store, "redis"))
                            .isSameAs(canonical)
                            .isNotSameAs(webSearch);
                });
    }

    @Test
    void doesNotUseWebSearchTemplateAsFinancialStore() {
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
