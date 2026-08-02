package com.apimarketplace.orchestrator.execution.v2.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisLoopIterationStore} - the cross-replica loop counter introduced
 * after the 2026-07-30 prod overrun (maxIterations=60 loop ran 73+ body iterations because the
 * counter lived in a per-JVM cache while signal resumes fan out across replicas).
 *
 * <p>The class's value IS its failure semantics, so the fail-open paths are tested explicitly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisLoopIterationStore")
class RedisLoopIterationStoreTest {

    private static final String RUN = "run_<id>";
    private static final int EPOCH = 1;
    private static final String EDGE = "core:statut->core:attendre_le_rendu:iterate";
    private static final String ITER_KEY = "loop:iter:" + RUN + ":" + EPOCH + ":" + EDGE;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisLoopIterationStore store;

    @BeforeEach
    void setUp() {
        store = new RedisLoopIterationStore(redisTemplate, Duration.ofHours(24));
    }

    @Nested
    @DisplayName("wiring")
    class Wiring {

        /**
         * Regression guard for the defect that made the first version of this fix a silent no-op:
         * a user {@code @Component} scan is evaluated BEFORE auto-configured {@code @Bean} methods
         * are registered, so {@code @ConditionalOnBean} on an auto-configured type never matches -
         * the bean is never created, {@code BackEdgeHandler} injects null, and every cross-replica
         * guard short-circuits with no error and no log. Same trap already documented in this repo
         * on {@code OrchestratorInstanceRegistrar}.
         */
        @Test
        @DisplayName("regression: the bean must be unconditional (a @ConditionalOnBean here never matches)")
        void beanMustNotBeConditionalOnBean() {
            assertNotNull(RedisLoopIterationStore.class.getAnnotation(Component.class),
                "the store must be component-scanned");
            assertNull(RedisLoopIterationStore.class.getAnnotation(ConditionalOnBean.class),
                "@ConditionalOnBean on a @Component is evaluated before auto-config beans exist, "
                + "so it silently never matches and the whole cross-replica fix becomes inert");
        }

        /**
         * Uses the REAL Spring constructor with an empty provider. A per-parameter
         * {@code @Autowired(required = false)} would have been silently ignored (Spring reads
         * optionality from the constructor element, and only a {@code @Nullable}-named annotation
         * marks a parameter optional), so an absent factory would have thrown instead of
         * degrading - this pins that the no-Redis path is actually reachable.
         */
        @Test
        @DisplayName("No Redis wired: disabled, and every method degrades without throwing")
        void degradesGracefullyWithoutRedis() {
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.connection.RedisConnectionFactory> empty =
                mock(org.springframework.beans.factory.ObjectProvider.class);
            when(empty.getIfAvailable()).thenReturn(null);
            RedisLoopIterationStore noRedis = new RedisLoopIterationStore(empty);

            assertFalse(noRedis.isEnabled());
            assertNull(noRedis.readProgress(RUN, EPOCH, EDGE));
            assertTrue(noRedis.tryClaim(RUN, EPOCH, EDGE, 3),
                "with no shared store the caller must proceed, not stall");
            assertDoesNotThrow(() -> noRedis.recordIteration(RUN, EPOCH, EDGE, 3));
            assertDoesNotThrow(() -> noRedis.markTerminated(RUN, EPOCH, EDGE, 3));
        }
    }

    @Nested
    @DisplayName("readProgress()")
    class ReadProgress {

        @Test
        @DisplayName("Reads iteration and terminated flag")
        void readsIterationAndTerminatedFlag() {
            when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOps);
            when(hashOps.multiGet(eq(ITER_KEY), anyList())).thenReturn(List.of("59", "1"));

            RedisLoopIterationStore.LoopProgress progress = store.readProgress(RUN, EPOCH, EDGE);

            assertNotNull(progress);
            assertEquals(59, progress.iteration());
            assertTrue(progress.terminated());
        }

        @Test
        @DisplayName("A missing terminated field reads as not-terminated")
        void missingTerminatedFieldIsFalse() {
            when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOps);
            when(hashOps.multiGet(eq(ITER_KEY), anyList())).thenReturn(Arrays.asList("7", null));

            RedisLoopIterationStore.LoopProgress progress = store.readProgress(RUN, EPOCH, EDGE);

