package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis exercise of {@link RedisInFlightStore#stage}, so the ACTUAL MULTI/EXEC runs
 * end-to-end.
 *
 * <p>The mocked unit test in {@code RedisInFlightStoreTest} can only assert that the code
 * issues the commands it was written to issue: the transaction result is handed to it by the
 * test itself. Two things the whole fix rests on are therefore invisible there, and both are
 * properties of Redis and Lettuce rather than of this codebase:
 * <ol>
 *   <li>that {@code multi()} / queued ops / {@code exec()} through a {@code SessionCallback}
 *       really is atomic across these three keys, so a concurrent reader can never see the
 *       index member without its value;
 *   <li>that {@code exec()} reports a transaction that applied, whatever Spring's internal
 *       decomposition of the three convenience methods into wire commands happens to be.
 * </ol>
 *
 * <p>Why it matters: staging used to be a SADD followed by a separate SET, and a per-run guard
 * reading between the two resolved the member to nothing and PRUNED it. Nothing re-added it,
 * so the delivery went permanently invisible to every guard, its epoch closed while the agent
 * was still being delivered, and the node downstream of the agent was never dispatched, never
 * marked SKIPPED, and got no step row.
 *
 * <p>Skips gracefully when no Docker daemon is available.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RedisInFlightStore - REAL Redis (actual MULTI/EXEC)")
class RedisInFlightStoreRealRedisTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory cf;
    private StringRedisTemplate redis;
    private RedisInFlightStore store;

    @BeforeEach
    void setUp() {
        cf = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        cf.afterPropertiesSet();
        redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new RedisInFlightStore(redis, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (cf != null) cf.destroy();
    }

    private static RedisInFlightStore.InFlightEntry entry(String correlationId, String runId, int epoch) {
        PendingAgent p = new PendingAgent(
                correlationId, runId, "agent:x", "X", "trigger:ask", epoch, 0, "0", "agent",
                "t1", null, null, null, null, null, "deepseek-chat", null, null, Instant.now(), "o1");
        AgentResultMessage r = new AgentResultMessage(
                correlationId, runId, "agent:x", Map.of(), true, null, "agent", Instant.now());
        return new RedisInFlightStore.InFlightEntry(p, r);
    }

    @Test
    @DisplayName("stage writes the value, the index member and the index TTL, and the guards see it")
    void stageWritesEverythingAndTheGuardsSeeIt() {
        RedisInFlightStore.InFlightEntry e = entry("cid-real", "run-real", 3);

        store.stage(e.pending(), e.result());

        assertNotNull(redis.opsForValue().get(RedisInFlightStore.KEY_PREFIX + "cid-real"),
                "the value must exist");
        Set<String> members = redis.opsForSet().members(RedisInFlightStore.RUN_INDEX_PREFIX + "run-real");
        assertEquals(Set.of("cid-real"), members, "the index member must exist");
        Long ttl = redis.getExpire(RedisInFlightStore.RUN_INDEX_PREFIX + "run-real", TimeUnit.SECONDS);
        assertTrue(ttl != null && ttl > 0,
                "the index must expire with the entries it points at, or an index abandoned by a "
                        + "crash keeps a run's epoch resets deferred forever (got: " + ttl + ")");
        assertTrue(store.hasAnyInFlightForRun("run-real"),
                "what stage() writes must be what the guard reads");
        assertTrue(store.hasOtherInFlightForEpoch("run-real", "trigger:ask", 3, null));
    }

    @Test
    @DisplayName("A guard reading concurrently NEVER observes the index member without its value")
    void concurrentGuardNeverSeesAHalfWrittenEntry() throws Exception {
        // The regression, against real Redis. Pre-fix this loop reliably caught the window,
        // because SADD and SET were separate round trips. Post-fix the transaction makes the
        // pair indivisible, so the reader either sees neither or both.
        //
        // The assertion is on the SHAPE, not on a count: a reader that sees the member must be
        // able to resolve it. One violation is the whole bug, so a single failing iteration
        // fails the test.
        final int rounds = 300;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger halfWritten = new AtomicInteger();
        AtomicInteger observedComplete = new AtomicInteger();
        try {
            for (int i = 0; i < rounds; i++) {
                String cid = "cid-race-" + i;
                String runId = "run-race";
                redis.delete(RedisInFlightStore.RUN_INDEX_PREFIX + runId);
                redis.delete(RedisInFlightStore.KEY_PREFIX + cid);
                RedisInFlightStore.InFlightEntry e = entry(cid, runId, 1);

                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);

                pool.submit(() -> {
                    try {
                        go.await();
                        store.stage(e.pending(), e.result());
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        go.await();
                        // SPIN, do not sample once. A single read almost always lands before
                        // the writer's first command and observes nothing, which proves
                        // nothing - the assertion below rejects exactly that outcome. Reading
                        // in a tight loop until the pair is complete puts many reads inside
                        // the interval the writer is working in, which is the only way to
                        // observe a half-written entry if one is possible.
                        //
                        // Read the two keys the way a per-run guard does, but WITHOUT going
                        // through listForRun: the point is to observe the raw pair, not to
                        // exercise the prune.
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (System.nanoTime() < deadline) {
                            Set<String> seen = redis.opsForSet()
                                    .members(RedisInFlightStore.RUN_INDEX_PREFIX + runId);
                            if (seen != null && seen.contains(cid)) {
                                if (redis.opsForValue().get(RedisInFlightStore.KEY_PREFIX + cid) == null) {
                                    halfWritten.incrementAndGet();
                                } else {
                                    observedComplete.incrementAndGet();
                                }
                                return;
                            }
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });

                go.countDown();
                assertTrue(done.await(10, TimeUnit.SECONDS), "round " + i + " did not settle");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, halfWritten.get(),
                "a reader saw the index member with no value. That member is exactly what "
                        + "listForRun prunes, and nothing would re-add it - the delivery would go "
                        + "permanently invisible to every per-run guard");
        assertTrue(observedComplete.get() > 0,
                "the reader never won the race in " + rounds + " rounds, so this test proved "
                        + "nothing about the window - it must observe the staged pair at least once");
    }

    @Test
    @DisplayName("A member whose value is gone is still pruned: staging atomically must not make the store hoard stale members")
    void staleMemberIsStillPruned() {
        RedisInFlightStore.InFlightEntry e = entry("cid-stale", "run-stale", 1);
        store.stage(e.pending(), e.result());
        // clear() deletes the value and deliberately leaves the member behind.
        store.clear("cid-stale");

        assertTrue(redis.opsForSet().isMember(RedisInFlightStore.RUN_INDEX_PREFIX + "run-stale", "cid-stale"),
                "precondition: clear() leaves the member for the reader to prune");

        assertTrue(!store.hasAnyInFlightForRun("run-stale"),
                "a member that resolves to nothing is not an in-flight agent");
        assertEquals(Set.of(), redis.opsForSet().members(RedisInFlightStore.RUN_INDEX_PREFIX + "run-stale"),
                "and it must be pruned, or a run whose agents all finished could never re-arm");
    }
}
