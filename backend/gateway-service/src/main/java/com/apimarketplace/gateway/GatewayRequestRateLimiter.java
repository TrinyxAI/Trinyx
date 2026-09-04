package com.apimarketplace.gateway;

import com.apimarketplace.common.web.GatewaySignatureV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Distributed authenticated-request limiter. Every gateway replica shares the same Redis window;
 * Redis failure is fail-closed because accepting unbounded bodies during a control-plane outage
 * would defeat the heap protection this boundary provides.
 */
@Component
class GatewayRequestRateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final int requestsPerWindow;
    private final long windowMs;

    GatewayRequestRateLimiter(
            ReactiveStringRedisTemplate redis,
            @Value("${trinyx.gateway.rate-limit.requests-per-window:120}") int requestsPerWindow,
            @Value("${trinyx.gateway.rate-limit.window-ms:60000}") long windowMs) {
        this.redis = redis;
        this.requestsPerWindow = Math.max(1, requestsPerWindow);
        this.windowMs = Math.max(1000L, windowMs);
    }

    Mono<Boolean> allow(String subject) {
        if (subject == null || subject.isBlank()) {
            return Mono.just(false);
        }
        String subjectHash = GatewaySignatureV2.sha256Hex(
                subject.getBytes(StandardCharsets.UTF_8));
        String key = "trinyx:gateway:rate:" + subjectHash;
        return redis.execute(INCREMENT, List.of(key), Long.toString(windowMs))
                .next()
                .map(count -> count != null && count <= requestsPerWindow)
                .defaultIfEmpty(false)
                .onErrorReturn(false);
    }
}
