package com.apimarketplace.common.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis-backed producer settlement outbox shared by Cloud runtime services.
 *
 * <p>Production Compose enables AOF and mounts /data. This store therefore
 * survives application/container restarts while the Redis volume is retained.
 * It is not a substitute for replicated infrastructure or volume backups.</p>
 */
public final class RedisExternalSettlementIntentStore
        implements ExternalSettlementIntentStore {

    private static final String PREFIX = "trinyx:billing:producer-outbox:";
    private static final String DUE = PREFIX + "due";
    private static final String PROVIDER_DISPATCH_DUE = PREFIX + "provider-dispatch-due";
    private static final Duration ACTIVE_TTL = Duration.ofHours(48);
    private static final Duration TERMINAL_TTL = Duration.ofDays(7);
    private static final Duration CLAIM_TTL = Duration.ofSeconds(60);
    // Earlier than the authoritative 10-minute hold TTL. A legitimately long
    // provider call may be marked UNKNOWN and can still commit idempotently later.
    private static final Duration DISPATCH_UNKNOWN_AFTER = Duration.ofMinutes(8);

    private static final DefaultRedisScript<Long> MARK_DISPATCHING =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[1])
                    if not raw then return -1 end
                    local ok, operation = pcall(cjson.decode, raw)
                    if not ok or operation['state'] ~= 'RESERVED' then return -2 end
                    if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
                    redis.call('PSETEX', KEYS[1], ARGV[1], ARGV[2])
                    redis.call('PSETEX', KEYS[2], ARGV[1], ARGV[2])
                    redis.call('ZADD', KEYS[3], ARGV[3], ARGV[4])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> RECORD_OUTCOME_UNKNOWN =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[1])
                    if not raw then return -1 end
                    local ok, operation = pcall(cjson.decode, raw)
                    if not ok then return -2 end
                    local current = operation['state']
                    if current == 'OUTCOME_UNKNOWN' then
                        redis.call('PERSIST', KEYS[1])
                        redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
                        redis.call('ZREM', KEYS[3], ARGV[4])
                        redis.call('DEL', KEYS[4], KEYS[5])
                        return 2
                    end
                    if current ~= 'DISPATCHING' then
                        redis.call('ZREM', KEYS[8], ARGV[5])
                        redis.call('DEL', KEYS[6], KEYS[7])
                        redis.call('ZREM', KEYS[3], ARGV[4])
                        redis.call('DEL', KEYS[4], KEYS[5])
                        return 0
                    end
                    operation['state'] = 'OUTCOME_UNKNOWN'
                    operation['updatedAt'] = ARGV[1]
                    redis.call('SET', KEYS[1], cjson.encode(operation))
                    redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
                    redis.call('ZREM', KEYS[3], ARGV[4])
                    redis.call('DEL', KEYS[4], KEYS[5])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> PERSIST_INTENT =
            new DefaultRedisScript<>("""
                    local existing = redis.call('GET', KEYS[1])
                    if existing then
                        if existing == ARGV[1] then return 2 end
                        return -1
                    end
                    redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
                    redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> ACKNOWLEDGE_INTENT =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[4])
                    if not raw then return -1 end
                    local ok, operation = pcall(cjson.decode, raw)
                    if not ok then return -2 end
                    local current = operation['state']
                    if (current == 'COMMITTED' or current == 'RELEASED')
                            and current ~= ARGV[2] then
                        return -3
                    end
                    if current == 'SETTLEMENT_FAILED'
                            and ARGV[2] ~= 'COMMITTED'
                            and ARGV[2] ~= 'RELEASED' then
                        return -3
                    end
                    operation['state'] = ARGV[2]
                    operation['updatedAt'] = ARGV[3]
                    if ARGV[2] == 'OUTCOME_UNKNOWN' then
                        redis.call('SET', KEYS[4], cjson.encode(operation))
                    else
                        redis.call('PSETEX', KEYS[4], ARGV[4], cjson.encode(operation))
                    end
                    redis.call('ZREM', KEYS[3], ARGV[1])
                    redis.call('DEL', KEYS[1], KEYS[2], KEYS[5], KEYS[6])
                    redis.call('ZREM', KEYS[7], ARGV[5])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> DEAD_LETTER_INTENT =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[5])
                    if raw then
                        local ok, operation = pcall(cjson.decode, raw)
                        if ok then
                            local current = operation['state']
                            if current ~= 'COMMITTED' and current ~= 'RELEASED'
                                    and current ~= 'SETTLEMENT_FAILED' then
                                operation['state'] = 'SETTLEMENT_FAILED'
                                operation['updatedAt'] = ARGV[5]
                                redis.call('PSETEX', KEYS[5], ARGV[2], cjson.encode(operation))
                            end
                        end
                    end
                    redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
                    redis.call('ZREM', KEYS[4], ARGV[3])
                    redis.call('DEL', KEYS[2], KEYS[3], KEYS[6], KEYS[7])
                    redis.call('ZREM', KEYS[8], ARGV[4])
                    return 1
                    """, Long.class);

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
            if (existing != null) {
                Intent prior = json.readValue(existing, Intent.class);
                if (!prior.body().equals(intent.body()) || !prior.url().equals(intent.url())
                        || !prior.trustedHeaders().equals(intent.trustedHeaders())) {
                    throw new IllegalStateException(
                            "settlement intent equivocation for " + intent.key());
                }
                return;
            }
            Long result = redis.execute(PERSIST_INTENT, List.of(key, DUE),
                    encoded, String.valueOf(ACTIVE_TTL.toMillis()),
                    String.valueOf(System.currentTimeMillis()), intent.key());
            if (result != null && result == -1L) {
                throw new IllegalStateException(
                        "settlement intent equivocation for " + intent.key());
            }
            if (result == null) {
                throw new IllegalStateException(
                        "Could not atomically persist settlement intent " + intent.key());
            }
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
        String state = switch (intent.action()) {
            case "COMMIT_LLM", "COMMIT_AMOUNT" -> "COMMITTED";
            case "RELEASE", "RELEASE_LOCAL" -> "RELEASED";
            case "OUTCOME_UNKNOWN" -> "OUTCOME_UNKNOWN";
            default -> null;
        };
        if (state == null) {
            throw new IllegalArgumentException(
                    "Unsupported settlement action " + intent.action());
        }
        Long result = redis.execute(ACKNOWLEDGE_INTENT, List.of(
                        payloadKey(intent), claimKey(intent.key()), DUE,
                        operationKey(intent.operationId()), dispatchKey(intent.operationId()),
                        dispatchClaimKey(intent.operationId()), PROVIDER_DISPATCH_DUE),
                intent.key(), state, Instant.now().toString(),
                String.valueOf((isTerminal(state) ? TERMINAL_TTL : ACTIVE_TTL).toMillis()),
                intent.operationId().toString());
        if (result == null || result < 0) {
            throw new IllegalStateException(
                    "Could not atomically acknowledge settlement intent "
                            + intent.key() + " result=" + result);
        }
    }

    @Override
    public void retry(Intent intent, String error) {
        Intent next = new Intent(intent.action(), intent.operationId(),
                intent.url(), intent.body(), intent.attempts() + 1,
                intent.trustedHeaders());
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
            String encoded = json.writeValueAsString(Map.of(
                    "intent", intent,
                    "error", error == null ? "permanent rejection" : error));
            Long result = redis.execute(DEAD_LETTER_INTENT, List.of(
                            PREFIX + "dead:" + intent.key(), payloadKey(intent),
                            claimKey(intent.key()), DUE, operationKey(intent.operationId()),
                            dispatchKey(intent.operationId()),
                            dispatchClaimKey(intent.operationId()), PROVIDER_DISPATCH_DUE),
                    encoded, String.valueOf(TERMINAL_TTL.toMillis()), intent.key(),
                    intent.operationId().toString(), Instant.now().toString());
            if (result == null || result != 1L) {
                throw new IllegalStateException(
                        "Could not atomically dead-letter settlement intent " + intent.key());
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not dead-letter settlement intent", failure);
        }
    }

    @Override
    public void recordUnknown(UUID operationId, Map<String, Object> details) {
        try {
            String audit = json.writeValueAsString(Map.of(
                    "operationId", operationId,
                    "state", "UNKNOWN_PROVIDER_OUTCOME",
                    "details", details == null ? Map.of() : details));
            String unknownIntentKey = "OUTCOME_UNKNOWN:" + operationId;
            Long result = redis.execute(RECORD_OUTCOME_UNKNOWN, List.of(
                            operationKey(operationId), PREFIX + "unknown:" + operationId,
                            PROVIDER_DISPATCH_DUE, dispatchKey(operationId),
                            dispatchClaimKey(operationId),
                            PREFIX + "item:" + unknownIntentKey,
                            claimKey(unknownIntentKey), DUE),
                    Instant.now().toString(), audit,
                    String.valueOf(TERMINAL_TTL.toMillis()),
                    operationId.toString(), unknownIntentKey);
            if (result == null || result < 0) {
                throw new IllegalStateException(
                        "Could not atomically record ambiguous provider outcome "
                                + operationId + " result=" + result);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not persist ambiguous provider outcome", failure);
        }
    }

    @Override
    public void registerProviderOperation(ProviderOperation operation) {
        try {
            ProviderOperation reserved = new ProviderOperation(
                    operation.operationId(), value(operation.requestHash()),
                    value(operation.provider()), value(operation.model()),
                    operation.outcomeUnknownUrl(), operation.trustedHeaders(),
                    "RESERVED", Instant.now());
            String key = operationKey(operation.operationId());
            String encoded = json.writeValueAsString(reserved);
            Boolean created = redis.opsForValue().setIfAbsent(key, encoded, ACTIVE_TTL);
            if (Boolean.TRUE.equals(created)) return;

            String existing = redis.opsForValue().get(key);
            if (existing == null) {
                throw new IllegalStateException(
                        "provider operation disappeared during registration "
                                + operation.operationId());
            }
            ProviderOperation prior = json.readValue(existing, ProviderOperation.class);
            if (!prior.requestHash().equals(reserved.requestHash())
                    || !prior.provider().equals(reserved.provider())
                    || !prior.model().equals(reserved.model())
                    || !prior.outcomeUnknownUrl().equals(reserved.outcomeUnknownUrl())
                    || (!reserved.trustedHeaders().isEmpty()
                        && !prior.trustedHeaders().equals(reserved.trustedHeaders()))) {
                throw new IllegalStateException(
                        "provider operation equivocation for " + operation.operationId());
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not register provider operation", failure);
        }
    }

    @Override
    public boolean markProviderDispatching(UUID operationId) {
        try {
            ProviderOperation operation = readOperation(operationId);
            if (operation == null || !"RESERVED".equals(operation.state())) return false;
            ProviderOperation dispatching = new ProviderOperation(
                    operation.operationId(), operation.requestHash(), operation.provider(),
                    operation.model(), operation.outcomeUnknownUrl(),
                    operation.trustedHeaders(), "DISPATCHING", Instant.now());
            long dueAt = System.currentTimeMillis() + DISPATCH_UNKNOWN_AFTER.toMillis();
            Long result = redis.execute(MARK_DISPATCHING,
                    List.of(operationKey(operationId), dispatchKey(operationId),
                            PROVIDER_DISPATCH_DUE),
                    String.valueOf(ACTIVE_TTL.toMillis()),
                    json.writeValueAsString(dispatching),
                    String.valueOf(dueAt), operationId.toString());
            return result != null && result == 1L;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not persist provider dispatch state", failure);
        }
    }

    @Override
    public List<ProviderOperation> claimStaleProviderDispatches(int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(
                PROVIDER_DISPATCH_DUE, 0, System.currentTimeMillis(),
                0, Math.max(1, limit));
        if (due == null || due.isEmpty()) return List.of();
        List<ProviderOperation> result = new ArrayList<>();
        for (String rawId : due) {
            UUID operationId;
            try {
                operationId = UUID.fromString(rawId);
            } catch (IllegalArgumentException invalid) {
                redis.opsForZSet().remove(PROVIDER_DISPATCH_DUE, rawId);
                continue;
            }
            Boolean lease = redis.opsForValue().setIfAbsent(
                    dispatchClaimKey(operationId), UUID.randomUUID().toString(), CLAIM_TTL);
            if (!Boolean.TRUE.equals(lease)) continue;
            try {
                String encoded = redis.opsForValue().get(dispatchKey(operationId));
                if (encoded == null) {
                    redis.opsForZSet().remove(PROVIDER_DISPATCH_DUE, rawId);
                    redis.delete(dispatchClaimKey(operationId));
                    continue;
                }
                result.add(json.readValue(encoded, ProviderOperation.class));
                redis.opsForZSet().add(PROVIDER_DISPATCH_DUE, rawId,
                        System.currentTimeMillis() + CLAIM_TTL.toMillis());
            } catch (Exception failure) {
                redis.delete(dispatchClaimKey(operationId));
                throw new IllegalStateException(
                        "Could not claim stale provider dispatch", failure);
            }
        }
        return result;
    }

    @Override
    public ProviderOperation providerOperation(UUID operationId) {
        return readOperation(operationId);
    }

    @Override
    public Map<String, String> trustedHeaders(UUID operationId) {
        ProviderOperation operation = readOperation(operationId);
        return operation == null ? Map.of() : operation.trustedHeaders();
    }

    private ProviderOperation readOperation(UUID operationId) {
        try {
            String encoded = redis.opsForValue().get(operationKey(operationId));
            return encoded == null ? null : json.readValue(encoded, ProviderOperation.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not read provider operation", failure);
        }
    }

    private static boolean isTerminal(String state) {
        return "COMMITTED".equals(state) || "RELEASED".equals(state)
                || "SETTLEMENT_FAILED".equals(state);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String payloadKey(Intent intent) {
        return PREFIX + "item:" + intent.key();
    }
    private static String claimKey(String member) {
        return PREFIX + "claim:" + member;
    }
    private static String operationKey(UUID operationId) {
        return PREFIX + "operation:" + operationId;
    }
    private static String dispatchKey(UUID operationId) {
        return PREFIX + "dispatch:" + operationId;
    }
    private static String dispatchClaimKey(UUID operationId) {
        return PREFIX + "dispatch-claim:" + operationId;
    }
}
