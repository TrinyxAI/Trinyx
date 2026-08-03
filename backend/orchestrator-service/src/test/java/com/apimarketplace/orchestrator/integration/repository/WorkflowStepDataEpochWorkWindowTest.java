package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.EpochWorkWindowProjection;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run history's "last execution duration" and the epoch timeline's per-epoch
 * duration are both measured here, by executing the query against a real database.
 *
 * <p>The figure used to be read from {@code workflow_epochs.duration_ms}, which is
 * stamped at CLOSE time as {@code now - startedAt}. An epoch closes only when it is
 * reconciled - the next fire, a resume, a restart recovery sweep - so the column
 * measured the epoch's LIFETIME: prod showed 6h01m and 32h42m for a workflow whose
 * step rows span 5 to 35 seconds per epoch.
 *
 * <p>These tests run on H2 rather than mocking the query, for two reasons. The
 * previous attempt at this fix shipped Postgres-only {@code DISTINCT ON} syntax that
 * H2 rejects (90068) - a mocked test asserting on the SQL string cannot catch that.
 * And the behaviour that matters (which epoch wins, how rows collapse per run) lives
 * in the query, not in the Java around it.
 */
@DataJpaIntegrationTest
@DisplayName("Epoch work windows are measured from the step rows")
class WorkflowStepDataEpochWorkWindowTest {

    @Autowired private WorkflowStepDataRepository repository;
    @Autowired private TestEntityManager entityManager;

    private static final Instant T0 = Instant.parse("2026-08-02T12:00:00Z");

    /** Persists one completed step row. Offsets are seconds from {@link #T0}. */
    private void step(String runId, int epoch, String alias, long startOffsetSec, long endOffsetSec) {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
        entity.setWorkflowRunId(UUID.nameUUIDFromBytes(runId.getBytes()));
        entity.setRunId(runId);
        entity.setStepAlias(alias);
        entity.setToolId(alias);
        entity.setStatus("COMPLETED");
        entity.setTenantId("tenant-1");
        entity.setOrganizationId("org-1");
        entity.setEpoch(epoch);
        entity.setStartTime(T0.plusSeconds(startOffsetSec));
        entity.setEndTime(T0.plusSeconds(endOffsetSec));
        entityManager.persist(entity);
    }

    /**
     * The figure the run history shows, produced by the PRODUCTION code path against
     * the seeded rows. Re-implementing the latest-epoch selection here would let a
     * regression in it pass unnoticed, which is most of what these tests exist for.
     * The epoch repository is unused by this method, hence null.
     */
    private Map<String, Long> latestDurations(String... runIds) {
        entityManager.flush();
        return new WorkflowEpochService(null, new ObjectMapper(), repository)
                .getLatestEpochWorkDurationByRunIds(List.of(runIds));
    }

    @Test
    @DisplayName("The window spans the whole epoch: first node starting to last node finishing")
    void spansFirstStartToLastEnd() {
        step("run-A", 1, "fetch", 0, 3);
        step("run-A", 1, "transform", 3, 5);
        step("run-A", 1, "notify", 5, 12);

        assertThat(latestDurations("run-A")).containsEntry("run-A", 12_000L);
    }

    @Test
    @DisplayName("Only the LATEST epoch counts: an older, longer epoch does not win")
    void olderLongerEpochLoses() {
        // This is the whole point of the query. Epoch 1 took two minutes, epoch 2
        // took four seconds; the column must say four seconds.
        step("run-A", 1, "slow", 0, 120);
        step("run-A", 2, "fast", 600, 604);

        assertThat(latestDurations("run-A")).containsEntry("run-A", 4_000L);
    }

    @Test
    @DisplayName("Each run gets its own window: rows never leak between runs")
    void runsAreIsolated() {
        // A single-row result would satisfy a handler that overwrote the map key or
        // read the wrong column, so measure several runs at once with distinct spans.
        step("run-A", 3, "a", 0, 7);
        step("run-B", 1, "b", 100, 110);
        step("run-C", 9, "c", 50, 51);

        assertThat(latestDurations("run-A", "run-B", "run-C"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "run-A", 7_000L,
                        "run-B", 10_000L,
                        "run-C", 1_000L));
    }

    @Test
    @DisplayName("A run not asked for is not returned")
    void unrequestedRunsExcluded() {
        step("run-A", 1, "a", 0, 7);
        step("run-B", 1, "b", 0, 90);

        assertThat(latestDurations("run-A")).containsOnlyKeys("run-A");
    }

