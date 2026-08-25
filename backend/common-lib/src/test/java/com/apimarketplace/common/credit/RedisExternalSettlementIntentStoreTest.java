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
                .contains("ZREM', KEYS[3]")
                .contains("DEL', KEYS[1], KEYS[2]");

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
            assertThat(store.markProviderDispatching(released)).isTrue();
            assertThat(store.markProviderDispatching(released)).isFalse();
            store.markProviderState(released, "RELEASED");
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
