package com.apimarketplace.orchestrator.domain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the read-time removal of the phantom {@code "trigger:default"} DAG.
 *
 * <p>Runs created before {@code StateSnapshotService.initializeReadyTriggerDags} were armed
 * through the FLAT ready-node write while {@code dags} was still empty, so
 * {@code getDefaultTriggerId()} returned the sentinel and the real trigger's ready marker was
 * stored under {@code "trigger:default"}. Once the first fire created the trigger's real DAG,
 * the SAME node id existed in two dags, and {@link StateSnapshot#findDagContaining(String)}
 * picks between them by {@code Map.copyOf} iteration order - randomised per JVM by a
 * {@code System.nanoTime()}-seeded salt. Behaviour was bimodal per process and constant
 * within it, which is exactly what made the rerun/trigger-cycle failures read as suite
 * flakiness rather than as a defect.
 *
 * <p>The removal is deliberately narrow, so these tests cover both directions: the phantom
 * goes, and anything that merely resembles it stays.
 */
@DisplayName("StateSnapshot phantom trigger:default DAG removal")
class StateSnapshotPhantomSentinelDagTest {

    private static final String SENTINEL = StateSnapshot.DEFAULT_TRIGGER_SENTINEL;

    private static EpochState epoch(Set<String> completed, Set<String> ready) {
        return new EpochState(completed, Set.of(), Set.of(), Set.of(), Set.of(), ready, Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.now());
    }

    /** The exact shape the pre-fix birth write left behind: never fired, ready marker only. */
    private static DagState phantomSentinel(String... readyTriggerIds) {
        return new DagState(0, 0, 0, Map.of(0, epoch(Set.of(), Set.of(readyTriggerIds))), Set.of());
    }

    private static DagState realDag(int currentEpoch, Set<String> completed, Set<String> ready) {
        return new DagState(currentEpoch, 0, currentEpoch + 1,
                Map.of(currentEpoch, epoch(completed, ready)), Set.of(currentEpoch));
    }

    private static StateSnapshot snapshotOf(Map<String, DagState> dags) {
        return new StateSnapshot(3, 0L, dags,
                null, null, null, null, null, Map.of(), Map.of(),
                null, null, null, null, 0L, Instant.now());
    }

    @Test
    @DisplayName("The phantom sentinel is dropped once the real trigger DAG exists")
    void phantomSentinelDroppedWhenRealDagExists() {
        StateSnapshot snapshot = snapshotOf(Map.of(
                "trigger:start", realDag(1, Set.of("core:stamp"), Set.of("trigger:start")),
                SENTINEL, phantomSentinel("trigger:start")));

        assertFalse(snapshot.getDags().containsKey(SENTINEL),
                "the birth-write leftover must not survive: it makes findDagContaining ambiguous");
        assertEquals(Set.of("trigger:start"), snapshot.getDags().keySet());
    }

    @Test
    @DisplayName("Dropping the phantom makes findDagContaining resolve the trigger to its real DAG")
    void findDagContainingResolvesToRealDag() {
        // Ten shuffled keys so a surviving sentinel would win the iteration order in some JVMs.
        Map<String, DagState> dags = new HashMap<>();
        dags.put("trigger:start", realDag(1, Set.of(), Set.of("trigger:start")));
        for (int i = 0; i < 8; i++) {
            dags.put("trigger:filler" + i, realDag(1, Set.of(), Set.of("trigger:filler" + i)));
        }
        dags.put(SENTINEL, phantomSentinel("trigger:start"));

        StateSnapshot snapshot = snapshotOf(dags);

        assertEquals("trigger:start", snapshot.findDagContaining("trigger:start"),
                "with the phantom gone there is exactly one DAG holding the trigger, so the "
                        + "per-JVM map-order coin flip cannot decide the answer any more");
    }

    @Test
    @DisplayName("The trigger stays ready in the flat view after the phantom is dropped")
    void flatReadyViewSurvivesTheDrop() {
        StateSnapshot snapshot = snapshotOf(Map.of(
                "trigger:start", realDag(1, Set.of(), Set.of("trigger:start")),
                SENTINEL, phantomSentinel("trigger:start")));

        assertTrue(snapshot.getReadyNodeIds().contains("trigger:start"),
                "the real DAG carries the ready marker; dropping the duplicate must not blank "
                        + "readyStepIds, which is what the birth write exists to populate");
    }

    @Test
    @DisplayName("A legacy V2-migrated run whose only DAG is the sentinel is left untouched")
    void legacySentinelOnlyRunUntouched() {
        StateSnapshot snapshot = snapshotOf(Map.of(
                SENTINEL, new DagState(0, 0, 0,
                        Map.of(0, epoch(Set.of("mcp:step1"), Set.of("mcp:step2"))), Set.of(0))));

        assertTrue(snapshot.getDags().containsKey(SENTINEL),
                "there is no real DAG to migrate to: dropping this would erase the whole run state");
        assertTrue(snapshot.getCompletedNodeIds().contains("mcp:step1"));
    }

    @Test
    @DisplayName("A sentinel carrying executed nodes is kept even when a real DAG exists")
    void sentinelWithRealWorkIsKept() {
        StateSnapshot snapshot = snapshotOf(Map.of(
                "trigger:start", realDag(1, Set.of("core:stamp"), Set.of()),
                SENTINEL, new DagState(0, 0, 0,
                        Map.of(0, epoch(Set.of("mcp:legacy"), Set.of())), Set.of())));

        assertTrue(snapshot.getDags().containsKey(SENTINEL),
                "completed nodes on the sentinel are execution history, not a birth-write artifact");
        assertTrue(snapshot.getCompletedNodeIds().contains("mcp:legacy"));
    }

    @Test
    @DisplayName("A sentinel holding a ready node that is not a DAG key is kept")
    void sentinelWithUnknownReadyNodeIsKept() {
        StateSnapshot snapshot = snapshotOf(Map.of(
                "trigger:start", realDag(1, Set.of(), Set.of()),
                SENTINEL, phantomSentinel("core:stamp")));

        assertTrue(snapshot.getDags().containsKey(SENTINEL),
                "core:stamp names no DAG, so this marker is not a duplicate of anything - "
                        + "dropping it would silently lose a ready node");
        assertTrue(snapshot.getReadyNodeIds().contains("core:stamp"));
    }

    @Test
    @DisplayName("A trigger literally labelled Default keeps its own DAG BEFORE its first fire")
    void triggerLabelledDefaultKeepsItsDagBeforeFiring() {
        // LabelNormalizer.triggerKey("Default") is "trigger:default", so this trigger's own
        // legitimate DAG is bit-for-bit the phantom signature: never fired, one empty epoch,
        // ready set holding one id. fireCount does NOT separate them - the birth write runs
        // precisely in the window before any fire. What separates them is that a phantom's
        // markers name OTHER dags while this one names itself.
        StateSnapshot snapshot = snapshotOf(Map.of(
                "trigger:webhook", phantomSentinel("trigger:webhook"),
                SENTINEL, phantomSentinel(SENTINEL)));

        assertTrue(snapshot.getDags().containsKey(SENTINEL),
                "dropping this erases a real armed trigger, and it only comes back when that "
                        + "trigger fires - which for a schedule can be hours later");
        assertTrue(snapshot.getReadyNodeIds().contains(SENTINEL),
                "the trigger must stay visible in readyStepIds while it waits");
    }

    @Test
    @DisplayName("A sentinel that has fired is kept: it is a real DAG for a trigger labelled Default")
    void firedSentinelIsKept() {
        DagState firedSentinel = new DagState(0, 0, 1,
                Map.of(0, epoch(Set.of(), Set.of("trigger:start"))), Set.of());
        StateSnapshot snapshot = snapshotOf(Map.of(
                "trigger:start", realDag(1, Set.of(), Set.of("trigger:start")),
                SENTINEL, firedSentinel));

        assertTrue(snapshot.getDags().containsKey(SENTINEL),
                "a trigger literally labelled \"Default\" normalizes to trigger:default and does "
                        + "receive fires; fireCount > 0 is what tells the two apart");
    }

    @Test
    @DisplayName("A multi-trigger run keeps the phantom until every named trigger DAG exists")
    void multiTriggerPhantomKeptUntilAllDagsExist() {
        StateSnapshot partial = snapshotOf(Map.of(
                "trigger:a", realDag(1, Set.of(), Set.of("trigger:a")),
                SENTINEL, phantomSentinel("trigger:a", "trigger:b")));

        assertTrue(partial.getDags().containsKey(SENTINEL),
                "trigger:b has not fired yet, so its ready marker only exists on the sentinel");
        assertTrue(partial.getReadyNodeIds().contains("trigger:b"));

        StateSnapshot complete = snapshotOf(Map.of(
                "trigger:a", realDag(1, Set.of(), Set.of("trigger:a")),
                "trigger:b", realDag(1, Set.of(), Set.of("trigger:b")),
                SENTINEL, phantomSentinel("trigger:a", "trigger:b")));

        assertFalse(complete.getDags().containsKey(SENTINEL),
                "both markers are now duplicated by real DAGs, so the phantom carries nothing");
        assertTrue(complete.getReadyNodeIds().containsAll(Set.of("trigger:a", "trigger:b")));
    }

    @Test
    @DisplayName("The removal survives a JSON round-trip, which is where it actually runs")
    void removalAppliesOnDeserialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        // Built by hand: a snapshot serialized by the PRE-fix code, as stored in
        // workflow_runs.state_snapshot for a run already in flight.
        String json = "{\"version\":3,\"seq\":4,\"dags\":{"
                + "\"trigger:start\":{\"currentEpoch\":1,\"currentSpawn\":0,\"fireCount\":1,"
                + "\"epochs\":{\"1\":{\"completedNodeIds\":[\"core:stamp\"],\"failedNodeIds\":[],"
                + "\"partialFailedNodeIds\":[],\"skippedNodeIds\":[],\"runningNodeIds\":[],"
                + "\"readyNodeIds\":[\"trigger:start\"],\"awaitingSignalNodeIds\":[],"
                + "\"decisionBranches\":{},\"loops\":{},\"splits\":{}}},\"activeEpochs\":[1]},"
                + "\"trigger:default\":{\"currentEpoch\":0,\"currentSpawn\":0,\"fireCount\":0,"
                + "\"epochs\":{\"0\":{\"completedNodeIds\":[],\"failedNodeIds\":[],"
                + "\"partialFailedNodeIds\":[],\"skippedNodeIds\":[],\"runningNodeIds\":[],"
                + "\"readyNodeIds\":[\"trigger:start\"],\"awaitingSignalNodeIds\":[],"
                + "\"decisionBranches\":{},\"loops\":{},\"splits\":{}}},\"activeEpochs\":[]}},"
                + "\"nodes\":{},\"edges\":{},\"totalDurationMs\":0}";

        StateSnapshot parsed = mapper.readValue(json, StateSnapshot.class);

        assertFalse(parsed.getDags().containsKey(SENTINEL),
                "an in-flight run must be healed when its snapshot is read, without a Flyway "
                        + "migration or raw SQL on state_snapshot");
        assertEquals("trigger:start", parsed.findDagContaining("trigger:start"));
        assertTrue(parsed.getCompletedNodeIds().contains("core:stamp"));
    }
}
