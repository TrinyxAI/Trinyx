package com.apimarketplace.gateway;

import com.apimarketplace.common.web.GatewaySignatureV2;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRequestRateLimiterTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void incrementAndExpiryAreOneAtomicLuaExecution() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        AtomicReference<RedisScript<Long>> capturedScript = new AtomicReference<>();
        AtomicReference<List<String>> capturedKeys = new AtomicReference<>();
        AtomicReference<Object[]> capturedArguments = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    executions.incrementAndGet();
                    capturedScript.set(invocation.getArgument(0));
                    capturedKeys.set(invocation.getArgument(1));
                    capturedArguments.set(invocation.getArgument(2));
                    return Flux.just(1L);
                });

        GatewayRequestRateLimiter limiter =
                new GatewayRequestRateLimiter(redis, 120, 60_000);

        StepVerifier.create(limiter.allow("subject"))
                .expectNext(true)
                .verifyComplete();

        String expectedKey = "trinyx:gateway:rate:"
                + GatewaySignatureV2.sha256Hex(
                        "subject".getBytes(StandardCharsets.UTF_8));
        assertThat(executions).hasValue(1);
        assertThat(capturedKeys.get()).containsExactly(expectedKey);
        assertThat(capturedArguments.get()).containsExactly("60000");

        String lua = capturedScript.get().getScriptAsString();
        assertThat(lua)
                .contains("redis.call('INCR', KEYS[1])")
                .contains("if count == 1 then")
                .contains("redis.call('PEXPIRE', KEYS[1], ARGV[1])");
    }
}
