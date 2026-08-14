package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.state.elide.EpochStateRunningElideModule;
import com.apimarketplace.orchestrator.services.state.elide.EpochStateRunningElideSerializer;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A refused claim has to say WHICH of four opposite situations it is, for the node the caller
 * actually named, without doing I/O under the claim's row lock and without accumulating entries.
 *
 * <p>Snapshots here are built the way production builds them (through the per-trigger mutators,
 * so {@code dags} is populated). That matters: an earlier version of this feature was tested with
 * {@code dags = Map.of()}, the V2 back-compat path, which made a branch look covered that cannot
 * execute in production at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Claim refusal registry")
class ClaimRefusalRegistryTest {

    private static final String RUN_ID = "run-claim-1";
    private static final String TRIGGER = "trigger:start";
    private static final String OTHER_TRIGGER = "trigger:second";

    @Mock private RunningNodeTracker runningNodeTracker;

    private ClaimRefusalRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ClaimRefusalRegistry(runningNodeTracker);
        when(runningNodeTracker.getRunningCountsAcrossEpochs(RUN_ID)).thenReturn(Map.of());
        when(runningNodeTracker.getRunningCounts(anyString(), anyInt())).thenReturn(Map.of());
    }

    /** A V3 snapshot with `dags`, and no running ids: the shape production persists. */
    private StateSnapshot ready(String... readyNodeIds) {
        StateSnapshot s = StateSnapshot.empty();
        for (String id : readyNodeIds) {
            s = s.addReadyNode(TRIGGER, id);
        }
        return s;
    }

    private String refuseAndExplain(StateSnapshot snapshot, String nodeId) {
        registry.refuse(RUN_ID, snapshot, nodeId);
        return registry.lastRefusal(RUN_ID, nodeId).orElseThrow().explain();
    }

    private String stateOf(String nodeId) {
        return registry.lastRefusal(RUN_ID, nodeId).orElseThrow().state();
    }

    @Nested
    @DisplayName("What the snapshot alone can say")
    class SnapshotClassification {

        @Test
        @DisplayName("A node that already finished is told to be re-run, not executed")
        void completedSuggestsARerun() {
            StateSnapshot s = ready("mcp:b").markNodeCompleted(TRIGGER, "mcp:a");

            assertThat(refuseAndExplain(s, "mcp:a"))
                    .contains("it already finished (completed)", "re-run it instead");
        }

        @Test
        @DisplayName("A failed node names the failure, not a generic 'finished'")
        void failedIsNamed() {
            assertThat(refuseAndExplain(ready("mcp:b").markNodeFailed(TRIGGER, "mcp:a"), "mcp:a"))
                    .contains("it already finished (failed)");
        }

        @Test
        @DisplayName("A skipped node names the skip")
        void skippedIsNamed() {
            assertThat(refuseAndExplain(ready("mcp:b").markNodeSkipped(TRIGGER, "mcp:a"), "mcp:a"))
                    .contains("it already finished (skipped)");
        }

        @Test
        @DisplayName("A node parked on a signal is distinguished from one that never ran")
        void awaitingSignalIsNotConfusedWithNotReady() {
            StateSnapshot s = ready("mcp:b").markNodeAwaitingSignal(TRIGGER, "core:approval");

            assertThat(refuseAndExplain(s, "core:approval")).contains("waiting for a signal");
        }

        @Test
        @DisplayName("An id in none of the sets is hedged, because a stale plan id lands here too")
        void unknownDoesNotAssertOneCause() {
            // Both execute paths accept a plan edit immediately before the claim, so a step id
            // removed by that edit reaches this bucket. Naming "predecessors have not settled" as
            // the cause would be the same confident-but-wrong answer this class exists to stop.
            String explain = refuseAndExplain(ready("core:body"), "mcp:no-longer-in-the-plan");

            assertThat(explain)
                    .contains("not runnable right now")
                    .contains("may not have all settled")
                    .contains("may not be in the current plan");
        }

        @Test
        @DisplayName("The nodes that COULD be claimed are named alongside the refusal")
        void namesWhatIsClaimableInstead() {
            refuseAndExplain(ready("core:body", TRIGGER), "core:loop");

            assertThat(registry.lastRefusal(RUN_ID, "core:loop").orElseThrow().readyNow())
                    .containsExactlyInAnyOrder("core:body", TRIGGER);
        }

        @Test
        @DisplayName("Nothing claimable at all is stated plainly, not as an empty list")
        void emptyReadySetReadsAsNothing() {
            assertThat(refuseAndExplain(StateSnapshot.empty(), "core:loop"))
                    .contains("Claimable right now: nothing");
        }

        @Test
        @DisplayName("Claimable ids are listed in a stable order, so log lines diff across runs")
        void claimableIsSorted() {
            assertThat(refuseAndExplain(ready("mcp:zeta", "core:alpha", "mcp:mid"), "core:loop"))
                    .contains("Claimable right now: core:alpha, mcp:mid, mcp:zeta.");
        }

        @Test
        @DisplayName("An outcome from a PREVIOUS epoch is not reported as the node's state now")
        void anOlderEpochsOutcomeIsNotReportedAsCurrent() {
            // Several epochs are in flight at once by design, and the flat getters union them
            // all. A node COMPLETED in epoch 0 and running again in epoch 1 would read back as
            // "completed", telling the caller to re-run the very epoch it is trying to step.
            // Vague beats precise-about-the-wrong-epoch, so this must NOT say "completed".
            StateSnapshot s = ready("mcp:b")
                    .openEpochForDag(TRIGGER, 0)
                    .markNodeCompleted(TRIGGER, "mcp:a")
                    .openEpochForDag(TRIGGER, 1);
            assertThat(s.getCompletedNodeIds())
                    .as("both epochs are active, so the flat union still carries epoch 0's outcome")
                    .contains("mcp:a");

            registry.refuse(RUN_ID, s, "mcp:a");

            assertThat(stateOf("mcp:a")).isEqualTo("unknown");
        }

        @Test
        @DisplayName("The current epoch's own outcome IS reported")
        void theCurrentEpochsOutcomeIsReported() {
            StateSnapshot s = ready("mcp:b").openEpochForDag(TRIGGER, 1).markNodeCompleted(TRIGGER, "mcp:a");

            registry.refuse(RUN_ID, s, "mcp:a");

            assertThat(stateOf("mcp:a")).isEqualTo("completed");
        }
    }

    @Nested
    @DisplayName("Resolving 'running', which the snapshot cannot answer")
    class RunningResolution {

        /**
         * Round-trips a snapshot through the REAL elide serializer, so this pins the platform
         * behaviour rather than a hand-made empty set. If elide is ever turned off, the middle
         * assertion fails and tells the reader this workaround may no longer be needed.
         */
        private StateSnapshot afterElidingRoundTrip(StateSnapshot source) throws Exception {
            ObjectMapper eliding = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .registerModule(new EpochStateRunningElideModule(tenantId -> true));
            String json = eliding.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-1")
                    .writeValueAsString(source);
            return new ObjectMapper().registerModule(new JavaTimeModule())
                    .readValue(json, StateSnapshot.class);
        }

        @Test
        @DisplayName("A node executing is still named as running after the elide drops it from the snapshot")
        void runningSurvivesTheElideBecauseRedisIsConsulted() throws Exception {
            // THE regression. `runningNodeIds` is elided from the persisted JSONB (default-ON for
            // every tenant), so a node that is plainly executing reads back as being in no state
            // at all, and would be answered "not runnable right now" - which sends the reader to
            // the DAG wiring for what is really a second click on a busy node.
            StateSnapshot beforeSave = ready("mcp:b").addRunningNode(TRIGGER, "mcp:a");
            assertThat(beforeSave.getRunningNodeIds()).contains("mcp:a");

            StateSnapshot asPersisted = afterElidingRoundTrip(beforeSave);
            assertThat(asPersisted.getRunningNodeIds())
                    .as("the elide really does drop it; that is the whole trap")
                    .isEmpty();
            when(runningNodeTracker.getRunningCounts(RUN_ID, 0)).thenReturn(Map.of("mcp:a", 1));

            registry.refuse(RUN_ID, asPersisted, "mcp:a");

            assertThat(stateOf("mcp:a")).isEqualTo("running");
            assertThat(registry.lastRefusal(RUN_ID, "mcp:a").orElseThrow().explain())
                    .contains("already executing");
        }

        @Test
        @DisplayName("A stale counter from a PREVIOUS epoch cannot resurrect a finished node")
        void staleCounterInAnOlderEpochIsNotConsulted() {
            // The asymmetry that a narrower classifier creates: epoch 0's outcome is invisible to
            // the current-epoch classifier, so if the Redis lookup still read every epoch, epoch
            // 0's undecremented counter (pod killed before markCompleted, 1h hash TTL) would
            // upgrade a FINISHED node to "already executing". The lookup is scoped to the same
            // epochs the classifier read, so epoch 0 is never asked about.
            StateSnapshot s = ready("mcp:b")
                    .openEpochForDag(TRIGGER, 0)
                    .markNodeCompleted(TRIGGER, "mcp:a")
                    .openEpochForDag(TRIGGER, 1);
            when(runningNodeTracker.getRunningCountsAcrossEpochs(RUN_ID)).thenReturn(Map.of("mcp:a", 1));
            when(runningNodeTracker.getRunningCounts(RUN_ID, 0)).thenReturn(Map.of("mcp:a", 1));
            when(runningNodeTracker.getRunningCounts(RUN_ID, 1)).thenReturn(Map.of());

            registry.refuse(RUN_ID, s, "mcp:a");

            assertThat(stateOf("mcp:a")).isEqualTo("unknown");
            verify(runningNodeTracker, never()).getRunningCountsAcrossEpochs(RUN_ID);
            verify(runningNodeTracker, never()).getRunningCounts(RUN_ID, 0);
        }

        @Test
        @DisplayName("A node executing under one trigger is never reported as finished under another")
        void aLiveExecutionInAnotherDagBeatsATerminalState() {
            // A node reachable from two triggers can be finished under one and executing under the
            // other. "It already finished, re-run it instead" is the only wrong answer here that
            // pushes the user toward a DESTRUCTIVE action (a second run of something live), so it
            // must not be reachable. The mirror mistake, "already executing" for something
            // finished, only makes them wait.
            StateSnapshot s = StateSnapshot.empty()
                    .addReadyNode(TRIGGER, "mcp:other")
                    .markNodeCompleted(TRIGGER, "mcp:shared")
                    .addReadyNode(OTHER_TRIGGER, "mcp:other2")
                    .addRunningNode(OTHER_TRIGGER, "mcp:shared");

            registry.refuse(RUN_ID, s, "mcp:shared");

            assertThat(stateOf("mcp:shared")).isEqualTo("running");
        }

        @Test
        @DisplayName("The same holds when the live execution is only in Redis, as the elide leaves it")
        void aLiveExecutionKnownOnlyToRedisAlsoBeats() {
            // Production shape: the running marker is elided out of the snapshot, so the second
            // DAG reads as `unknown` and only Redis knows. Resolving Redis solely when the WHOLE
            // answer is unknown would miss this and report "completed".
            StateSnapshot s = StateSnapshot.empty()
                    .addReadyNode(TRIGGER, "mcp:other")
                    .openEpochForDag(TRIGGER, 3)
                    .markNodeCompleted(TRIGGER, "mcp:shared")
                    .addReadyNode(OTHER_TRIGGER, "mcp:other2");
            when(runningNodeTracker.getRunningCounts(RUN_ID, 0)).thenReturn(Map.of("mcp:shared", 1));

            registry.refuse(RUN_ID, s, "mcp:shared");

            assertThat(stateOf("mcp:shared")).isEqualTo("running");
        }

        @Test
        @DisplayName("Two different outcomes across DAGs resolve the same way every run")
        void crossDagTerminalStatesAreDeterministic() {
            // getDags() is an immutable map whose iteration order is randomised per JVM, so
            // "first non-unknown wins" would report `completed` on some restarts and `failed` on
            // others for the same data. The fixed authority order is what makes it stable, and
            // without this test deleting it changes nothing that any assertion notices.
            StateSnapshot s = StateSnapshot.empty()
                    .addReadyNode(TRIGGER, "mcp:other")
                    .markNodeFailed(TRIGGER, "mcp:shared")
                    .addReadyNode(OTHER_TRIGGER, "mcp:other2")
                    .markNodeCompleted(OTHER_TRIGGER, "mcp:shared");

            registry.refuse(RUN_ID, s, "mcp:shared");

            assertThat(stateOf("mcp:shared")).isEqualTo("completed");
        }

        @Test
        @DisplayName("Every label the classifier returns is in the authority list")
        void everyClassifiedStateIsRanked() {
            // What rank() guards: a label the classifier returns that the authority list omits
            // would score -1 from indexOf and silently beat `completed`. This reads the
            // classifier's RAW output - going through a refusal would collapse an unlisted label
            // to `unknown` before it could be seen, which is what made an earlier version of this
            // test pass no matter what. It catches a renamed label, not a newly added branch: no
            // test can exercise a branch nobody calls.
            Set<String> node = Set.of("mcp:a");
            Set<String> none = Set.of();
            assertThat(ClaimRefusalRegistry.PRECEDENCE).containsAll(Set.of(
                    ClaimRefusalRegistry.classifyIn(node, none, none, none, none, "mcp:a"),
                    ClaimRefusalRegistry.classifyIn(none, node, none, none, none, "mcp:a"),
                    ClaimRefusalRegistry.classifyIn(none, none, node, none, none, "mcp:a"),
                    ClaimRefusalRegistry.classifyIn(none, none, none, node, none, "mcp:a"),
                    ClaimRefusalRegistry.classifyIn(none, none, none, none, node, "mcp:a"),
                    ClaimRefusalRegistry.classifyIn(none, none, none, none, none, "mcp:a")));
        }

        @Test
        @DisplayName("Within one epoch a recorded outcome outranks a running marker")
        void withinOneEpochTheOutcomeWins() {
            // A legacy (pre-elide) snapshot can carry both for the same node in the same epoch.
            // If running were checked first, such a node would answer "it is already executing"
            // for the hash's whole life - the confidently-wrong class this class exists to
            // remove. Only reachable on non-elided snapshots, and untested until now.
            Set<String> node = Set.of("mcp:a");
            Set<String> none = Set.of();

            assertThat(ClaimRefusalRegistry.classifyIn(node, none, none, none, node, "mcp:a"))
                    .isEqualTo("completed");
            assertThat(ClaimRefusalRegistry.classifyIn(none, node, none, none, node, "mcp:a"))
                    .isEqualTo("failed");
            assertThat(ClaimRefusalRegistry.classifyIn(none, none, none, node, node, "mcp:a"))
                    .isEqualTo("awaiting_signal");
        }

        @Test
        @DisplayName("Two DAGs at the same epoch cost one Redis read, not two")
        void theRunningLookupIsDedupedPerEpoch() {
            // The tracker hash is keyed (run, epoch) and shared by every DAG at that number, so
            // asking once per DAG would be the same HGETALL twice on a user-click path. Without
            // this assertion the dedupe can be dropped and nothing goes red.
            StateSnapshot s = StateSnapshot.empty()
                    .addReadyNode(TRIGGER, "mcp:other")
                    .addReadyNode(OTHER_TRIGGER, "mcp:other2");

            registry.refuse(RUN_ID, s, "mcp:absent");
            registry.lastRefusal(RUN_ID, "mcp:absent");

            verify(runningNodeTracker, org.mockito.Mockito.times(1)).getRunningCounts(RUN_ID, 0);
        }

        @Test
        @DisplayName("A stale running marker never outranks a recorded outcome")
        void persistedTerminalStateBeatsRedis() {
            // A pod killed mid-execution never decrements the counter, so the entry survives for
            // the hash's TTL. If Redis were consulted first, a node that has since completed
            // would be answered "it is already executing" - confidently wrong, which is worse
            // than vague and is exactly what this class exists to avoid.
            when(runningNodeTracker.getRunningCounts(RUN_ID, 0)).thenReturn(Map.of("mcp:a", 1));
            StateSnapshot s = ready("mcp:b").markNodeCompleted(TRIGGER, "mcp:a");

            registry.refuse(RUN_ID, s, "mcp:a");

            assertThat(stateOf("mcp:a")).isEqualTo("completed");
        }

        @Test
        @DisplayName("Refusing touches no Redis, because it runs under the claim's row lock")
        void refuseDoesNoIoUnderTheLock() {
            // refuse() is called inside loadFreshForUpdate's PESSIMISTIC_WRITE transaction. The
            // tracker's cross-epoch read can degrade to a blocking KEYS scan, and paying for that
            // while holding an exclusive lock on the hottest row in the system would let a
            // diagnostic slow down execution itself.
            registry.refuse(RUN_ID, ready("mcp:b"), "mcp:a");

            verifyNoInteractions(runningNodeTracker);
        }

        @Test
        @DisplayName("A pre-elide snapshot that still carries running ids is honoured")
        void snapshotRunningIdsRemainAFallback() {
            StateSnapshot legacy = StateSnapshot.empty().addRunningNode(TRIGGER, "mcp:a");

            registry.refuse(RUN_ID, legacy, "mcp:a");

            assertThat(stateOf("mcp:a")).isEqualTo("running");
        }

        @Test
        @DisplayName("A tracker entry left at zero is not running")
        void zeroCountIsNotRunning() {
            // Belt and braces: the tracker's own readers already filter counts <= 0, so this
            // guards the contract rather than an observed input.
            when(runningNodeTracker.getRunningCounts(RUN_ID, 0)).thenReturn(Map.of("mcp:a", 0));

            registry.refuse(RUN_ID, ready("mcp:b"), "mcp:a");

            assertThat(stateOf("mcp:a")).isEqualTo("unknown");
        }

        @Test
        @DisplayName("Redis throwing degrades the message, it never breaks the refusal")
        void redisFailureIsSwallowed() {
            // The tracker is fail-open today, but nothing forces it to stay that way, and this
            // call sits on the path that answers a user's click.
            when(runningNodeTracker.getRunningCounts(RUN_ID, 0))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

            registry.refuse(RUN_ID, ready("mcp:b"), "mcp:a");

            assertThat(registry.lastRefusal(RUN_ID, "mcp:a")).isPresent();
            assertThat(stateOf("mcp:a")).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("Attribution and lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("Two refusals on one run each keep their own reason")
        void bothRefusalsSurvive() {
            // Keyed by run alone, the second refusal evicted the first, so the caller that
            // motivated this class - two clients racing on the same run - was the one left with
            // the generic sentence. Per-node keys mean neither loses.
            registry.refuse(RUN_ID, ready("mcp:ready"), "mcp:a");
            registry.refuse(RUN_ID, ready("mcp:ready").markNodeCompleted(TRIGGER, "mcp:b"), "mcp:b");

            assertThat(registry.lastRefusal(RUN_ID, "mcp:a").orElseThrow().nodeId()).isEqualTo("mcp:a");
            assertThat(stateOf("mcp:b")).isEqualTo("completed");
        }

        @Test
        @DisplayName("A refusal is never reported for a node it does not describe")
        void refusalIsNotReportedForAnotherNode() {
            registry.refuse(RUN_ID, ready("mcp:ready"), "mcp:a");

            assertThat(registry.lastRefusal(RUN_ID, "mcp:never-refused")).isEmpty();
        }

        @Test
        @DisplayName("Concurrent refusals on one run all survive, each under its own node")
        void concurrentRefusalsAllSurvive() throws Exception {
            // Fails on a run-keyed cache: only the last writer's reason would remain, so most
            // threads would read empty.
            int threads = 8;
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            AtomicReference<String> problem = new AtomicReference<>();

            for (int i = 0; i < threads; i++) {
                String nodeId = "mcp:node-" + i;
                new Thread(() -> {
                    try {
                        start.await();
                        registry.refuse(RUN_ID, ready("mcp:ready"), nodeId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < threads; i++) {
                String nodeId = "mcp:node-" + i;
                registry.lastRefusal(RUN_ID, nodeId)
                        .filter(r -> r.nodeId().equals(nodeId))
                        .ifPresentOrElse(r -> { }, () -> problem.set("lost the reason for " + nodeId));
            }
            assertThat(problem.get()).isNull();
        }

        @Test
        @DisplayName("A success on one node leaves another node's reason alone")
        void clearIsScopedToItsNode() {
            registry.refuse(RUN_ID, ready("mcp:ready"), "mcp:a");
            registry.refuse(RUN_ID, ready("mcp:ready"), "mcp:b");

            registry.clear(RUN_ID, "mcp:b");

            assertThat(registry.lastRefusal(RUN_ID, "mcp:a"))
                    .as("B succeeding says nothing about why A is refused")
                    .isPresent();
            assertThat(registry.lastRefusal(RUN_ID, "mcp:b")).isEmpty();
        }

        @Test
        @DisplayName("No refusal recorded for a run nobody refused")
        void noRefusalForAnUntouchedRun() {
            assertThat(registry.lastRefusal("run-never-claimed", "mcp:a")).isEmpty();
        }

        @Test
        @DisplayName("A closed run leaves nothing behind, so refusals cannot accumulate")
        void cleanupRunDropsTheEntry() {
            registry.refuse(RUN_ID, ready("mcp:b"), "mcp:a");
            assertThat(registry.getCacheSize()).isEqualTo(1);

            registry.cleanupRun(RUN_ID);

            assertThat(registry.getCacheSize()).isZero();
            assertThat(registry.lastRefusal(RUN_ID, "mcp:a")).isEmpty();
        }

        @Test
        @DisplayName("Two pairs that a printable separator would merge stay distinct")
        void compositeKeysCannotCollide() {
            // The hazard a separator guards is not a shared prefix (startsWith already handles
            // "run-1" vs "run-10" for any separator) but a separator that can occur INSIDE an id.
            // Node ids are `prefix:label`, so with ':' these two pairs both key to "a:b:c" and
            // the second refusal would silently answer for the first.
            registry.refuse("a", ready("mcp:x"), "b:c");
            registry.refuse("a:b", ready("mcp:y").markNodeCompleted(TRIGGER, "c"), "c");

            assertThat(registry.lastRefusal("a", "b:c").orElseThrow().state())
                    .as("run 'a' node 'b:c' must not be answered by run 'a:b' node 'c'")
                    .isEqualTo("unknown");
            assertThat(registry.lastRefusal("a:b", "c").orElseThrow().state()).isEqualTo("completed");
        }

        @Test
        @DisplayName("Cleaning one run leaves another run's reason alone")
        void cleanupRunIsScopedToItsRun() {
            // cleanupRun runs on every rerun / refire / cancel. Wiping the whole cache instead of
            // this run's entries would drop a refusal another run recorded a moment earlier, and
            // that caller would silently get the generic sentence. Replacing the prefix scan with
            // invalidateAll() left every other test green.
            registry.refuse("run-a", ready("mcp:b"), "mcp:a");
            registry.refuse("run-b", ready("mcp:b"), "mcp:a");

            registry.cleanupRun("run-a");

            assertThat(registry.lastRefusal("run-a", "mcp:a")).isEmpty();
            assertThat(registry.lastRefusal("run-b", "mcp:a"))
                    .as("run-b's refusal has nothing to do with run-a being rerun")
                    .isPresent();
        }

        @Test
        @DisplayName("cleanupRun is idempotent, as the cache contract requires")
        void cleanupRunIsIdempotent() {
            registry.cleanupRun(RUN_ID);
            registry.cleanupRun(RUN_ID);

            assertThat(registry.getCacheSize()).isZero();
        }

        @Test
        @DisplayName("Entries are capped, because nothing clears a run that simply ends")
        void entriesAreBoundedWithoutAnyCleanup() {
            // RunCacheRegistry.cleanupRun is only reached on rerun / refire / cancel, NOT when a
            // run ends normally. The ordinary close of a step-by-step session is a refused click
            // followed by the tab closing, and that entry is never explicitly dropped: the size
            // cap is what stops it accumulating.
            StateSnapshot snapshot = ready("mcp:b");
            for (int i = 0; i < ClaimRefusalRegistry.MAX_SIZE * 2; i++) {
                registry.refuse("run-" + i, snapshot, "mcp:a");
            }

            assertThat(registry.getCacheSize())
                    .as("bounded by the cap, not by the number of runs ever refused")
                    .isLessThanOrEqualTo((int) ClaimRefusalRegistry.MAX_SIZE);
        }

        @Test
        @DisplayName("An entry expires on its own, so a quiet instance holds nothing")
        void entriesExpireWithoutAnyCleanup() {
            // The other half of the bound. A refusal is read in the same request that caused it,
            // so nothing should still be holding one two minutes later.
            AtomicLong nanos = new AtomicLong();
            var timed = new ClaimRefusalRegistry(runningNodeTracker, nanos::get);
            timed.refuse(RUN_ID, ready("mcp:b"), "mcp:a");
            assertThat(timed.lastRefusal(RUN_ID, "mcp:a")).isPresent();

            nanos.addAndGet(ClaimRefusalRegistry.TTL.plusSeconds(1).toNanos());

            assertThat(timed.lastRefusal(RUN_ID, "mcp:a")).isEmpty();
            assertThat(timed.getCacheSize()).isZero();
        }
    }

    @Nested
    @DisplayName("The 409 body")
    class ResponseBody {

        @Test
        @DisplayName("A known reason fills message, nodeState and readyNow, and is returned for the log")
        void knownReasonFillsTheBody() {
            registry.refuse(RUN_ID, ready("core:body"), "core:loop");
            Map<String, Object> body = new LinkedHashMap<>();

            String message = ClaimRefusalRegistry.describeInto(
                    body, registry.lastRefusal(RUN_ID, "core:loop"), "core:loop");

            assertThat(body).containsEntry("nodeState", "unknown");
            assertThat(body).containsEntry("readyNow", new java.util.TreeSet<>(Set.of("core:body")));
            assertThat(body.get("message"))
                    .as("the body and the log line must be the same sentence")
                    .isEqualTo(message);
            assertThat(message).contains("core:loop", "not runnable right now");
        }

        @Test
        @DisplayName("With no recorded reason the fallback still names the node")
        void fallbackNamesTheNode() {
            Map<String, Object> body = new LinkedHashMap<>();

            String message = ClaimRefusalRegistry.describeInto(body, Optional.empty(), "mcp:a");

            assertThat(message).contains("mcp:a").contains("not in READY state");
            assertThat(body).doesNotContainKeys("nodeState", "readyNow");
        }
    }
}
