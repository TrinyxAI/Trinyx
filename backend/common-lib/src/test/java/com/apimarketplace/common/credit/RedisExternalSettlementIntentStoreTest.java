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
}
