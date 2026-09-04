package com.apimarketplace.common.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({RedisConnectionFactory.class, StringRedisTemplate.class})
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "external-paid-monolith")
public class ExternalSettlementOutboxAutoConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(RedisConnectionFactory.class)
    ExternalSettlementIntentStore externalSettlementIntentStore(
            RedisConnectionFactory connectionFactory,
            ObjectMapper json) {
        return new RedisExternalSettlementIntentStore(
                new StringRedisTemplate(connectionFactory), json);
    }

    @Bean
    @ConditionalOnBean({ExternalSettlementIntentStore.class, CreditConsumptionClient.class})
    ExternalSettlementIntentDispatcher externalSettlementIntentDispatcher(
            ExternalSettlementIntentStore store, CreditConsumptionClient client) {
        return new ExternalSettlementIntentDispatcher(store, client);
    }
}
