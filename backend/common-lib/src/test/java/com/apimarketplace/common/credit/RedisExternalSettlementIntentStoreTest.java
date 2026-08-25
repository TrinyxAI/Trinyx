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
                .contains("PSETEX', KEYS[1]")
                .contains("PSETEX', KEYS[2]");
    }

    @Test
    void intentPersistenceAndFinalizationAreSingleRedisScripts() throws Exception {
        String persist = script("PERSIST_INTENT");
        assertThat(persist)
                .contains("PSETEX', KEYS[1]")
                .contains("ZADD', KEYS[2]");

        String acknowledge = script("ACKNOWLEDGE_INTENT");
        assertThat(acknowledge)
                .contains("operation['state'] = ARGV[2]")
                .contains("current == 'SETTLEMENT_FAILED'")
                .contains("ARGV[2] ~= 'COMMITTED'")
                .contains("ARGV[2] ~= 'RELEASED'")
                .contains("ZREM', KEYS[3]")
                .contains("DEL', KEYS[1], KEYS[2]");

        String unknown = script("RECORD_OUTCOME_UNKNOWN");
        assertThat(unknown)
                .contains("current ~= 'DISPATCHING'")
                .contains("operation['state'] = 'OUTCOME_UNKNOWN'")
                .contains("PSETEX', KEYS[1]")
                .contains("ZREM', KEYS[3]")
                .contains("DEL', KEYS[4], KEYS[5]");

        String dead = script("DEAD_LETTER_INTENT");
        assertThat(dead)
                .contains("operation['state'] = 'SETTLEMENT_FAILED'")
                .contains("PSETEX', KEYS[1]")
                .contains("ZREM', KEYS[4]");
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
            store.acknowledge(intent);

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
            assertThatThrownBy(() -> store.acknowledge(laterUnknown))
                    .hasMessageContaining("result=-3");
            store.dead(laterUnknown, "non-terminal evidence cannot recover a dead letter");

            store.acknowledge(commit);
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + commit.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", commit.key())).isNull();

            ExternalSettlementIntentStore.Intent conflictingRelease =
                    intent("RELEASE", committed, "http://auth/release");
            store.persist(conflictingRelease);
            assertThatThrownBy(() -> store.acknowledge(conflictingRelease))
                    .hasMessageContaining("result=-3");
            store.dead(conflictingRelease, "COMMITTED is authoritative");

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

            store.acknowledge(release);
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
            assertThat(redis.opsForValue().get(
                    "trinyx:billing:producer-outbox:item:" + release.key())).isNull();
            assertThat(redis.opsForZSet().score(
                    "trinyx:billing:producer-outbox:due", release.key())).isNull();

            ExternalSettlementIntentStore.Intent conflictingCommit =
                    intent("COMMIT_LLM", released, "http://auth/commit");
            store.persist(conflictingCommit);
            assertThatThrownBy(() -> store.acknowledge(conflictingCommit))
                    .hasMessageContaining("result=-3");
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
            store.dead(conflictingCommit, "RELEASED is authoritative");
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
            ExternalSettlementIntentStore.ProviderOperation staleCommit =
                    store.claimStaleProviderDispatches(1).get(0);
            assertThat(staleCommit.state()).isEqualTo("DISPATCHING");

            ExternalSettlementIntentStore.Intent commit =
                    intent("COMMIT_LLM", committed, "http://auth/commit");
            store.persist(commit);
            store.acknowledge(commit);
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");

            ExternalSettlementIntentStore.Intent delayedCommitUnknown =
                    intent("OUTCOME_UNKNOWN", committed, "http://auth/outcome-unknown");
            store.persist(delayedCommitUnknown);
            store.recordUnknown(staleCommit.operationId(), delayedCommitUnknown.body());
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");
            store.dead(delayedCommitUnknown, "authority already committed");
            assertThat(store.providerOperation(committed).state()).isEqualTo("COMMITTED");

            UUID released = UUID.randomUUID();
            store.registerProviderOperation(operation(released));
            assertThat(store.markProviderDispatching(released)).isTrue();
            redis.opsForZSet().add(
                    "trinyx:billing:producer-outbox:provider-dispatch-due",
                    released.toString(), System.currentTimeMillis() - 1);
            ExternalSettlementIntentStore.ProviderOperation staleRelease =
                    store.claimStaleProviderDispatches(1).get(0);
            assertThat(staleRelease.state()).isEqualTo("DISPATCHING");

            ExternalSettlementIntentStore.Intent release =
                    intent("RELEASE", released, "http://auth/release");
            store.persist(release);
            store.acknowledge(release);
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");

            ExternalSettlementIntentStore.Intent delayedReleaseUnknown =
                    intent("OUTCOME_UNKNOWN", released, "http://auth/outcome-unknown");
            store.persist(delayedReleaseUnknown);
            store.recordUnknown(staleRelease.operationId(), delayedReleaseUnknown.body());
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
            store.dead(delayedReleaseUnknown, "authority already released");
            assertThat(store.providerOperation(released).state()).isEqualTo("RELEASED");
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
