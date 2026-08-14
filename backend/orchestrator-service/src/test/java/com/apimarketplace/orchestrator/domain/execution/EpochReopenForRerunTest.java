package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reopening the executed epoch when a rerun targets a run whose cycle already closed.
 *
 * <p>Shape under test, produced by an AUTO run that ran to quiescence: the executed epoch N
 * is closed and pruned, and {@code prepareNextCycle} leaves {@code currentEpoch} pointing at a
 * DORMANT epoch N+1 that holds nothing but the trigger in {@code readyNodeIds}. Every stored
 * output still belongs to N, and storage reads are epoch-filtered, so a rerun executed against
 * N+1 resolves every upstream template to empty while each node still reports success.
 */
@DisplayName("Epoch reopen for rerun (cycle already closed)")
class EpochReopenForRerunTest {

    private static final String TRIGGER_ID = "trigger:start";

    /** The dormant epoch a closed cycle stages for the next fire: trigger ready, nothing else. */
    private static EpochState preparedDormant() {
        return EpochState.fresh().addReadyNode(TRIGGER_ID);
    }

    private static EpochState executed() {
        return EpochState.fresh()
                .markNodeCompleted(TRIGGER_ID)
                .markNodeCompleted("mcp:step_a")
                .markNodeCompleted("mcp:step_b");
    }

