package com.apimarketplace.orchestrator.execution.v2.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cross-replica progress store for while-loop back-edges.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Prod incident 2026-07-30: a while loop configured {@code maxIterations=60} ran 73+ body
 * iterations. Its body contains a 15s Wait node, so every iteration yields a {@code WAIT_TIMER}
 * signal and the loop is advanced from the SIGNAL-RESUME path. Signal resumes are dispatched
 * cross-instance (see {@code SignalResumeRedisListener}), so with 2 orchestrator replicas the
 * same run's back-edge was advanced alternately by both pods - and the iteration counter
 * ({@link BackEdgeState}) lived ONLY in {@code V2StepByStepContextManager}'s per-JVM
 * {@code globalDataCache}. Each pod therefore counted only ITS OWN iterations: the guard was
 * enforced per-replica, so the effective ceiling became {@code replicas x maxIterations}.
 * Proof from the incident logs, same run, same edge, same instant:
 * pod A {@code iteration=39/60} while pod B reported {@code iteration=59/60}.
 *
 * <p>The same split-brain affected {@code BackEdgeHandler}'s in-memory
 * {@code claimedBackEdgeCalls} split-sibling dedup, so a forEach inside a loop body could have
 * its back-edge processed once PER REPLICA instead of once.
 *
 * <h2>Contract</h2>
 *
 * <p>Two Redis keys per {@code (runId, epoch, edgeId)}:
 * <ul>
 *   <li>{@code loop:iter:{runId}:{epoch}:{edgeId}} - HASH {@code {iteration, terminated}}:
 *       the shared source of truth. {@code iteration} follows {@link BackEdgeState#iteration()}
 *       semantics (the body iteration that just completed; the loop's initial body entry is 0).</li>
 *   <li>{@code loop:claim:{runId}:{epoch}:{edgeId}:{iteration}} - SETNX marker replacing
 *       the per-JVM claim set, so exactly one caller (across all replicas) advances a given
 *       iteration.</li>
 * </ul>
 *
 * <p><b>Why the key stops at {@code epoch}, and why a step rerun needs
 * {@link #clearBackEdge}.</b> The key must be STABLE for the whole life of one loop: every
 * iteration of a loop whose body waits is advanced from a different resume, possibly on a
 * different replica, and a key that moves under a live loop silently restarts its counter - the
 * original overrun. {@code epoch} qualifies: it is persisted on the signal
 * ({@code SignalWaitEntity.epoch}, part of its unique constraint) and handed back verbatim to the
 * resume, so every advance of a given loop agrees on it.
 *
 * <p>{@code spawn} does NOT qualify, despite looking like the natural "rerun generation": it is
 * not persisted on the signal, so it is re-read live per resume, and
 * {@code TriggerEpochManager.incrementEpoch} RESETS it to 0 for the trigger. A refire would then
 * move a still-running older epoch's loop onto a different key mid-flight. So the rerun case is
 * handled explicitly instead: {@code StepRerunService} calls {@link #clearBackEdge} for exactly
 * the back-edges it is resetting.
 *
 * <p><b>Writes are MONOTONIC.</b> {@link #recordIteration} and {@link #markTerminated} run a Lua
 * script that only ever raises {@code iteration}. Without that, a single failed {@link #readProgress}
 * (which fails open, see below) on a replica that has never seen the run would make it fall back to
 * its local {@code iteration=0} and then blindly HSET 1 over a shared 59 - resetting the ceiling and
 * licensing another {@code maxIterations} body runs, i.e. re-opening the very bug this class fixes.
 *
 * <p><b>Fail-open by design.</b> Every method degrades to "no shared opinion" on a Redis error
 * ({@link #readProgress} returns {@code null}, {@link #tryClaim} returns {@code true}), which
 * puts the caller back on the pre-fix per-JVM behavior instead of stalling a live loop. Monotonic
 * writes are what make that safe.
 *
 * <p><b>TTL</b> is sliding: 24h, refreshed on every write. An active loop (even one waiting hours
 * between iterations) keeps its key alive; abandoned keys expire on their own. There is
 * deliberately NO run-wide clear - only the targeted {@link #clearBackEdge}.
 * {@code BackEdgeHandler.cleanupRun} in particular must NOT wipe anything: it is reached from
 * {@code WorkflowResumeService.clearCachedStateForRerun}, whose callers include
 * {@code ReusableTriggerService} on a refire while a PREVIOUS epoch's loop may still be mid-Wait;
 * wiping that live epoch's counter would restart it at 0 and reproduce the overrun.
 *
 * <p><b>Own template, on purpose.</b> This builds its own {@link StringRedisTemplate} from the
 * connection factory instead of injecting one: the only {@code StringRedisTemplate} bean in this
 * service is {@code WebSearchConfig.webSearchRedisTemplate}, whose Lettuce command timeout is
 * sized for a 600s {@code BLPOP}. Inheriting that would make a Redis hiccup block the back-edge
 * thread for minutes - the exact opposite of the fail-open contract above.
 *
 * <p><b>No {@code @ConditionalOnBean}.</b> A user {@code @Component} scan is evaluated BEFORE
 * auto-configured {@code @Bean} methods are registered, so a class-level
 * {@code @ConditionalOnBean(...)} on an auto-configured type silently never matches and the bean
 * is never created (documented in this repo on {@code OrchestratorInstanceRegistrar}). The bean is
 * therefore unconditional and gates itself on a {@code null} connection factory at runtime.
 */
@Component
public class RedisLoopIterationStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisLoopIterationStore.class);

    static final String ITER_PREFIX = "loop:iter:";
    static final String CLAIM_PREFIX = "loop:claim:";

    static final String FIELD_ITERATION = "iteration";
    static final String FIELD_TERMINATED = "terminated";

    /** Sliding: refreshed on every write, so a long-waiting but ALIVE loop never expires. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Hard ceiling on the claim markers one {@link #clearBackEdge} may delete. The builder path
     * validates {@code maxIterations} to 1..10000, but a plan authored another way is not checked,
     * so this bounds the {@code DEL} argument list regardless of what the plan claims.
     */
    static final int MAX_CLAIM_KEYS_PER_CLEAR = 10_000;

    /**
     * KEYS[1]=hash key, ARGV[1]=iteration, ARGV[2]=terminated flag ("0"/"1"), ARGV[3]=ttl ms.
     * Raises {@code iteration} only (never lowers it) and only ever SETS the terminated flag.
     * On a missing key {@code HGET} yields Lua {@code false}, so {@code cur} starts at -1 and the
     * first write always creates the hash before {@code PEXPIRE} runs.
     * Field names come from the constants above so a rename cannot desync the script from
     * {@link #readProgress}.
     */
    private static final RedisScript<Long> ADVANCE_SCRIPT = new DefaultRedisScript<>(
        "local cur = tonumber(redis.call('HGET', KEYS[1], '" + FIELD_ITERATION + "') or '-1')\n"
        + "local new = tonumber(ARGV[1])\n"
        + "if new > cur then redis.call('HSET', KEYS[1], '" + FIELD_ITERATION + "', ARGV[1]) end\n"
        + "if ARGV[2] == '1' then redis.call('HSET', KEYS[1], '" + FIELD_TERMINATED + "', '1') end\n"
        + "redis.call('PEXPIRE', KEYS[1], ARGV[3])\n"
        + "return 1\n",
        Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    /**
     * Takes an {@link ObjectProvider} rather than an optional parameter: per-PARAMETER
     * {@code @Autowired(required = false)} is silently ignored by Spring (optionality is read from
     * the constructor element, and only a {@code @Nullable}-named annotation marks a parameter
     * optional), so an absent factory would throw instead of degrading. The provider makes the
     * "no Redis wired" path real.
     */
    @Autowired
    public RedisLoopIterationStore(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider) {
        this(buildTemplate(connectionFactoryProvider.getIfAvailable()), DEFAULT_TTL);
    }

    private static StringRedisTemplate buildTemplate(RedisConnectionFactory connectionFactory) {
        if (connectionFactory == null) {
            logger.warn("[LoopStore] No Redis connection factory - loop iteration counts stay per-JVM. "
                + "A multi-replica deployment will enforce maxIterations per replica.");
            return null;
        }
        return new StringRedisTemplate(connectionFactory);
    }

    /** Test-only - inject a mock template and/or a short TTL. */
    RedisLoopIterationStore(StringRedisTemplate redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    /** False when no Redis is wired: the caller then keeps its per-JVM state. */
    public boolean isEnabled() {
        return redisTemplate != null;
    }

    /**
     * Shared progress of one loop back-edge.
     *
     * @param iteration  body iteration that just completed (initial body entry = 0)
     * @param terminated true once some replica has ended the loop AND activated its exit path
     */
    public record LoopProgress(int iteration, boolean terminated) {}

    /**
     * Read the cross-replica progress for a back-edge.
     *
     * @return {@code null} when nothing has been recorded yet OR Redis is unreachable - the
     *         caller then keeps its local {@link BackEdgeState} (fail-open). Safe only because
     *         writes are monotonic (see class javadoc).
     */
    public LoopProgress readProgress(String runId, int epoch, String edgeId) {
        String key = iterKey(runId, epoch, edgeId);
        if (key == null || redisTemplate == null) {
            return null;
        }
        try {
            List<Object> values = redisTemplate.opsForHash()
                .multiGet(key, List.of(FIELD_ITERATION, FIELD_TERMINATED));
            if (values == null || values.isEmpty() || values.get(0) == null) {
                return null;
            }
            int iteration = Integer.parseInt(String.valueOf(values.get(0)).trim());
            boolean terminated = values.size() > 1 && "1".equals(String.valueOf(values.get(1)));
            return new LoopProgress(iteration, terminated);
        } catch (Exception e) {
            logger.warn("[LoopStore] readProgress failed for runId={} epoch={} edgeId={}: {}",
                runId, epoch, edgeId, e.getMessage());
            return null;
        }
    }

    /**
     * Claim the right to advance the back-edge from {@code completedIteration}. Replaces the
     * per-JVM claim set: when a forEach inside the loop body fans out, every split sibling
     * reaches the same back-edge at the same {@code completedIteration} and only the first
     * claimant (across ALL replicas) must process it.
     *
     * @return true when this caller won the claim (or Redis is unreachable - fail-open, which
     *         reproduces the pre-fix single-replica behavior rather than stalling the loop)
     */
    public boolean tryClaim(String runId, int epoch, String edgeId, int completedIteration) {
        String key = claimKey(runId, epoch, edgeId, completedIteration);
        if (key == null || redisTemplate == null) {
            return true;
        }
        try {
            Boolean won = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", ttl.toMillis(), TimeUnit.MILLISECONDS);
            // A null reply means the command did not report a loss - treat as won (fail-open).
            return !Boolean.FALSE.equals(won);
        } catch (Exception e) {
            logger.warn("[LoopStore] tryClaim failed for runId={} epoch={} edgeId={} iteration={} - proceeding (fail-open): {}",
                runId, epoch, edgeId, completedIteration, e.getMessage());
            return true;
        }
    }

    /**
     * Publish the iteration a replica has just started, so every other replica sees it.
     * Monotonic: a stale caller can never lower the shared count.
     */
    public void recordIteration(String runId, int epoch, String edgeId, int iteration) {
        advance(runId, epoch, edgeId, iteration, false);
    }

    /**
     * Publish the loop's termination so a sibling replica stops re-entering the body.
     *
     * <p>Call this only AFTER the exit path has been activated: the flag makes every other
     * replica skip the back-edge entirely, so publishing it before the exit is queued would turn
     * a crash in between into a permanently stalled run.
     */
    public void markTerminated(String runId, int epoch, String edgeId, int iteration) {
        advance(runId, epoch, edgeId, iteration, true);
    }

    /**
     * Drop one back-edge's shared progress, so the loop restarts from iteration 0.
     *
     * <p>Called from the STEP-RERUN path only, for the back-edges whose loop body that rerun is
     * resetting. A rerun deliberately keeps the epoch ({@code StepRerunService}: "Increment spawn
     * (NOT epoch)"), so without this the rerun would read back the previous attempt's
     * {@code terminated=1} and exit with zero iterations until the TTL.
     *
     * <p>Deliberately NOT a {@code SCAN}-based run-wide wipe: this deletes exactly the keys of the
     * loops being reset, and only the caller that is intentionally restarting them may call it. A
     * broader clear would let a trigger REFIRE wipe a different, still-running epoch's counter and
     * reproduce the overrun.
     *
     * <p>Claim markers are deleted for iterations {@code 0..maxIterations}, the loop's own
     * ceiling read from the plan - a bounded delete, never a keyspace walk.
     *
     * <p><b>The bound comes from the PLAN, deliberately not from {@link #readProgress}.</b> That
     * read fails open (returns {@code null} on a Redis error), so bounding by it would delete the
     * hash and ZERO claim markers on a blip. The surviving {@code loop:claim:…:0} then makes
     * {@code BackEdgeHandler.claimBackEdgeAdvance} return false on the AUTO path, the rerun loop
     * never advances, and it is STICKY: every later rerun reads {@code null} (the hash is gone)
     * and again deletes nothing. Bounding by {@code maxIterations} cannot under-delete.
     *
     * @param maxIterations the loop's configured ceiling; clamped to
     *                      {@link #MAX_CLAIM_KEYS_PER_CLEAR} so a malformed plan cannot emit an
     *                      unbounded {@code DEL}
     */
    public void clearBackEdge(String runId, int epoch, String edgeId, int maxIterations) {
        String key = iterKey(runId, epoch, edgeId);
        if (key == null || redisTemplate == null) {
            return;
        }
        try {
            int bound = Math.min(Math.max(maxIterations, 0), MAX_CLAIM_KEYS_PER_CLEAR);
            List<String> keys = new ArrayList<>(bound + 2);
            keys.add(key);
            for (int i = 0; i <= bound; i++) {
                keys.add(claimKey(runId, epoch, edgeId, i));
            }
            redisTemplate.delete(keys);
            logger.info("[LoopStore] Cleared back-edge for rerun: runId={} epoch={} edgeId={} keys={}",
                runId, epoch, edgeId, keys.size());
        } catch (Exception e) {
            logger.warn("[LoopStore] clearBackEdge failed for runId={} epoch={} edgeId={}: {}",
                runId, epoch, edgeId, e.getMessage());
        }
    }

    private void advance(String runId, int epoch, String edgeId, int iteration, boolean terminated) {
        String key = iterKey(runId, epoch, edgeId);
        if (key == null || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.execute(ADVANCE_SCRIPT, List.of(key),
                String.valueOf(iteration), terminated ? "1" : "0", String.valueOf(ttl.toMillis()));
        } catch (Exception e) {
            logger.warn("[LoopStore] advance failed for runId={} epoch={} edgeId={} iteration={} terminated={}: {}",
                runId, epoch, edgeId, iteration, terminated, e.getMessage());
        }
    }

    public static String iterKey(String runId, int epoch, String edgeId) {
        if (runId == null || runId.isEmpty() || edgeId == null || edgeId.isEmpty()) {
            return null;
        }
        return ITER_PREFIX + runId + ":" + epoch + ":" + edgeId;
    }

    public static String claimKey(String runId, int epoch, String edgeId, int iteration) {
        if (iterKey(runId, epoch, edgeId) == null) {
            return null;
        }
        return CLAIM_PREFIX + runId + ":" + epoch + ":" + edgeId + ":" + iteration;
    }
}
