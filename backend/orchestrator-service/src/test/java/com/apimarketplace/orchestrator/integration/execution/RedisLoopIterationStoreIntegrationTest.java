package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link RedisLoopIterationStore} against a REAL Redis.
 *
 * <p>The unit tests can only assert the Lua script's TEXT. The monotonic-write guarantee is the
 * load-bearing property of the 2026-07-30 fix (a maxIterations=60 loop ran 73+ body iterations
 * because the counter was per-JVM), and a wrong field name, a swapped ARGV index or {@code >=}
 * instead of {@code >} would sail past a substring assertion while silently letting a stale
 * replica rewind the shared count. So the script is exercised for real here.
 */
@Testcontainers
@DisplayName("RedisLoopIterationStore (real Redis)")
class RedisLoopIterationStoreIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final String RUN = "run-loop-store-it";
    private static final String EDGE = "core:statut->core:attendre_le_rendu:iterate";

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static RedisLoopIterationStore store;

    @BeforeAll
    static void startRedis() {
        connectionFactory = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisConnectionFactory> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(connectionFactory);
        store = new RedisLoopIterationStore(provider);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private String edge(String suffix) {
        // A distinct edge id per test keeps them independent without flushing the DB.
        return EDGE + ":" + suffix;
    }

    @Test
    @Timeout(30)
    @DisplayName("The first write creates the hash and is read back")
    void firstWriteCreatesTheHash() {
        String e = edge("create");
        assertThat(store.readProgress(RUN, 1, e)).isNull();

        store.recordIteration(RUN, 1, e, 0);

        RedisLoopIterationStore.LoopProgress progress = store.readProgress(RUN, 1, e);
        assertThat(progress).isNotNull();
        assertThat(progress.iteration()).isZero();
        assertThat(progress.terminated()).isFalse();
    }

    @Test
    @Timeout(30)
    @DisplayName("regression: a stale replica cannot rewind the shared iteration")
    void writesAreMonotonic() {
        String e = edge("monotonic");
        store.recordIteration(RUN, 1, e, 59);

        // A replica that just failed a readProgress falls back to its local iteration=0 and
        // publishes 1. Pre-fix this was a blind HSET and reset the ceiling, licensing another
        // maxIterations body runs.
        store.recordIteration(RUN, 1, e, 1);

        assertThat(store.readProgress(RUN, 1, e).iteration())
            .as("the shared count must never go backwards")
            .isEqualTo(59);

        store.recordIteration(RUN, 1, e, 60);
        assertThat(store.readProgress(RUN, 1, e).iteration())
            .as("a higher value still advances")
            .isEqualTo(60);
    }

    @Test
    @Timeout(30)
    @DisplayName("The terminated flag sticks and survives a later, lower write")
    void terminatedFlagSticks() {
        String e = edge("terminated");
        store.recordIteration(RUN, 1, e, 12);
        store.markTerminated(RUN, 1, e, 12);

        assertThat(store.readProgress(RUN, 1, e).terminated()).isTrue();

        store.recordIteration(RUN, 1, e, 3);
        RedisLoopIterationStore.LoopProgress after = store.readProgress(RUN, 1, e);
        assertThat(after.terminated())
            .as("termination is one-way - a late straggler must not revive the loop")
            .isTrue();
        assertThat(after.iteration()).isEqualTo(12);
    }

    @Test
    @Timeout(30)
    @DisplayName("Every write sets the sliding TTL, so a slow loop never expires mid-flight")
    void everyWriteRefreshesTheTtl() {
        String e = edge("ttl");
        String key = RedisLoopIterationStore.iterKey(RUN, 1, e);
        long ttlSeconds = RedisLoopIterationStore.DEFAULT_TTL.toSeconds();

        store.recordIteration(RUN, 1, e, 1);
        assertThat(template.getExpire(key))
            .as("the very first write must create the key AND arm its expiry")
            .isBetween(ttlSeconds - 60, ttlSeconds);

        store.markTerminated(RUN, 1, e, 2);
        assertThat(template.getExpire(key))
            .as("the terminal write re-arms it too")
            .isBetween(ttlSeconds - 60, ttlSeconds);
    }

    @Test
    @Timeout(30)
    @DisplayName("tryClaim grants exactly one winner per iteration, and the next iteration can claim again")
    void claimIsPerIterationAndSingleWinner() {
        String e = edge("claim");

        assertThat(store.tryClaim(RUN, 1, e, 4)).isTrue();
        assertThat(store.tryClaim(RUN, 1, e, 4))
            .as("a split sibling or a second replica at the same iteration must lose")
            .isFalse();
        assertThat(store.tryClaim(RUN, 1, e, 5))
            .as("the NEXT iteration is a different claim")
            .isTrue();
    }

    /**
     * A step rerun keeps the EPOCH ({@code StepRerunService}: "Increment spawn (NOT epoch)") and
     * the epoch is what keys the counter, so the rerun path must clear it explicitly - otherwise
     * the reset loop reads back {@code terminated=1} and exits after zero iterations until the TTL.
     */
    @Test
    @Timeout(30)
    @DisplayName("regression: clearBackEdge wipes the counter AND its claim markers, so a rerun starts clean")
    void clearBackEdgeResetsTheCounterAndClaims() {
        String e = edge("clear");
        store.recordIteration(RUN, 1, e, 2);
        store.markTerminated(RUN, 1, e, 2);
        assertThat(store.tryClaim(RUN, 1, e, 0)).isTrue();
        assertThat(store.tryClaim(RUN, 1, e, 1)).isTrue();
        assertThat(store.tryClaim(RUN, 1, e, 2)).isTrue();

        store.clearBackEdge(RUN, 1, e, 60);

        assertThat(store.readProgress(RUN, 1, e))
            .as("the rerun must see no shared opinion at all, not a terminated loop")
            .isNull();
        assertThat(store.tryClaim(RUN, 1, e, 0))
            .as("stale claim markers must go too, else the AUTO path could not re-advance")
            .isTrue();
        assertThat(store.tryClaim(RUN, 1, e, 2)).isTrue();
    }

    @Test
    @Timeout(30)
    @DisplayName("clearBackEdge touches only its own back-edge and only its own epoch")
    void clearBackEdgeIsNarrowlyScoped() {
        String target = edge("scoped-target");
        String sibling = edge("scoped-sibling");
        store.recordIteration(RUN, 1, target, 5);
        store.recordIteration(RUN, 1, sibling, 6);
        store.recordIteration(RUN, 2, target, 7);

        store.clearBackEdge(RUN, 1, target, 60);

        assertThat(store.readProgress(RUN, 1, target)).isNull();
        assertThat(store.readProgress(RUN, 1, sibling).iteration())
            .as("a sibling loop in the same run/epoch must survive")
            .isEqualTo(6);
        assertThat(store.readProgress(RUN, 2, target).iteration())
            .as("another epoch's still-running loop must survive - wiping it is the overrun")
            .isEqualTo(7);
    }

    @Test
    @Timeout(30)
    @DisplayName("A trigger refire (new epoch) is a fresh counter with no clear needed")
    void newEpochIsAFreshCounter() {
        String e = edge("epoch");
        store.recordIteration(RUN, 1, e, 59);
        store.markTerminated(RUN, 1, e, 59);

        assertThat(store.readProgress(RUN, 2, e)).isNull();
        assertThat(store.readProgress(RUN, 1, e).terminated())
            .as("the previous epoch is untouched")
            .isTrue();
    }

    @Test
    @Timeout(30)
    @DisplayName("Two loops in the same run keep independent counters")
    void distinctEdgesAreIndependent() {
        store.recordIteration(RUN, 1, edge("loop-a"), 7);
        store.recordIteration(RUN, 1, edge("loop-b"), 2);

        assertThat(store.readProgress(RUN, 1, edge("loop-a")).iteration()).isEqualTo(7);
        assertThat(store.readProgress(RUN, 1, edge("loop-b")).iteration()).isEqualTo(2);
    }
}
