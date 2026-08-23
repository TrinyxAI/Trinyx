package com.apimarketplace.common.web;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Test/dev fallback. Production Cloud can require Redis through configuration. */
public final class InMemoryGatewayNonceStore implements GatewayNonceStore {

    private final ConcurrentHashMap<String, Long> expirations = new ConcurrentHashMap<>();

    @Override
    public boolean consume(String providerId, String nonce, Duration ttl) {
        long now = System.currentTimeMillis();
        if ((expirations.size() & 255) == 0) {
            expirations.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        String key = (providerId == null ? "" : providerId) + ":" + nonce;
        long expiresAt = now + Math.max(1, ttl.toMillis());
        return expirations.compute(key, (ignored, existing) ->
                existing == null || existing <= now ? expiresAt : existing) == expiresAt;
    }
}
