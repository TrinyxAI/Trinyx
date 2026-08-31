package com.apimarketplace.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Selects the distributed Gateway replay store from the canonical Redis
 * connection factory. The nonce store deliberately owns its template: other
 * templates may use blocking or workload-specific timeouts and must never be
 * selected by type guessing.
 *
 * <p>This class is isolated from {@link GatewayWebAutoConfiguration} so a
 * service without Spring Data Redis remains loadable. If Cloud requires a
 * distributed store and this configuration cannot create one, the in-memory
 * fallback is still rejected by {@link GatewayAuthenticationFilter}.</p>
 */
@AutoConfiguration(before = GatewayWebAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({RedisConnectionFactory.class, StringRedisTemplate.class})
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class GatewayRedisNonceStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GatewayNonceStore.class)
    @ConditionalOnSingleCandidate(RedisConnectionFactory.class)
    public GatewayNonceStore redisGatewayNonceStore(RedisConnectionFactory connectionFactory) {
        return new RedisGatewayNonceStore(new StringRedisTemplate(connectionFactory));
    }
}
