package com.apimarketplace.common.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisExternalSettlementIntentStoreTest {

    @Test
    void providerDispatchTransitionIsAtomicAndRejectsNonReservedOperations() throws Exception {
        Field field = RedisExternalSettlementIntentStore.class
                .getDeclaredField("MARK_DISPATCHING");
        field.setAccessible(true);
        DefaultRedisScript<?> script = (DefaultRedisScript<?>) field.get(null);
        String lua = script.getScriptAsString();

        assertThat(lua)
                .contains("cjson.decode")
                .contains("operation['state'] ~= 'RESERVED'")
                .contains("SET', KEYS[1]")
                .contains("SET', KEYS[2]");

        assertThat(script("RECORD_OUTCOME_UNKNOWN"))
                .contains("owner ~= ARGV[6]")
                .contains("return -4")
                .contains("SET', KEYS[6], ARGV[7]")
                .contains("ZADD', KEYS[8], ARGV[8], ARGV[5]");
    }

    @Test
    void intentPersistenceAndFinalizationAreSingleRedisScripts() throws Exception {
        String persist = script("PERSIST_INTENT");
        assertThat(persist)
                .contains("PERSIST', KEYS[1]")
                .contains("SET', KEYS[1]")
                .contains("ZADD', KEYS[2]");

        String claim = script("CLAIM_INTENT");
        assertThat(claim)
                .contains("'SET', KEYS[2], ARGV[1], 'NX', 'PX'")
                .contains("ZADD', KEYS[3]");

        String retry = script("RETRY_INTENT");
        assertThat(retry)
                .contains("owner ~= ARGV[4]")
                .contains("if not existing")
                .contains("ZREM', KEYS[2]")
                .contains("SET', KEYS[1]")
                .contains("DEL', KEYS[3]");

        String acknowledge = script("ACKNOWLEDGE_INTENT");
        assertThat(acknowledge)
                .contains("owner ~= ARGV[6]")
                .contains("operation['state'] = ARGV[2]")
                .contains("current == 'SETTLEMENT_FAILED'")
                .contains("ARGV[2] ~= 'COMMITTED'")
                .contains("ARGV[2] ~= 'RELEASED'")
                .contains("ARGV[2] == 'OUTCOME_UNKNOWN'")
                .contains("return 2")
                .contains("SET', KEYS[4]")
                .contains("ZREM', KEYS[3]")
                .contains("DEL', KEYS[1], KEYS[2]");

        String unknown = script("RECORD_OUTCOME_UNKNOWN");
        assertThat(unknown)
                .contains("current ~= 'DISPATCHING'")
                .contains("operation['state'] = 'OUTCOME_UNKNOWN'")
                .contains("PERSIST', KEYS[1]")
                .contains("SET', KEYS[1]")
                .contains("ZREM', KEYS[8]")
                .contains("DEL', KEYS[6], KEYS[7]")
                .contains("ZREM', KEYS[3]")
                .contains("DEL', KEYS[4], KEYS[5]");

        String dead = script("DEAD_LETTER_INTENT");
        assertThat(dead)
                .contains("owner ~= ARGV[6]")
                .contains("operation['state'] = 'SETTLEMENT_FAILED'")
                .contains("SET', KEYS[5]")
                .contains("PERSIST', KEYS[5]")
                .contains("if unresolved")
                .contains("SET', KEYS[1]")
                .contains("PSETEX', KEYS[1]")
                .contains("ZREM', KEYS[4]");

        String repairDead = script("REPAIR_DEAD_LETTER");
        assertThat(repairDead)
                .contains("operation['state'] ~= 'SETTLEMENT_FAILED'")
                .contains("PERSIST', KEYS[2]")
                .contains("PERSIST', KEYS[1]");

        String quarantine = script("QUARANTINE_CORRUPT_INTENT");
        assertThat(quarantine)
                .contains("operation['state'] = 'SETTLEMENT_FAILED'")
                .contains("SET', KEYS[1]")
                .contains("ZREM', KEYS[4]")
                .contains("DEL', KEYS[2], KEYS[3]");
    }

    @Test
    void realRedisEnforcesDispatchAndSettlementTransitions() {
        String host = System.getenv("TRINYX_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "real Redis is enabled by Cloud CI");

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try {
            Set<String> existing = redis.keys("trinyx:billing:producer-outbox:*");
            if (existing != null && !existing.isEmpty()) redis.delete(existing);

            RedisExternalSettlementIntentStore store =
                    new RedisExternalSettlementIntentStore(
                            redis, new ObjectMapper().findAndRegisterModules());

            UUID released = UUID.randomUUID();
            store.registerProviderOperation(operation(released));
            store.registerProviderOperation(operation(released));
            assertThat(store.providerOperation(released).requestHash())
                    .isEqualTo("a".repeat(64));
            ExternalSettlementIntentStore.ProviderOperation equivocation =
                    new ExternalSettlementIntentStore.ProviderOperation(
                            released, "b".repeat(64), "openai", "gpt-test",
                            "http://auth/outcome-unknown", Map.of(),
                            "RESERVED", Instant.now());
            assertThatThrownBy(() -> store.registerProviderOperation(equivocation))
                    .hasMessageContaining("provider operation equivocation");
            assertThat(store.providerOperation(released).requestHash())
                    .isEqualTo("a".repeat(64));
            assertThat(store.markProviderDispatching(released)).isTrue();
            assertThat(store.markProviderDispatching(released)).isFalse();
            ExternalSettlementIntentStore.Intent releasedIntent =
                    intent("RELEASE", released, "http://auth/release");
            store.persist(releasedIntent);
            store.acknowledge(releasedIntent);
            assertThat(store.markProviderDispatching(released))
                    .as("a released hold must never be dispatched")
                    .isFalse();

            UUID committed = UUID.randomUUID();
            store.registerProviderOperation(operation(committed));
            assertThat(store.markProviderDispatching(committed)).isTrue();
            ExternalSettlementIntentStore.Intent intent =
                    new ExternalSettlementIntentStore.Intent(
                            "COMMIT_LLM", committed, "http://auth/commit",
                            Map.of("requestHash", "a".repeat(64)), 0,
                            Map.of("X-Principal-ID", UUID.randomUUID().toString()));
            store.persist(intent);
            List<ExternalSettlementIntentStore.Intent> claimed = store.claimDue(10);
            assertThat(claimed).extracting(ExternalSettlementIntentStore.Intent::key)
                    .contains(intent.key());
            ExternalSettlementIntentStore.Intent claimedCommit = claimed.stream()
                    .filter(value -> value.key().equals(intent.key()))
                    .findFirst().orElseThrow();
            store.acknowledge(claimedCommit);

            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + intent.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", intent.key())).isNull();
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:operation:" + committed))
                    .contains("\"state\":\"COMMITTED\"");
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void realRedisAllowsAuthoritativeTerminalToSupersedeLocalSettlementFailure() {
        String host = System.getenv("TRINYX_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "real Redis is enabled by Cloud CI");

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try {
            Set<String> existing = redis.keys("trinyx:billing:producer-outbox:*");
            if (existing != null && !existing.isEmpty()) redis.delete(existing);

            RedisExternalSettlementIntentStore store =
                    new RedisExternalSettlementIntentStore(
                            redis, new ObjectMapper().findAndRegisterModules());

            UUID committed = UUID.randomUUID();
            store.registerProviderOperation(operation(committed));
            assertThat(store.markProviderDispatching(committed)).isTrue();
            ExternalSettlementIntentStore.Intent staleUnknown =
                    intent("OUTCOME_UNKNOWN", committed, "http://auth/outcome-unknown");
            ExternalSettlementIntentStore.Intent commit =
                    intent("COMMIT_LLM", committed, "http://auth/commit");
            store.persist(staleUnknown);
            store.persist(commit);
            store.dead(staleUnknown, "authority already committed");

            assertThat(store.providerOperation(committed).state())
                    .isEqualTo("SETTLEMENT_FAILED");
            store.recordUnknown(committed, staleUnknown.body());
            assertThat(store.providerOperation(committed).state())
                    .isEqualTo("SETTLEMENT_FAILED");

            ExternalSettlementIntentStore.Intent laterUnknown =
                    intent("OUTCOME_UNKNOWN", committed, "http://auth/outcome-unknown");
            store.persist(laterUnknown);
            store.acknowledge(laterUnknown);
            assertThat(store.providerOperation(committed).state())
                    .isEqualTo("SETTLEMENT_FAILED");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + laterUnknown.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", laterUnknown.key())).isNull();

            store.acknowledge(commit);
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + commit.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", commit.key())).isNull();

            ExternalSettlementIntentStore.Intent conflictingRelease =
                    intent("RELEASE", committed, "http://auth/release");
            store.persist(conflictingRelease);
            ExternalSettlementIntentStore.Intent ownedConflictingRelease =
                    store.claim(conflictingRelease);
            assertThat(ownedConflictingRelease).isNotNull();
            assertThatThrownBy(() -> store.acknowledge(ownedConflictingRelease))
                    .hasMessageContaining("result=-3");
            store.dead(ownedConflictingRelease, "COMMITTED is authoritative");

            UUID released = UUID.randomUUID();
            store.registerProviderOperation(operation(released));
            assertThat(store.markProviderDispatching(released)).isTrue();
            ExternalSettlementIntentStore.Intent releaseUnknown =
                    intent("OUTCOME_UNKNOWN", released, "http://auth/outcome-unknown");
            ExternalSettlementIntentStore.Intent release =
                    intent("RELEASE", released, "http://auth/release");
            store.persist(releaseUnknown);
            store.persist(release);
            store.dead(releaseUnknown, "authority already released");

            assertThat(store.providerOperation(released).state())
                    .isEqualTo("SETTLEMENT_FAILED");

            ExternalSettlementIntentStore.Intent laterReleaseUnknown =
                    intent("OUTCOME_UNKNOWN", released, "http://auth/outcome-unknown");
            store.persist(laterReleaseUnknown);
            store.acknowledge(laterReleaseUnknown);
            assertThat(store.providerOperation(released).state())
                    .isEqualTo("SETTLEMENT_FAILED");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:"
                            + laterReleaseUnknown.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due",
                    laterReleaseUnknown.key())).isNull();

            store.acknowledge(release);
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + release.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", release.key())).isNull();

            ExternalSettlementIntentStore.Intent conflictingCommit =
                    intent("COMMIT_LLM", released, "http://auth/commit");
            store.persist(conflictingCommit);
            ExternalSettlementIntentStore.Intent ownedConflictingCommit =
                    store.claim(conflictingCommit);
            assertThat(ownedConflictingCommit).isNotNull();
            assertThatThrownBy(() -> store.acknowledge(ownedConflictingCommit))
                    .hasMessageContaining("result=-3");
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
            store.dead(ownedConflictingCommit, "RELEASED is authoritative");
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void unresolvedJournalRemainsPersistentAndStaleRetryCannotResurrectIt() {
        String host = System.getenv("TRINYX_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "real Redis is enabled by Cloud CI");

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try {
            Set<String> existing = redis.keys("trinyx:billing:producer-outbox:*");
            if (existing != null && !existing.isEmpty()) redis.delete(existing);

            RedisExternalSettlementIntentStore store =
                    new RedisExternalSettlementIntentStore(
                            redis, new ObjectMapper().findAndRegisterModules());

            UUID committed = UUID.randomUUID();
            String committedOperationKey =
                    "trinyx:billing:producer-outbox:operation:" + committed;
            String committedDispatchKey =
                    "trinyx:billing:producer-outbox:dispatch:" + committed;
            store.registerProviderOperation(operation(committed));
            assertThat(redis.getExpire(committedOperationKey)).isPositive();
            assertThat(store.markProviderDispatching(committed)).isTrue();
            assertThat(redis.getExpire(committedOperationKey)).isEqualTo(-1L);
            assertThat(redis.getExpire(committedDispatchKey)).isEqualTo(-1L);

            ExternalSettlementIntentStore.Intent commit =
                    intent("COMMIT_LLM", committed, "http://auth/commit");
            String commitPayloadKey =
                    "trinyx:billing:producer-outbox:item:" + commit.key();
            store.persist(commit);
            assertThat(redis.getExpire(commitPayloadKey)).isEqualTo(-1L);

            ExternalSettlementIntentStore.Intent claimed =
                    store.claimDue(1).get(0);
            store.retry(claimed, "authority unavailable");
            assertThat(redis.getExpire(commitPayloadKey)).isEqualTo(-1L);
            redis.opsForZSet().add(
                    "trinyx:billing:producer-outbox:due",
                    commit.key(), System.currentTimeMillis() - 1);
            ExternalSettlementIntentStore.Intent retried =
                    store.claimDue(1).get(0);
            assertThat(retried.attempts()).isEqualTo(1);

            store.acknowledge(retried);
            assertThat(redis.opsForValue().get(commitPayloadKey)).isNull();
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");
            assertThat(redis.getExpire(committedOperationKey))
                    .isPositive()
                    .isLessThanOrEqualTo(604800L);

            store.retry(retried, "stale worker response");
            assertThat(redis.opsForValue().get(commitPayloadKey)).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", commit.key())).isNull();

            UUID released = UUID.randomUUID();
            String releasedOperationKey =
                    "trinyx:billing:producer-outbox:operation:" + released;
            store.registerProviderOperation(operation(released));
            assertThat(store.markProviderDispatching(released)).isTrue();
            ExternalSettlementIntentStore.Intent release =
                    intent("RELEASE", released, "http://auth/release");
            String releasePayloadKey =
                    "trinyx:billing:producer-outbox:item:" + release.key();
            store.persist(release);
            assertThat(redis.getExpire(releasePayloadKey)).isEqualTo(-1L);
            store.acknowledge(release);
            assertThat(redis.opsForValue().get(releasePayloadKey)).isNull();
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
            assertThat(redis.getExpire(releasedOperationKey))
                    .isPositive()
                    .isLessThanOrEqualTo(604800L);

            UUID unknown = UUID.randomUUID();
            store.registerProviderOperation(operation(unknown));
            assertThat(store.markProviderDispatching(unknown)).isTrue();
            ExternalSettlementIntentStore.Intent unknownIntent =
                    intent("OUTCOME_UNKNOWN", unknown, "http://auth/outcome-unknown");
            String unknownPayloadKey =
                    "trinyx:billing:producer-outbox:item:" + unknownIntent.key();
            store.persist(unknownIntent);
            assertThat(redis.getExpire(unknownPayloadKey)).isEqualTo(-1L);
            store.recordUnknown(unknown, unknownIntent.body());
            store.acknowledge(unknownIntent);
            assertThat(redis.opsForValue().get(unknownPayloadKey)).isNull();
            assertThat(redis.getExpire(
                    "trinyx:billing:producer-outbox:operation:" + unknown))
                    .isEqualTo(-1L);

            UUID failed = UUID.randomUUID();
            String failedOperationKey =
                    "trinyx:billing:producer-outbox:operation:" + failed;
            store.registerProviderOperation(operation(failed));
            assertThat(store.markProviderDispatching(failed)).isTrue();
            ExternalSettlementIntentStore.Intent failedIntent =
                    intent("COMMIT_LLM", failed, "http://auth/commit");
            String failedPayloadKey =
                    "trinyx:billing:producer-outbox:item:" + failedIntent.key();
            store.persist(failedIntent);
            assertThat(redis.getExpire(failedPayloadKey)).isEqualTo(-1L);
            store.dead(failedIntent, "permanent authority rejection");
            assertThat(redis.opsForValue().get(failedPayloadKey)).isNull();
            assertThat(store.providerOperation(failed).state())
                    .isEqualTo("SETTLEMENT_FAILED");
            assertThat(redis.getExpire(failedOperationKey)).isEqualTo(-1L);
            String failedDeadKey =
                    "trinyx:billing:producer-outbox:dead:" + failedIntent.key();
            assertThat(redis.getExpire(failedDeadKey)).isEqualTo(-1L);
            assertThat(redis.opsForValue().get(failedDeadKey))
                    .contains("permanent authority rejection", failedIntent.operationId().toString());

            // Repeating DEAD repairs legacy producer failures that still carry
            // the former terminal TTL.
            redis.expire(failedOperationKey, java.time.Duration.ofDays(7));
            assertThat(redis.getExpire(failedOperationKey)).isPositive();
            store.dead(failedIntent, "idempotent dead-letter retry");
            assertThat(redis.getExpire(failedOperationKey)).isEqualTo(-1L);
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void unknownOperationRemainsPersistentUntilAuthoritativeTerminal() {
        String host = System.getenv("TRINYX_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "real Redis is enabled by Cloud CI");

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try {
            Set<String> existing = redis.keys("trinyx:billing:producer-outbox:*");
            if (existing != null && !existing.isEmpty()) redis.delete(existing);

            RedisExternalSettlementIntentStore store =
                    new RedisExternalSettlementIntentStore(
                            redis, new ObjectMapper().findAndRegisterModules());

            UUID committed = UUID.randomUUID();
            String committedOperationKey =
                    "trinyx:billing:producer-outbox:operation:" + committed;
            store.registerProviderOperation(operation(committed));
            assertThat(store.markProviderDispatching(committed)).isTrue();
            ExternalSettlementIntentStore.Intent recordedUnknown =
                    intent("OUTCOME_UNKNOWN", committed, "http://auth/outcome-unknown");
            store.persist(recordedUnknown);
            store.recordUnknown(committed, recordedUnknown.body());

            assertThat(store.providerOperation(committed).state())
                    .isEqualTo("OUTCOME_UNKNOWN");
            assertThat(redis.getExpire(committedOperationKey)).isEqualTo(-1L);

            store.acknowledge(recordedUnknown);
            assertThat(redis.getExpire(committedOperationKey)).isEqualTo(-1L);

            ExternalSettlementIntentStore.Intent commit =
                    intent("COMMIT_LLM", committed, "http://auth/commit");
            store.persist(commit);
            store.acknowledge(commit);
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");
            assertThat(redis.getExpire(committedOperationKey))
                    .isPositive()
                    .isLessThanOrEqualTo(604800L);

            UUID released = UUID.randomUUID();
            String releasedOperationKey =
                    "trinyx:billing:producer-outbox:operation:" + released;
            String releasedAuditKey =
                    "trinyx:billing:producer-outbox:unknown:" + released;
            store.registerProviderOperation(operation(released));
            assertThat(store.markProviderDispatching(released)).isTrue();
            ExternalSettlementIntentStore.Intent acknowledgedUnknown =
                    intent("OUTCOME_UNKNOWN", released, "http://auth/outcome-unknown");
            store.persist(acknowledgedUnknown);
            store.acknowledge(acknowledgedUnknown);

            assertThat(store.providerOperation(released).state())
                    .isEqualTo("OUTCOME_UNKNOWN");
            assertThat(redis.getExpire(releasedOperationKey)).isEqualTo(-1L);
            assertThat(redis.opsForValue().get(releasedAuditKey)).isNull();

            store.recordUnknown(released, acknowledgedUnknown.body());
            assertThat(redis.getExpire(releasedOperationKey)).isEqualTo(-1L);
            assertThat(redis.opsForValue().get(releasedAuditKey))
                    .contains("UNKNOWN_PROVIDER_OUTCOME");

            ExternalSettlementIntentStore.Intent release =
                    intent("RELEASE", released, "http://auth/release");
            store.persist(release);
            store.acknowledge(release);
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
            assertThat(redis.getExpire(releasedOperationKey))
                    .isPositive()
                    .isLessThanOrEqualTo(604800L);
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void staleRecoveryCannotRegressAnAuthoritativeTerminal() {
        String host = System.getenv("TRINYX_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "real Redis is enabled by Cloud CI");

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try {
            Set<String> existing = redis.keys("trinyx:billing:producer-outbox:*");
            if (existing != null && !existing.isEmpty()) redis.delete(existing);

            RedisExternalSettlementIntentStore store =
                    new RedisExternalSettlementIntentStore(
                            redis, new ObjectMapper().findAndRegisterModules());

            UUID committed = UUID.randomUUID();
            store.registerProviderOperation(operation(committed));
            assertThat(store.markProviderDispatching(committed)).isTrue();
            redis.opsForZSet().add(
                    "trinyx:billing:producer-outbox:provider-dispatch-due",
                    committed.toString(), System.currentTimeMillis() - 1);
            ExternalSettlementIntentStore.ClaimedProviderOperation staleCommit =
                    store.claimStaleProviderDispatches(1).get(0);
            assertThat(staleCommit.operation().state()).isEqualTo("DISPATCHING");

            ExternalSettlementIntentStore.Intent commit =
                    intent("COMMIT_LLM", committed, "http://auth/commit");
            store.persist(commit);
            store.acknowledge(commit);
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");

            ExternalSettlementIntentStore.Intent delayedCommitUnknown =
                    intent("OUTCOME_UNKNOWN", committed, "http://auth/outcome-unknown");
            assertThat(store.recordRecoveredUnknown(
                    staleCommit, delayedCommitUnknown)).isFalse();
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");
            store.dead(delayedCommitUnknown, "authority already committed");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:"
                            + delayedCommitUnknown.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due",
                    delayedCommitUnknown.key())).isNull();
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");

            UUID released = UUID.randomUUID();
            store.registerProviderOperation(operation(released));
            assertThat(store.markProviderDispatching(released)).isTrue();
            redis.opsForZSet().add(
                    "trinyx:billing:producer-outbox:provider-dispatch-due",
                    released.toString(), System.currentTimeMillis() - 1);
            ExternalSettlementIntentStore.ClaimedProviderOperation staleRelease =
                    store.claimStaleProviderDispatches(1).get(0);
            assertThat(staleRelease.operation().state()).isEqualTo("DISPATCHING");

            ExternalSettlementIntentStore.Intent release =
                    intent("RELEASE", released, "http://auth/release");
            store.persist(release);
            store.acknowledge(release);
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");

            ExternalSettlementIntentStore.Intent delayedReleaseUnknown =
                    intent("OUTCOME_UNKNOWN", released, "http://auth/outcome-unknown");
            assertThat(store.recordRecoveredUnknown(
                    staleRelease, delayedReleaseUnknown)).isFalse();
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
            store.dead(delayedReleaseUnknown, "authority already released");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:"
                            + delayedReleaseUnknown.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due",
                    delayedReleaseUnknown.key())).isNull();
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void expiredDispatchRecoveryLeaseFencesStaleWorker() {
        String host = System.getenv("TRINYX_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "real Redis is enabled by Cloud CI");

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try {
            Set<String> existing = redis.keys("trinyx:billing:producer-outbox:*");
            if (existing != null && !existing.isEmpty()) redis.delete(existing);

            RedisExternalSettlementIntentStore store =
                    new RedisExternalSettlementIntentStore(
                            redis, new ObjectMapper().findAndRegisterModules());
            UUID operationId = UUID.randomUUID();
            store.registerProviderOperation(operation(operationId));
            assertThat(store.markProviderDispatching(operationId)).isTrue();

            String dueKey =
                    "trinyx:billing:producer-outbox:provider-dispatch-due";
            String claimKey =
                    "trinyx:billing:producer-outbox:dispatch-claim:"
                            + operationId;
            redis.opsForZSet().add(dueKey, operationId.toString(),
                    System.currentTimeMillis() - 1);
            ExternalSettlementIntentStore.ClaimedProviderOperation workerA =
                    store.claimStaleProviderDispatches(1).get(0);

            // Deterministically model lease A expiry, then B reclaim.
            redis.delete(claimKey);
            redis.opsForZSet().add(dueKey, operationId.toString(),
                    System.currentTimeMillis() - 1);
            ExternalSettlementIntentStore.ClaimedProviderOperation workerB =
                    store.claimStaleProviderDispatches(1).get(0);
            assertThat(workerB.claimToken()).isNotEqualTo(workerA.claimToken());
            assertThat(redis.opsForValue().get(claimKey))
                    .isEqualTo(workerB.claimToken());

            ExternalSettlementIntentStore.Intent unknown =
                    intent("OUTCOME_UNKNOWN", operationId,
                            "http://auth/outcome-unknown");
            assertThat(store.recordRecoveredUnknown(workerA, unknown))
                    .isFalse();
            assertThat(store.providerOperation(operationId).state())
                    .isEqualTo("DISPATCHING");
            assertThat(redis.opsForValue().get(claimKey))
                    .isEqualTo(workerB.claimToken());
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + unknown.key()))
                    .isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", unknown.key()))
                    .isNull();

            assertThat(store.recordRecoveredUnknown(workerB, unknown))
                    .isTrue();
            assertThat(store.providerOperation(operationId).state())
                    .isEqualTo("OUTCOME_UNKNOWN");
            assertThat(redis.opsForValue().get(claimKey)).isNull();
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + unknown.key()))
                    .isNotNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", unknown.key()))
                    .isNotNull();
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void corruptIntentIsPersistentlyQuarantinedWithoutBlockingValidBatchMembers() {
        String host = System.getenv("TRINYX_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "real Redis is enabled by Cloud CI");

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try {
            Set<String> existing = redis.keys("trinyx:billing:producer-outbox:*");
            if (existing != null && !existing.isEmpty()) redis.delete(existing);

            RedisExternalSettlementIntentStore store =
                    new RedisExternalSettlementIntentStore(
                            redis, new ObjectMapper().findAndRegisterModules());

            UUID corruptOperation = UUID.randomUUID();
            store.registerProviderOperation(operation(corruptOperation));
            assertThat(store.markProviderDispatching(corruptOperation)).isTrue();
            String corruptMember = "COMMIT_LLM:" + corruptOperation;
            String corruptPayloadKey =
                    "trinyx:billing:producer-outbox:item:" + corruptMember;
            redis.opsForValue().set(corruptPayloadKey, "{not-json");
            redis.opsForZSet().add(
                    "trinyx:billing:producer-outbox:due",
                    corruptMember, System.currentTimeMillis() - 2);

            UUID validOperation = UUID.randomUUID();
            store.registerProviderOperation(operation(validOperation));
            assertThat(store.markProviderDispatching(validOperation)).isTrue();
            ExternalSettlementIntentStore.Intent valid =
                    intent("COMMIT_LLM", validOperation, "http://auth/commit");
            store.persist(valid);

            List<ExternalSettlementIntentStore.Intent> claimed = store.claimDue(10);

            assertThat(claimed)
                    .extracting(ExternalSettlementIntentStore.Intent::key)
                    .contains(valid.key())
                    .doesNotContain(corruptMember);
            assertThat(redis.opsForValue().get(corruptPayloadKey)).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", corruptMember)).isNull();
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:claim:" + corruptMember)).isNull();
            String quarantineKey =
                    "trinyx:billing:producer-outbox:dead-corrupt:" + corruptMember;
            assertThat(redis.getExpire(quarantineKey)).isEqualTo(-1L);
            assertThat(redis.opsForValue().get(quarantineKey))
                    .contains("{not-json", corruptMember);
            assertThat(store.providerOperation(corruptOperation).state())
                    .isEqualTo("SETTLEMENT_FAILED");
            assertThat(redis.getExpire(
                    "trinyx:billing:producer-outbox:operation:" + corruptOperation))
                    .isEqualTo(-1L);
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void expiredRedisLeaseFencesStaleWorkerFromAckRetryAndDead() {
        String host = System.getenv("TRINYX_TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "real Redis is enabled by Cloud CI");

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try {
            Set<String> existing = redis.keys("trinyx:billing:producer-outbox:*");
            if (existing != null && !existing.isEmpty()) redis.delete(existing);

            RedisExternalSettlementIntentStore store =
                    new RedisExternalSettlementIntentStore(
                            redis, new ObjectMapper().findAndRegisterModules());
            UUID operationId = UUID.randomUUID();
            store.registerProviderOperation(operation(operationId));
            assertThat(store.markProviderDispatching(operationId)).isTrue();
            ExternalSettlementIntentStore.Intent intent =
                    intent("COMMIT_LLM", operationId, "http://auth/commit");
            store.persist(intent);

            ExternalSettlementIntentStore.Intent workerA = store.claim(intent);
            assertThat(workerA).isNotNull();
            redis.delete("trinyx:billing:producer-outbox:claim:" + intent.key());
            ExternalSettlementIntentStore.Intent workerB = store.claim(intent);
            assertThat(workerB).isNotNull();
            assertThat(workerB.claimToken()).isNotEqualTo(workerA.claimToken());

            assertThatThrownBy(() -> store.acknowledge(workerA))
                    .hasMessageContaining("result=-4");
            assertThatThrownBy(() -> store.retry(workerA, "stale"))
                    .hasMessageContaining("result=-4");
            assertThatThrownBy(() -> store.dead(workerA, "stale"))
                    .hasMessageContaining("result=-4");
            assertThat(store.providerOperation(operationId).state())
                    .isEqualTo("DISPATCHING");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + intent.key())).isNotNull();

            store.acknowledge(workerB);
            assertThat(store.providerOperation(operationId).state())
                    .isEqualTo("COMMITTED");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + intent.key())).isNull();
        } finally {
            connectionFactory.destroy();
        }
    }

    private static ExternalSettlementIntentStore.Intent intent(
            String action, UUID operationId, String targetUrl) {
        return new ExternalSettlementIntentStore.Intent(
                action, operationId, targetUrl,
                Map.of(
                        "requestHash", "a".repeat(64),
                        "provider", "openai",
                        "model", "gpt-test"),
                0,
                Map.of("X-Principal-ID", UUID.randomUUID().toString()));
    }

    private static ExternalSettlementIntentStore.ProviderOperation operation(UUID operationId) {
        return new ExternalSettlementIntentStore.ProviderOperation(
                operationId, "a".repeat(64), "openai", "gpt-test",
                "http://auth/outcome-unknown", Map.of(), "RESERVED", Instant.now());
    }

    private static String script(String fieldName) throws Exception {
        Field field = RedisExternalSettlementIntentStore.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((DefaultRedisScript<?>) field.get(null)).getScriptAsString();
    }
}