    @Test
    @DisplayName("A run with no step rows is absent, so the caller renders nothing rather than a zero")
    void runWithoutStepsIsAbsent() {
        step("run-A", 1, "a", 0, 7);

        assertThat(latestDurations("run-A", "run-never-executed")).doesNotContainKey("run-never-executed");
    }

    @Test
    @DisplayName("An epoch whose nodes were all instantaneous reports zero, not nothing")
    void zeroSpanIsAValidAnswer() {
        // All-skipped epochs really do produce start == end. Zero is the truth here;
        // dropping it would leave the cell blank and read as "never ran".
        step("run-A", 1, "skipped-one", 4, 4);
        step("run-A", 1, "skipped-two", 4, 4);

        assertThat(latestDurations("run-A")).containsEntry("run-A", 0L);
    }

    @Test
    @DisplayName("An in-flight epoch reports the window closed so far")
    void inFlightEpochReportsPartialWindow() {
        // The first two nodes are done, a third is still executing (no row yet).
        // Reporting the settled part is better than reporting the previous epoch:
        // the run history's status column already says the run is live.
        step("run-A", 1, "done", 0, 30);
        step("run-A", 2, "first", 100, 104);
        step("run-A", 2, "second", 104, 109);

        assertThat(latestDurations("run-A")).containsEntry("run-A", 9_000L);
    }

    @Test
    @DisplayName("A row missing its end timestamp does not corrupt the window")
    void rowWithoutEndTimeIsIgnoredByTheAggregate() {
        // Defensive: no writer leaves end_time null today (rows are written at
        // completion), but MAX() skipping nulls is what keeps a future one honest.
        step("run-A", 1, "done", 0, 6);
        WorkflowStepDataEntity running = new WorkflowStepDataEntity();
        running.setWorkflowRunId(UUID.nameUUIDFromBytes("run-A".getBytes()));
        running.setRunId("run-A");
        running.setStepAlias("still-running");
        running.setToolId("still-running");
        running.setStatus("RUNNING");
        running.setTenantId("tenant-1");
        running.setOrganizationId("org-1");
        running.setEpoch(1);
        running.setStartTime(T0.plusSeconds(6));
        running.setEndTime(null);
        entityManager.persist(running);

        assertThat(latestDurations("run-A")).containsEntry("run-A", 6_000L);
    }

    @Test
    @DisplayName("Per-run listing returns one window for EVERY epoch, for the timeline")
    void perRunListingCoversEveryEpoch() {
        step("run-A", 1, "a", 0, 5);
        step("run-A", 2, "b", 100, 130);
        step("run-A", 3, "c", 200, 201);
        step("run-B", 1, "other", 0, 999);
        entityManager.flush();

        Map<Integer, Long> byEpoch = repository.findEpochWorkWindows(List.of("run-A")).stream()
                .collect(Collectors.toMap(EpochWorkWindowProjection::epoch, EpochWorkWindowProjection::durationMs));

        assertThat(byEpoch).containsExactlyInAnyOrderEntriesOf(Map.of(
                1, 5_000L,
                2, 30_000L,
                3, 1_000L));
    }

    @Test
    @DisplayName("A wait INSIDE the graph is counted, because the waiting node's row spans it")
    void inGraphWaitIsCounted() {
        // An approval's step row is written with startTime = when the signal was
        // created and endTime = when it resolved, so a workflow that genuinely sat on
        // an approval for five minutes reports five minutes. That is the truth, not an
        // artefact: what this change excludes is only the idle tail AFTER the last node
        // finished. On the reported run the longest such row was 301.9s while the epoch
        // header claimed 32h42m.
        step("run-A", 1, "fetch", 0, 2);
        step("run-A", 1, "approve_delete", 2, 302);

        assertThat(latestDurations("run-A")).containsEntry("run-A", 302_000L);
    }

    @Test
    @DisplayName("Every projection carries the epoch it measured")
    void projectionCarriesItsEpoch() {
        // Without this the caller cannot key the timeline, and a silent epoch mix-up
        // would look like a plausible duration on the wrong row.
        step("run-A", 1, "a", 0, 5);
        step("run-A", 7, "b", 100, 130);
        entityManager.flush();

        Map<Integer, EpochWorkWindowProjection> windows = repository.findEpochWorkWindows(List.of("run-A")).stream()
                .collect(Collectors.toMap(EpochWorkWindowProjection::epoch, Function.identity()));

        assertThat(windows.keySet()).containsExactlyInAnyOrder(1, 7);
        assertThat(windows.get(7).firstStart()).isEqualTo(T0.plusSeconds(100));
        assertThat(windows.get(7).lastEnd()).isEqualTo(T0.plusSeconds(130));
    }
}
