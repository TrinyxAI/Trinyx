package com.apimarketplace.common.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "external-paid-monolith")
public class ExternalSettlementOutboxAutoConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    ExternalSettlementIntentStore externalSettlementIntentStore(
            StringRedisTemplate redis, ObjectMapper json) {
        return new RedisExternalSettlementIntentStore(redis, json);
    }

    @Bean
    @ConditionalOnBean({ExternalSettlementIntentStore.class, CreditConsumptionClient.class})
    ExternalSettlementIntentDispatcher externalSettlementIntentDispatcher(
            ExternalSettlementIntentStore store, CreditConsumptionClient client) {
        return new ExternalSettlementIntentDispatcher(store, client);
    }
}
