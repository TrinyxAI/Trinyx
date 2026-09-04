package com.apimarketplace.common.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger =
            LoggerFactory.getLogger(RedisExternalSettlementIntentStore.class);

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
                    redis.call('SET', KEYS[1], ARGV[1])
                    redis.call('SET', KEYS[2], ARGV[1])
                    redis.call('ZADD', KEYS[3], ARGV[2], ARGV[3])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> RECORD_OUTCOME_UNKNOWN =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[1])
                    if not raw then return -1 end
                    local ok, operation = pcall(cjson.decode, raw)
                    if not ok then return -2 end
                    if ARGV[6] ~= '' then
                        local owner = redis.call('GET', KEYS[5])
                        if not owner or owner ~= ARGV[6] then return -4 end
                    end
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
                    if ARGV[7] ~= '' then
                        local existing = redis.call('GET', KEYS[6])
                        if existing then
                            local existingOk, existingIntent =
                                    pcall(cjson.decode, existing)
                            local existingBody = existingOk
                                    and existingIntent['body'] or nil
                            local existingHeaders = existingOk
                                    and (existingIntent['trustedHeaders'] or {}) or {}
                            local operationHeaders =
                                    operation['trustedHeaders'] or {}
                            local function sameMap(left, right)
                                if type(left) ~= 'table'
                                        or type(right) ~= 'table' then
                                    return false
                                end
                                for key, value in pairs(left) do
                                    if right[key] ~= value then return false end
                                end
                                for key, value in pairs(right) do
                                    if left[key] ~= value then return false end
                                end
                                return true
                            end
                            if not existingOk
                                    or existingIntent['action'] ~= 'OUTCOME_UNKNOWN'
                                    or existingIntent['operationId'] ~= ARGV[4]
                                    or existingIntent['url']
                                            ~= operation['outcomeUnknownUrl']
                                    or type(existingBody) ~= 'table'
                                    or existingBody['requestHash']
                                            ~= operation['requestHash']
                                    or existingBody['provider']
                                            ~= operation['provider']
                                    or existingBody['model']
                                            ~= operation['model']
                                    or not sameMap(
                                            existingHeaders, operationHeaders) then
                                return -5
                            end
                        else
                            redis.call('SET', KEYS[6], ARGV[7])
                        end
                        redis.call('PERSIST', KEYS[6])
                        redis.call('ZADD', KEYS[8], ARGV[8], ARGV[5])
                    end
                    operation['state'] = 'OUTCOME_UNKNOWN'
                    operation['updatedAt'] = ARGV[1]
                    redis.call('SET', KEYS[1], cjson.encode(operation))
                    redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
                    redis.call('ZREM', KEYS[3], ARGV[4])
                    redis.call('DEL', KEYS[4], KEYS[5])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> FINALIZE_DISPATCH_CLAIM =
            new DefaultRedisScript<>("""
                    local owner = redis.call('GET', KEYS[1])
                    if not owner or owner ~= ARGV[1] then return 0 end
                    if ARGV[3] == 'touch' then
                        redis.call('PEXPIRE', KEYS[1], ARGV[5])
                        redis.call('ZADD', KEYS[2], ARGV[4], ARGV[2])
                        return 1
                    end
                    if ARGV[3] == 'remove' then
                        redis.call('ZREM', KEYS[2], ARGV[2])
                    elseif ARGV[3] ~= 'release' then
                        return -1
                    end
                    redis.call('DEL', KEYS[1])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> QUARANTINE_CORRUPT_DISPATCH =
            new DefaultRedisScript<>("""
                    local owner = redis.call('GET', KEYS[1])
                    if not owner or owner ~= ARGV[1] then return 0 end
                    redis.call('SET', KEYS[4], ARGV[3])
                    redis.call('PERSIST', KEYS[4])
                    redis.call('ZREM', KEYS[2], ARGV[2])
                    redis.call('DEL', KEYS[3], KEYS[1])
                    local raw = redis.call('GET', KEYS[5])
                    if raw then
                        local ok, operation = pcall(cjson.decode, raw)
                        if ok then
                            local current = operation['state']
                            if current ~= 'COMMITTED'
                                    and current ~= 'RELEASED'
                                    and current ~= 'SETTLEMENT_FAILED' then
                                operation['state'] = 'SETTLEMENT_FAILED'
                                operation['updatedAt'] = ARGV[4]
                                redis.call('SET', KEYS[5], cjson.encode(operation))
                            end
                        end
                    end
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> PERSIST_INTENT =
            new DefaultRedisScript<>("""
                    local existing = redis.call('GET', KEYS[1])
                    if existing then
                        if existing == ARGV[1] then
                            redis.call('PERSIST', KEYS[1])
                            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
                            return 2
                        end
                        return -1
                    end
                    redis.call('SET', KEYS[1], ARGV[1])
                    redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> RESCHEDULE_EXISTING_INTENT =
            new DefaultRedisScript<>("""
                    local current = redis.call('GET', KEYS[1])
                    if not current or current ~= ARGV[1] then return 0 end
                    redis.call('PERSIST', KEYS[1])
                    redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> CLAIM_INTENT =
            new DefaultRedisScript<>("""
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        redis.call('ZREM', KEYS[3], ARGV[3])
                        return 0
                    end
                    local claimed = redis.call(
                            'SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[2])
                    if not claimed then return 0 end
                    redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> CLEANUP_MISSING_INTENT =
            new DefaultRedisScript<>("""
                    local owner = redis.call('GET', KEYS[1])
                    if not owner or owner ~= ARGV[1] then return 0 end
                    if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
                    redis.call('ZREM', KEYS[3], ARGV[2])
                    redis.call('DEL', KEYS[1])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> RETRY_INTENT =
            new DefaultRedisScript<>("""
                    local existing = redis.call('GET', KEYS[1])
                    if not existing then
                        redis.call('ZREM', KEYS[2], ARGV[3])
                        local owner = redis.call('GET', KEYS[3])
                        if owner == ARGV[4] then redis.call('DEL', KEYS[3]) end
                        return 0
                    end
                    local owner = redis.call('GET', KEYS[3])
                    if not owner or owner ~= ARGV[4] then return -4 end
                    redis.call('SET', KEYS[1], ARGV[1])
                    redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
                    redis.call('DEL', KEYS[3])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> ACKNOWLEDGE_INTENT =
            new DefaultRedisScript<>("""
                    local owner = redis.call('GET', KEYS[2])
                    if not owner or owner ~= ARGV[6] then return -4 end
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
                            and ARGV[2] == 'OUTCOME_UNKNOWN' then
                        -- The authority may idempotently confirm an older UNKNOWN
                        -- after a different local settlement intent was dead-lettered.
                        -- Keep the stronger local failure state, but atomically retire
                        -- this now-superseded transport message.
                        redis.call('ZREM', KEYS[3], ARGV[1])
                        redis.call('DEL', KEYS[1], KEYS[2])
                        return 2
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
                    local owner = redis.call('GET', KEYS[3])
                    if not owner or owner ~= ARGV[6] then return -4 end
                    local unresolved = true
                    local raw = redis.call('GET', KEYS[5])
                    if raw then
                        local ok, operation = pcall(cjson.decode, raw)
                        if ok then
                            local current = operation['state']
                            if current == 'COMMITTED' or current == 'RELEASED' then
                                unresolved = false
                            elseif current == 'SETTLEMENT_FAILED' then
                                -- Repair keys written by older builds: a transport/dead-letter
                                -- state is still reconcilable and must outlive any fixed TTL.
                                redis.call('PERSIST', KEYS[5])
                            else
                                operation['state'] = 'SETTLEMENT_FAILED'
                                operation['updatedAt'] = ARGV[5]
                                redis.call('SET', KEYS[5], cjson.encode(operation))
                            end
                        end
                    end
                    if unresolved then
                        -- SETTLEMENT_FAILED is not financially terminal. Preserve the complete
                        -- payload until an authoritative COMMIT/RELEASE or manual reconciliation.
                        redis.call('SET', KEYS[1], ARGV[1])
                    else
                        redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
                    end
                    redis.call('ZREM', KEYS[4], ARGV[3])
                    redis.call('DEL', KEYS[2], KEYS[3], KEYS[6], KEYS[7])
                    redis.call('ZREM', KEYS[8], ARGV[4])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> REPAIR_DEAD_LETTER =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[2])
                    if not raw then return 0 end
                    local ok, operation = pcall(cjson.decode, raw)
                    if not ok or operation['state'] ~= 'SETTLEMENT_FAILED' then
                        return 0
                    end
                    redis.call('PERSIST', KEYS[2])
                    if redis.call('EXISTS', KEYS[1]) == 1 then
                        redis.call('PERSIST', KEYS[1])
                    end
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> QUARANTINE_CORRUPT_INTENT =
            new DefaultRedisScript<>("""
                    local owner = redis.call('GET', KEYS[3])
                    if not owner or owner ~= ARGV[5] then return -4 end
                    local raw = redis.call('GET', KEYS[5])
                    if raw then
                        local ok, operation = pcall(cjson.decode, raw)
                        if ok then
                            local current = operation['state']
                            if current ~= 'COMMITTED' and current ~= 'RELEASED' then
                                operation['state'] = 'SETTLEMENT_FAILED'
                                operation['updatedAt'] = ARGV[4]
                                redis.call('SET', KEYS[5], cjson.encode(operation))
                            end
                        end
                    end
                    redis.call('SET', KEYS[1], ARGV[1])
                    redis.call('ZREM', KEYS[4], ARGV[2])
                    redis.call('DEL', KEYS[2], KEYS[3], KEYS[6], KEYS[7])
                    redis.call('ZREM', KEYS[8], ARGV[3])
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
                redis.execute(RESCHEDULE_EXISTING_INTENT, List.of(key, DUE),
                        existing, String.valueOf(System.currentTimeMillis()), intent.key());
                return;
            }
            Long result = redis.execute(PERSIST_INTENT, List.of(key, DUE),
                    encoded, String.valueOf(System.currentTimeMillis()), intent.key());
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
    public Intent claim(Intent intent) {
        String token = UUID.randomUUID().toString();
        Long result = redis.execute(CLAIM_INTENT,
                List.of(payloadKey(intent), claimKey(intent.key()), DUE),
                token, String.valueOf(CLAIM_TTL.toMillis()), intent.key(),
                String.valueOf(System.currentTimeMillis() + CLAIM_TTL.toMillis()));
        return result != null && result == 1L ? intent.claimedBy(token) : null;
    }

    @Override
    public List<Intent> claimDue(int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(
                DUE, 0, System.currentTimeMillis(), 0, Math.max(1, limit));
        if (due == null || due.isEmpty()) return List.of();
        List<Intent> claimed = new ArrayList<>();
        for (String member : due) {
            String token = UUID.randomUUID().toString();
            Long claim = redis.execute(CLAIM_INTENT,
                    List.of(PREFIX + "item:" + member, claimKey(member), DUE),
                    token, String.valueOf(CLAIM_TTL.toMillis()), member,
                    String.valueOf(System.currentTimeMillis() + CLAIM_TTL.toMillis()));
            if (claim == null || claim != 1L) continue;
            String encoded = redis.opsForValue().get(PREFIX + "item:" + member);
            if (encoded == null) {
                cleanupMissingIntent(member, token);
                continue;
            }
            try {
                claimed.add(json.readValue(encoded, Intent.class).claimedBy(token));
            } catch (Exception failure) {
                quarantineCorruptIntent(member, token, encoded, failure);
            }
        }
        return claimed;
    }

    boolean cleanupMissingIntent(String member, String claimToken) {
        Long cleaned = redis.execute(CLEANUP_MISSING_INTENT,
                List.of(claimKey(member), PREFIX + "item:" + member, DUE),
                claimToken, member);
        return cleaned != null && cleaned == 1L;
    }

    @Override
    public void acknowledge(Intent intent) {
        intent = owned(intent);
        if (intent == null) return;
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
                String.valueOf(TERMINAL_TTL.toMillis()),
                intent.operationId().toString(), intent.claimToken());
        if (result == null || result < 0) {
            throw new IllegalStateException(
                    "Could not atomically acknowledge settlement intent "
                            + intent.key() + " result=" + result);
        }
    }

    @Override
    public void retry(Intent intent, String error) {
        intent = owned(intent);
        if (intent == null) return;
        Intent next = new Intent(intent.action(), intent.operationId(),
                intent.url(), intent.body(), intent.attempts() + 1,
                intent.trustedHeaders(), intent.claimToken());
        try {
            long cap = Math.min(300, 1L << Math.min(8, next.attempts()));
            long delay = ThreadLocalRandom.current().nextLong(
                    Math.max(1, cap / 2), cap + 1);
            Long result = redis.execute(RETRY_INTENT,
                    List.of(payloadKey(next), DUE, claimKey(next.key())),
                    json.writeValueAsString(next),
                    String.valueOf(System.currentTimeMillis()
                            + Duration.ofSeconds(delay).toMillis()),
                    next.key(), next.claimToken());
            if (result == null || result < 0) {
                throw new IllegalStateException(
                        "Could not atomically reschedule settlement intent "
                                + next.key() + " result=" + result);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not reschedule settlement intent", failure);
        }
    }

    @Override
    public void dead(Intent intent, String error) {
        Intent requested = intent;
        intent = owned(intent);
        if (intent == null) {
            // ACK/DEAD may already have removed the live payload. Retrying DEAD is
            // still useful to repair legacy TTLs on unresolved audit state, but
            // it must not recreate or mutate transport work.
            redis.execute(REPAIR_DEAD_LETTER, List.of(
                    PREFIX + "dead:" + requested.key(),
                    operationKey(requested.operationId())));
            return;
        }
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
                    intent.operationId().toString(), Instant.now().toString(),
                    intent.claimToken());
            if (result == null || result != 1L) {
                throw new IllegalStateException(
                        "Could not atomically dead-letter settlement intent "
                                + intent.key() + " result=" + result);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not dead-letter settlement intent", failure);
        }
    }

    @Override
    public void recordUnknown(UUID operationId, Map<String, Object> details) {
        recordUnknown(operationId, "", details, null);
    }

    @Override
    public boolean recordRecoveredUnknown(
            ClaimedProviderOperation claimed, Intent intent) {
        return recordUnknown(
                claimed.operation().operationId(), claimed.claimToken(),
                intent.body(), intent);
    }

    private boolean recordUnknown(
            UUID operationId, String recoveryClaimToken,
            Map<String, Object> details, Intent recoveredIntent) {
        try {
            String audit = json.writeValueAsString(Map.of(
                    "operationId", operationId,
                    "state", "UNKNOWN_PROVIDER_OUTCOME",
                    "details", details == null ? Map.of() : details));
            String unknownIntentKey = "OUTCOME_UNKNOWN:" + operationId;
            String recoveredPayload = recoveredIntent == null
                    ? "" : json.writeValueAsString(recoveredIntent);
            Long result = redis.execute(RECORD_OUTCOME_UNKNOWN, List.of(
                            operationKey(operationId), PREFIX + "unknown:" + operationId,
                            PROVIDER_DISPATCH_DUE, dispatchKey(operationId),
                            dispatchClaimKey(operationId),
                            PREFIX + "item:" + unknownIntentKey,
                            claimKey(unknownIntentKey), DUE),
                    Instant.now().toString(), audit,
                    String.valueOf(TERMINAL_TTL.toMillis()),
                    operationId.toString(), unknownIntentKey,
                    recoveryClaimToken == null ? "" : recoveryClaimToken,
                    recoveredPayload, String.valueOf(System.currentTimeMillis()));
            if (result != null && result == -4L) {
                return false;
            }
            if (result == null || result < 0) {
                throw new IllegalStateException(
                        "Could not atomically record ambiguous provider outcome "
                                + operationId + " result=" + result);
            }
            return true;
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
                    json.writeValueAsString(dispatching),
                    String.valueOf(dueAt), operationId.toString());
            return result != null && result == 1L;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not persist provider dispatch state", failure);
        }
    }

    @Override
    public List<ClaimedProviderOperation> claimStaleProviderDispatches(int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(
                PROVIDER_DISPATCH_DUE, 0, System.currentTimeMillis(),
                0, Math.max(1, limit));
        if (due == null || due.isEmpty()) return List.of();
        List<ClaimedProviderOperation> result = new ArrayList<>();
        for (String rawId : due) {
            UUID operationId;
            try {
                operationId = UUID.fromString(rawId);
            } catch (IllegalArgumentException invalid) {
                redis.opsForZSet().remove(PROVIDER_DISPATCH_DUE, rawId);
                continue;
            }
            String claimToken = UUID.randomUUID().toString();
            Boolean lease = redis.opsForValue().setIfAbsent(
                    dispatchClaimKey(operationId), claimToken, CLAIM_TTL);
            if (!Boolean.TRUE.equals(lease)) continue;
            try {
                String encoded = redis.opsForValue().get(dispatchKey(operationId));
                if (encoded == null) {
                    quarantineCorruptProviderDispatch(
                            operationId, claimToken, "",
                            new IllegalStateException(
                                    "provider dispatch snapshot is missing"));
                    continue;
                }

                ProviderOperation operation;
                try {
                    operation = json.readValue(encoded, ProviderOperation.class);
                } catch (Exception invalidSnapshot) {
                    quarantineCorruptProviderDispatch(
                            operationId, claimToken, encoded, invalidSnapshot);
                    continue;
                }

                long leaseUntil = System.currentTimeMillis() + CLAIM_TTL.toMillis();
                if (finalizeDispatchClaim(
                        operationId, claimToken, "touch", leaseUntil)) {
                    result.add(new ClaimedProviderOperation(operation, claimToken));
                }
            } catch (Exception failure) {
                // Release only this worker's lease. If it expired and another
                // worker reclaimed, the compare-and-delete is a no-op.
                try {
                    finalizeDispatchClaim(operationId, claimToken, "release", 0);
                } catch (RuntimeException cleanupFailure) {
                    logger.error("Could not release failed provider dispatch claim operationId={}: {}",
                            operationId, cleanupFailure.getMessage());
                }
                logger.error("Could not claim stale provider dispatch operationId={}: {}",
                        operationId, failure.getMessage());
            }
        }
        return result;
    }

    private boolean finalizeDispatchClaim(
            UUID operationId, String claimToken, String action, long dueAt) {
        Long result = redis.execute(FINALIZE_DISPATCH_CLAIM,
                List.of(dispatchClaimKey(operationId), PROVIDER_DISPATCH_DUE),
                claimToken, operationId.toString(), action, String.valueOf(dueAt),
                String.valueOf(CLAIM_TTL.toMillis()));
        if (result != null && result == -1L) {
            throw new IllegalArgumentException(
                    "Unsupported dispatch claim finalization " + action);
        }
        return result != null && result == 1L;
    }

    private void quarantineCorruptProviderDispatch(
            UUID operationId, String claimToken,
            String rawPayload, Exception failure) {
        try {
            String envelope = json.writeValueAsString(Map.of(
                    "operationId", operationId.toString(),
                    "rawPayload", rawPayload == null ? "" : rawPayload,
                    "error", failure.getClass().getSimpleName() + ": "
                            + value(failure.getMessage()),
                    "quarantinedAt", Instant.now().toString()));
            Long result = redis.execute(QUARANTINE_CORRUPT_DISPATCH, List.of(
                            dispatchClaimKey(operationId), PROVIDER_DISPATCH_DUE,
                            dispatchKey(operationId),
                            PREFIX + "dead-corrupt-dispatch:" + operationId,
                            operationKey(operationId)),
                    claimToken, operationId.toString(), envelope,
                    Instant.now().toString());
            if (result != null && result == 1L) {
                logger.error("Quarantined corrupt provider dispatch operationId={}",
                        operationId);
            } else {
                logger.debug("Skipped corrupt provider dispatch quarantine after losing lease "
                                + "operationId={}",
                        operationId);
            }
        } catch (Exception quarantineFailure) {
            throw new IllegalStateException(
                    "Could not quarantine corrupt provider dispatch "
                            + operationId, quarantineFailure);
        }
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

    private void quarantineCorruptIntent(
            String member, String claimToken, String rawPayload, Exception failure) {
        UUID operationId = null;
        int separator = member == null ? -1 : member.lastIndexOf(':');
        if (separator >= 0 && separator + 1 < member.length()) {
            try {
                operationId = UUID.fromString(member.substring(separator + 1));
            } catch (IllegalArgumentException ignored) {
                // The raw member is retained below for manual inspection.
            }
        }
        try {
            String envelope = json.writeValueAsString(Map.of(
                    "intentKey", member == null ? "" : member,
                    "rawPayload", rawPayload == null ? "" : rawPayload,
                    "error", failure.getClass().getSimpleName() + ": "
                            + value(failure.getMessage()),
                    "quarantinedAt", Instant.now().toString()));
            String operationIdValue = operationId == null ? "" : operationId.toString();
            Long result = redis.execute(QUARANTINE_CORRUPT_INTENT, List.of(
                            PREFIX + "dead-corrupt:" + member,
                            PREFIX + "item:" + member,
                            claimKey(member), DUE,
                            operationId == null
                                    ? PREFIX + "invalid-operation:" + member
                                    : operationKey(operationId),
                            operationId == null
                                    ? PREFIX + "invalid-dispatch:" + member
                                    : dispatchKey(operationId),
                            operationId == null
                                    ? PREFIX + "invalid-dispatch-claim:" + member
                                    : dispatchClaimKey(operationId),
                            PROVIDER_DISPATCH_DUE),
                    envelope, member, operationIdValue, Instant.now().toString(),
                    claimToken);
            if (result == null || result != 1L) {
                throw new IllegalStateException(
                        "Could not atomically quarantine corrupt settlement intent " + member);
            }
            logger.error("Quarantined corrupt settlement intent {} without blocking its batch: {}",
                    member, failure.getMessage());
        } catch (RuntimeException quarantineFailure) {
            throw quarantineFailure;
        } catch (Exception quarantineFailure) {
            throw new IllegalStateException(
                    "Could not quarantine corrupt settlement intent " + member,
                    quarantineFailure);
        }
    }

    private Intent owned(Intent intent) {
        if (intent.claimToken() != null && !intent.claimToken().isBlank()) {
            return intent;
        }
        Intent claimed = claim(intent);
        if (claimed == null) {
            Boolean payloadExists = redis.hasKey(payloadKey(intent));
            if (!Boolean.TRUE.equals(payloadExists)) {
                return null;
            }
            throw new IllegalStateException(
                    "Settlement intent lease lost for " + intent.key());
        }
        return claimed;
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