            assertNotNull(progress);
            assertEquals(7, progress.iteration());
            assertFalse(progress.terminated());
        }

        @Test
        @DisplayName("Nothing recorded yet returns null (no shared opinion)")
        void returnsNullWhenNothingRecorded() {
            when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOps);
            when(hashOps.multiGet(eq(ITER_KEY), anyList())).thenReturn(Arrays.asList(null, null));

            assertNull(store.readProgress(RUN, EPOCH, EDGE));
        }

        @Test
        @DisplayName("fail-open: a Redis error returns null instead of propagating")
        void failsOpenOnRedisError() {
            when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOps);
            when(hashOps.multiGet(eq(ITER_KEY), anyList()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

            assertNull(store.readProgress(RUN, EPOCH, EDGE),
                "a Redis hiccup must not break a live loop - the caller falls back to local state");
        }

        @Test
        @DisplayName("fail-open: a corrupt iteration value returns null instead of throwing")
        void failsOpenOnCorruptValue() {
            when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOps);
            when(hashOps.multiGet(eq(ITER_KEY), anyList())).thenReturn(List.of("not-a-number", "0"));

            assertNull(store.readProgress(RUN, EPOCH, EDGE));
        }

        @Test
        @DisplayName("A blank runId or edgeId yields no key and no Redis call")
        void guardsAgainstBlankIdentifiers() {
            assertNull(store.readProgress(null, EPOCH, EDGE));
            assertNull(store.readProgress("", EPOCH, EDGE));
            assertNull(store.readProgress(RUN, EPOCH, null));
            assertNull(store.readProgress(RUN, EPOCH, ""));
            verifyNoInteractions(redisTemplate);
        }
    }

    @Nested
    @DisplayName("tryClaim()")
    class TryClaim {

        @Test
        @DisplayName("SETNX wins -> claim granted")
        void grantsClaimOnSetNxWin() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);

            assertTrue(store.tryClaim(RUN, EPOCH, EDGE, 4));
        }

        @Test
        @DisplayName("SETNX loses -> claim refused (a split sibling or another replica has it)")
        void refusesClaimWhenAlreadyHeld() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(false);

            assertFalse(store.tryClaim(RUN, EPOCH, EDGE, 4));
        }

        @Test
        @DisplayName("A null reply is treated as won, not lost")
        void nullReplyCountsAsWon() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(null);

            assertTrue(store.tryClaim(RUN, EPOCH, EDGE, 4),
                "only an explicit FALSE means another caller holds the claim");
        }

        @Test
        @DisplayName("fail-open: a Redis error grants the claim rather than stalling the loop")
        void failsOpenOnRedisError() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

            assertTrue(store.tryClaim(RUN, EPOCH, EDGE, 4));
        }

        @Test
        @DisplayName("The claim key is scoped by run, epoch, edge AND iteration")
        void claimKeyIsFullyScoped() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);

            store.tryClaim(RUN, EPOCH, EDGE, 12);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(valueOps).setIfAbsent(key.capture(), anyString(), anyLong(), any());
            assertEquals("loop:claim:" + RUN + ":" + EPOCH + ":" + EDGE + ":12", key.getValue(),
                "a per-iteration key is what lets the NEXT iteration claim again");
        }
    }

    @Nested
    @DisplayName("monotonic writes")
    class MonotonicWrites {

        /**
         * The hazard this guards: readProgress fails open (returns null on a Redis blip), so a
         * replica seeing the run for the first time falls back to its local iteration=0. A blind
         * HSET would then write 1 over a shared 59, resetting the ceiling and licensing another
         * maxIterations body runs - re-opening the very bug this store fixes.
         */
        @Test
        @DisplayName("regression: the advance script raises the iteration only, never lowers it")
        void advanceScriptOnlyRaisesTheIteration() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);

            store.recordIteration(RUN, EPOCH, EDGE, 8);

            ArgumentCaptor<RedisScript<?>> script = ArgumentCaptor.forClass(RedisScript.class);
            ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(redisTemplate).execute(script.capture(), keys.capture(), args.capture());

            assertEquals(List.of(ITER_KEY), keys.getValue());
            assertArrayEquals(new Object[]{"8", "0", String.valueOf(Duration.ofHours(24).toMillis())},
                args.getValue());

            String lua = script.getValue().getScriptAsString();
            assertTrue(lua.contains("if new > cur then"),
                "the write must be guarded so a stale caller cannot lower the shared count");
            assertTrue(lua.contains("PEXPIRE"), "every write refreshes the sliding TTL");
        }

        @Test
        @DisplayName("markTerminated sets the terminated flag alongside the iteration")
        void markTerminatedSetsTheFlag() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);

            store.markTerminated(RUN, EPOCH, EDGE, 59);

            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(redisTemplate).execute(any(RedisScript.class), anyList(), args.capture());
            assertEquals("59", args.getValue()[0]);
            assertEquals("1", args.getValue()[1]);
        }

        @Test
        @DisplayName("fail-open: a Redis error on write is swallowed, not propagated to the engine")
        void failsOpenOnWriteError() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

            assertDoesNotThrow(() -> store.recordIteration(RUN, EPOCH, EDGE, 3));
            assertDoesNotThrow(() -> store.markTerminated(RUN, EPOCH, EDGE, 3));
        }

        @Test
        @DisplayName("A blank runId or edgeId writes nothing")
        void guardsAgainstBlankIdentifiers() {
            store.recordIteration(null, EPOCH, EDGE, 1);
            store.markTerminated(RUN, EPOCH, "", 1);
            verifyNoInteractions(redisTemplate);
        }
    }

    @Nested
    @DisplayName("clearBackEdge()")
    class ClearBackEdge {

        /**
         * The bound MUST come from the plan's maxIterations, never from readProgress: that read
         * fails open, so on a Redis blip a read-bounded clear would delete the hash and ZERO claim
         * markers. The surviving claim:0 then makes claimBackEdgeAdvance return false forever on
         * the AUTO path (sticky: later reruns read null and again delete nothing), silently
         * reproducing the "loop does not iterate after a rerun" symptom.
         */
        @Test
        @DisplayName("regression: deletes claim markers even when the counter read is unavailable")
        @SuppressWarnings("unchecked")
        void deletesClaimMarkersWithoutReadingTheCounter() {
            store.clearBackEdge(RUN, EPOCH, EDGE, 3);

            ArgumentCaptor<java.util.Collection<String>> keys =
                ArgumentCaptor.forClass(java.util.Collection.class);
            verify(redisTemplate).delete(keys.capture());
            assertTrue(keys.getValue().contains(ITER_KEY), "the counter hash itself must go");
            for (int i = 0; i <= 3; i++) {
                assertTrue(keys.getValue().contains(
                        "loop:claim:" + RUN + ":" + EPOCH + ":" + EDGE + ":" + i),
                    "claim marker " + i + " must go - a survivor blocks the next advance");
            }
            verify(redisTemplate, never()).opsForHash();
        }

        @Test
        @DisplayName("A malformed maxIterations cannot emit an unbounded DEL")
        @SuppressWarnings("unchecked")
        void clampsTheDeleteToAHardCeiling() {
            store.clearBackEdge(RUN, EPOCH, EDGE, Integer.MAX_VALUE);

            ArgumentCaptor<java.util.Collection<String>> keys =
                ArgumentCaptor.forClass(java.util.Collection.class);
            verify(redisTemplate).delete(keys.capture());
            assertEquals(RedisLoopIterationStore.MAX_CLAIM_KEYS_PER_CLEAR + 2, keys.getValue().size());
        }

        @Test
        @DisplayName("A negative maxIterations still clears the counter hash")
        @SuppressWarnings("unchecked")
        void negativeMaxStillClearsTheHash() {
            store.clearBackEdge(RUN, EPOCH, EDGE, -5);

            ArgumentCaptor<java.util.Collection<String>> keys =
                ArgumentCaptor.forClass(java.util.Collection.class);
            verify(redisTemplate).delete(keys.capture());
            assertTrue(keys.getValue().contains(ITER_KEY));
        }

        @Test
        @DisplayName("fail-open: a Redis error is swallowed, the rerun still proceeds")
        void failsOpenOnRedisError() {
            doThrow(new org.springframework.dao.QueryTimeoutException("redis down"))
                .when(redisTemplate).delete(anyCollection());

            assertDoesNotThrow(() -> store.clearBackEdge(RUN, EPOCH, EDGE, 3));
        }

        @Test
        @DisplayName("A blank runId or edgeId deletes nothing")
        void guardsAgainstBlankIdentifiers() {
            store.clearBackEdge(null, EPOCH, EDGE, 3);
            store.clearBackEdge(RUN, EPOCH, "", 3);
            verifyNoInteractions(redisTemplate);
        }
    }

    @Nested
    @DisplayName("key scoping")
    class KeyScoping {

        @Test
        @DisplayName("Keys are epoch-scoped so each trigger fire starts from a fresh counter")
        void keysAreEpochScoped() {
            assertNotEquals(
                RedisLoopIterationStore.iterKey(RUN, 1, EDGE),
                RedisLoopIterationStore.iterKey(RUN, 2, EDGE),
                "epoch scoping is what makes a run-wide clear unnecessary (and unsafe: a refire "
                + "must not wipe a previous epoch's still-running loop)");
        }

        @Test
        @DisplayName("Two loops in the same run keep independent counters")
        void distinctEdgesGetDistinctKeys() {
            assertNotEquals(
                RedisLoopIterationStore.iterKey(RUN, 1, "core:a->core:loop_a:iterate"),
                RedisLoopIterationStore.iterKey(RUN, 1, "core:b->core:loop_b:iterate"));
        }
    }
}
