package com.apimarketplace.common.credit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.lang.reflect.Field;

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

    private static String script(String fieldName) throws Exception {
        Field field = RedisExternalSettlementIntentStore.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((DefaultRedisScript<?>) field.get(null)).getScriptAsString();
    }
}
