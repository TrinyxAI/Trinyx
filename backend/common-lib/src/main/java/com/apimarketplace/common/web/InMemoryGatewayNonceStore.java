package com.apimarketplace.common.web;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Test/dev fallback. Production Cloud can require Redis through configuration. */
public final class InMemoryGatewayNonceStore implements GatewayNonceStore {

    private final ConcurrentHashMap<String, Long> expirations = new ConcurrentHashMap<>();

    @Override
    public void assertAvailable() {
        throw new IllegalStateException(
                "In-memory gateway nonce store is not a distributed backend");
    }

    @Override
    public boolean consume(String providerId, String nonce, Duration ttl) {
        long now = System.currentTimeMillis();
        if ((expirations.size() & 255) == 0) {
            expirations.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        String key = (providerId == null ? "" : providerId) + ":" + nonce;
        long expiresAt = now + Math.max(1, ttl.toMillis());
        for (;;) {
            Long existing = expirations.get(key);
            if (existing != null && existing > now) {
                return false;
            }
            if (existing == null) {
                if (expirations.putIfAbsent(key, expiresAt) == null) {
                    return true;
                }
            } else if (expirations.replace(key, existing, expiresAt)) {
                return true;
            }
        }
    }
}
