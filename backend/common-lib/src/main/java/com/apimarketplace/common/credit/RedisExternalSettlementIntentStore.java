package com.apimarketplace.common.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Redis-backed producer settlement outbox shared by Cloud runtime services. */
public final class RedisExternalSettlementIntentStore
        implements ExternalSettlementIntentStore {

    private static final String PREFIX = "trinyx:billing:producer-outbox:";
    private static final String DUE = PREFIX + "due";
    private static final Duration ACTIVE_TTL = Duration.ofHours(48);
    private static final Duration TERMINAL_TTL = Duration.ofDays(7);
    private static final Duration CLAIM_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisExternalSettlementIntentStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override public boolean durable() { return true; }

    @Override
    public void persist(Intent intent) {
        try {
            String key = payloadKey(intent);
            String encoded = json.writeValueAsString(intent);
            String existing = redis.opsForValue().get(key);
            if (existing != null && !existing.equals(encoded)) {
                Intent prior = json.readValue(existing, Intent.class);
                if (!prior.body().equals(intent.body()) || !prior.url().equals(intent.url())) {
                    throw new IllegalStateException(
                            "settlement intent equivocation for " + intent.key());
                }
                return;
            }
            redis.opsForValue().set(key, encoded, ACTIVE_TTL);
            redis.opsForZSet().add(DUE, intent.key(), System.currentTimeMillis());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not persist settlement intent", failure);
        }
    }

    @Override
    public List<Intent> claimDue(int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(
                DUE, 0, System.currentTimeMillis(), 0, Math.max(1, limit));
        if (due == null || due.isEmpty()) return List.of();
        List<Intent> claimed = new ArrayList<>();
        for (String member : due) {
            Boolean lease = redis.opsForValue().setIfAbsent(
                    claimKey(member), UUID.randomUUID().toString(), CLAIM_TTL);
            if (!Boolean.TRUE.equals(lease)) continue;
            try {
                String encoded = redis.opsForValue().get(PREFIX + "item:" + member);
                if (encoded == null) {
                    redis.opsForZSet().remove(DUE, member);
                    redis.delete(claimKey(member));
                    continue;
                }
                claimed.add(json.readValue(encoded, Intent.class));
                redis.opsForZSet().add(DUE, member,
                        System.currentTimeMillis() + CLAIM_TTL.toMillis());
            } catch (Exception failure) {
                redis.delete(claimKey(member));
                throw new IllegalStateException("Could not claim settlement intent", failure);
            }
        }
        return claimed;
    }

    @Override
    public void acknowledge(Intent intent) {
        redis.opsForZSet().remove(DUE, intent.key());
        redis.delete(List.of(payloadKey(intent), claimKey(intent.key())));
    }

    @Override
    public void retry(Intent intent, String error) {
        Intent next = new Intent(intent.action(), intent.operationId(),
                intent.url(), intent.body(), intent.attempts() + 1);
        try {
            redis.opsForValue().set(payloadKey(next), json.writeValueAsString(next), ACTIVE_TTL);
            long cap = Math.min(300, 1L << Math.min(8, next.attempts()));
            long delay = ThreadLocalRandom.current().nextLong(
                    Math.max(1, cap / 2), cap + 1);
            redis.opsForZSet().add(DUE, next.key(),
                    System.currentTimeMillis() + Duration.ofSeconds(delay).toMillis());
            redis.delete(claimKey(next.key()));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not reschedule settlement intent", failure);
        }
    }

    @Override
    public void dead(Intent intent, String error) {
        try {
            redis.opsForValue().set(PREFIX + "dead:" + intent.key(),
                    json.writeValueAsString(Map.of(
                            "intent", intent,
                            "error", error == null ? "permanent rejection" : error)),
                    TERMINAL_TTL);
            acknowledge(intent);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not dead-letter settlement intent", failure);
        }
    }

    @Override
    public void recordUnknown(UUID operationId, Map<String, Object> details) {
        try {
            redis.opsForValue().set(PREFIX + "unknown:" + operationId,
                    json.writeValueAsString(Map.of(
                            "operationId", operationId,
                            "state", "UNKNOWN_PROVIDER_OUTCOME",
                            "details", details == null ? Map.of() : details)),
                    TERMINAL_TTL);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not persist ambiguous provider outcome", failure);
        }
    }

    private static String payloadKey(Intent intent) {
        return PREFIX + "item:" + intent.key();
    }
    private static String claimKey(String member) {
        return PREFIX + "claim:" + member;
    }
}