    private static StateSnapshot snapshotOf(Map<String, DagState> dags) {
        return new StateSnapshot(3, 0L, dags,
                null, null, null, null, null,
                new HashMap<>(), new HashMap<>(),
                null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("DagState.reopenEpoch")
    class DagStateReopenEpoch {

        /** Cycle closed: epoch 5 executed and pruned, epoch 6 staged dormant. */
        private DagState afterClosedCycle() {
            return new DagState(6, 0, 3, Map.of(6, preparedDormant()), Set.of());
        }

        @Test
        @DisplayName("Moves currentEpoch BACKWARD to the executed epoch")
        void movesCurrentEpochBackward() {
            DagState reopened = afterClosedCycle().reopenEpoch(5);

            assertThat(reopened.getCurrentEpoch())
                    .as("the rerun must execute where the outputs live, not on the staged epoch")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("Marks the reopened epoch active so flat views include it")
        void marksReopenedEpochActive() {
            DagState reopened = afterClosedCycle().reopenEpoch(5);

            assertThat(reopened.getActiveEpochs()).containsExactly(5);
        }

        @Test
        @DisplayName("Leaves fireCount untouched: a rerun is not a trigger fire")
        void doesNotIncrementFireCount() {
            DagState before = afterClosedCycle();

            DagState reopened = before.reopenEpoch(5);

            assertThat(reopened.getFireCount()).isEqualTo(before.getFireCount());
        }

        @Test
        @DisplayName("openEpoch cannot serve this purpose: it counts a fire and never rewinds")
        void openEpochIsNotAnAlternative() {
            DagState before = afterClosedCycle();

            DagState opened = before.openEpoch(5);

            assertThat(opened.getFireCount())
                    .as("openEpoch counts a trigger fire, which a rerun is not")
                    .isEqualTo(before.getFireCount() + 1);
            assertThat(opened.getCurrentEpoch())
                    .as("openEpoch's Math.max pins currentEpoch to the dormant epoch")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("Preserves the executed state of an epoch still held in the snapshot")
        void preservesExistingEpochState() {
            DagState ds = new DagState(6, 0, 3,
                    Map.of(5, executed(), 6, preparedDormant()), Set.of());

            DagState reopened = ds.reopenEpoch(5);

            assertThat(reopened.getEpochState(5).getCompletedNodeIds())
                    .containsExactlyInAnyOrder(TRIGGER_ID, "mcp:step_a", "mcp:step_b");
        }

        @Test
        @DisplayName("Creates a fresh state when the epoch is absent (restore happens upstream)")
        void createsFreshStateWhenAbsent() {
            DagState reopened = afterClosedCycle().reopenEpoch(5);

            assertThat(reopened.getEpochState(5)).isNotNull();
            assertThat(reopened.getEpochState(5).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("StateSnapshot.reopenEpochForDag")
    class SnapshotReopenEpochForDag {

        @Test
        @DisplayName("Reopens the executed epoch as current and active")
        void reopensExecutedEpoch() {
            StateSnapshot snapshot = snapshotOf(Map.of(TRIGGER_ID,
                    new DagState(6, 0, 3, Map.of(5, executed(), 6, preparedDormant()), Set.of())));

            DagState dag = snapshot.reopenEpochForDag(TRIGGER_ID, 5).getDags().get(TRIGGER_ID);

            assertThat(dag.getCurrentEpoch()).isEqualTo(5);
            assertThat(dag.getActiveEpochs()).containsExactly(5);
        }

        @Test
        @DisplayName("Leaves the superseded dormant epoch alone rather than pruning it")
        void leavesSupersededDormantEpochAlone() {
            StateSnapshot snapshot = snapshotOf(Map.of(TRIGGER_ID,
                    new DagState(6, 0, 3, Map.of(5, executed(), 6, preparedDormant()), Set.of())));

            DagState dag = snapshot.reopenEpochForDag(TRIGGER_ID, 5).getDags().get(TRIGGER_ID);

            // Reopening changes which epoch is current and active, nothing else. The dormant
            // entry is unreachable once epoch 5 is active, and the next cycle close overwrites it.
            assertThat(dag.getEpochs()).containsOnlyKeys(5, 6);
            assertThat(dag.getEpochState(6).getReadyNodeIds()).containsExactly(TRIGGER_ID);
        }

        @Test
        @DisplayName("No-op when the target epoch is already current")
        void noOpWhenAlreadyCurrent() {
            StateSnapshot snapshot = snapshotOf(Map.of(TRIGGER_ID,
                    new DagState(5, 0, 3, Map.of(5, executed()), Set.of(5))));

            assertThat(snapshot.reopenEpochForDag(TRIGGER_ID, 5)).isSameAs(snapshot);
        }

        @Test
        @DisplayName("No-op for a trigger that has no DAG")
        void noOpForUnknownTrigger() {
            StateSnapshot snapshot = snapshotOf(Map.of(TRIGGER_ID,
                    new DagState(6, 0, 3, Map.of(6, preparedDormant()), Set.of())));

            assertThat(snapshot.reopenEpochForDag("trigger:other", 5)).isSameAs(snapshot);
        }

        @Test
        @DisplayName("The NEXT trigger fire after a rewind stages a fresh epoch and moves forward again")
        void nextCycleAfterARewindMovesForwardAgain() {
            // reopenEpochForDag's javadoc rests on this: the dormant epoch is left in place
            // because "the next cycle close overwrites it". Nothing pinned that, so a change to
            // prepareNextCycle could strand the rewound run one epoch behind forever.
            DagState afterRerun = snapshotOf(Map.of(TRIGGER_ID,
                    new DagState(6, 0, 3, Map.of(5, executed(), 6, preparedDormant()), Set.of())))
                    .reopenEpochForDag(TRIGGER_ID, 5)
                    .getDags().get(TRIGGER_ID);
            assertThat(afterRerun.getCurrentEpoch()).isEqualTo(5);

            // What the cycle close does once the rerun settles.
            DagState staged = afterRerun.closeAndPruneEpoch(5).prepareNextCycle(6, TRIGGER_ID);

            assertThat(staged.getCurrentEpoch()).as("the run moves forward again, not backward").isEqualTo(6);
            assertThat(staged.getActiveEpochs()).as("the staged epoch is dormant until the trigger fires").isEmpty();
            assertThat(staged.getEpochState(6).getReadyNodeIds()).containsExactly(TRIGGER_ID);
            assertThat(staged.getFireCount()).as("staging is not a fire").isEqualTo(3);
        }

        @Test
        @DisplayName("A second rerun on the rewound run behaves exactly like the first")
        void aSecondRerunIsIdempotent() {
            StateSnapshot once = snapshotOf(Map.of(TRIGGER_ID,
                    new DagState(6, 0, 3, Map.of(5, executed(), 6, preparedDormant()), Set.of())))
                    .reopenEpochForDag(TRIGGER_ID, 5);

            StateSnapshot twice = once.reopenEpochForDag(TRIGGER_ID, 5);

            assertThat(twice).as("epoch 5 is already current: nothing left to do").isSameAs(once);
        }

        @Test
        @DisplayName("Leaves sibling DAGs of a multi-trigger run untouched")
        void leavesSiblingDagsUntouched() {
            DagState sibling = new DagState(2, 0, 1, Map.of(2, executed()), Set.of());
            StateSnapshot snapshot = snapshotOf(Map.of(
                    TRIGGER_ID, new DagState(6, 0, 3, Map.of(6, preparedDormant()), Set.of()),
                    "trigger:other", sibling));

            StateSnapshot reopened = snapshot.reopenEpochForDag(TRIGGER_ID, 5);

            assertThat(reopened.getDags().get("trigger:other").getCurrentEpoch()).isEqualTo(2);
            assertThat(reopened.getDags().get("trigger:other").getActiveEpochs()).isEmpty();
        }
    }
}
