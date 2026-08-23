package com.apimarketplace.common.web;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

/** Redis SET NX implementation shared by all replicas of a service. */
public final class RedisGatewayNonceStore implements GatewayNonceStore {

    private static final String PREFIX = "trinyx:gateway:nonce:";
    private final StringRedisTemplate redis;

    public RedisGatewayNonceStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public boolean consume(String providerId, String nonce, Duration ttl) {
        String key = PREFIX + (providerId == null ? "" : providerId) + ":" + nonce;
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
    }

    @Override
    public boolean distributed() {
        return true;
    }
}
