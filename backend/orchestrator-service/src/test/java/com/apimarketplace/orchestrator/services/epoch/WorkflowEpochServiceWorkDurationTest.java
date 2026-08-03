package com.apimarketplace.orchestrator.services.epoch;

import com.apimarketplace.orchestrator.persistence.EpochWorkWindowProjection;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochTimestampRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How long an execution took is answered from the step rows, never from the epoch
 * header.
 *
 * <p>The header's {@code duration_ms} is stamped at CLOSE time as
 * {@code now - startedAt}, and an epoch closes only when it is reconciled - the next
 * fire, a resume, a restart recovery sweep - so it measures the epoch's LIFETIME.
 * Prod displayed 6h01m and 32h42m for a workflow whose epochs execute in 5 to 35
 * seconds. Both the run-history column and the epoch timeline used to read it, which
 * is why both are covered here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEpochService - execution durations come from the step rows")
class WorkflowEpochServiceWorkDurationTest {

    @Mock private WorkflowEpochRepository repository;
    @Mock private WorkflowStepDataRepository stepDataRepository;

    private WorkflowEpochService service;

    private static final Instant T0 = Instant.parse("2026-08-02T12:00:00Z");

    @BeforeEach
    void setUp() {
        service = new WorkflowEpochService(repository, new ObjectMapper(), stepDataRepository);
    }

    private static EpochWorkWindowProjection window(String runId, int epoch, long fromSec, long toSec) {
        return new EpochWorkWindowProjection(runId, epoch, T0.plusSeconds(fromSec), T0.plusSeconds(toSec));
    }

    @Test
    @DisplayName("The run-history duration is the step window, keyed by public run id")
    void latestDurationComesFromStepRows() {
        when(stepDataRepository.findEpochWorkWindows(anyCollection()))
                .thenReturn(List.of(window("run-A", 49, 0, 12), window("run-B", 3, 0, 90)));

        assertThat(service.getLatestEpochWorkDurationByRunIds(List.of("run-A", "run-B")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("run-A", 12_000L, "run-B", 90_000L));
    }

    @Test
    @DisplayName("The LATEST epoch wins per run, whatever order the rows arrive in")
    void latestEpochWinsRegardlessOfRowOrder() {
        // The query returns every epoch; picking the right one is this method's job.
        // The rows are deliberately out of order, and the longest epoch is not the
        // latest - a max-by-duration or first-row-wins bug would pass otherwise.
        when(stepDataRepository.findEpochWorkWindows(anyCollection())).thenReturn(List.of(
                window("run-A", 2, 0, 120),
                window("run-A", 5, 600, 604),
                window("run-A", 1, 0, 300),
                window("run-B", 7, 0, 9),
                window("run-B", 3, 0, 900)));

        assertThat(service.getLatestEpochWorkDurationByRunIds(List.of("run-A", "run-B")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("run-A", 4_000L, "run-B", 9_000L));
    }

    @Test
    @DisplayName("A latest epoch present but unmeasurable leaves the run blank, it does not fall back")
    void unmeasurableLatestEpochDoesNotFallBackToAnOlderOne() {
        // Epoch 5 is present in the aggregate but its window cannot be computed.
        // Reporting epoch 2's 120s under the label "last execution duration" would be a
        // plausible-looking lie that nothing in the UI could reveal.
        //
        // Defensive: today's aggregate cannot emit a group whose MAX(end_time) is null,
        // because every writer stamps end_time. The REACHABLE shape is the next test.
        when(stepDataRepository.findEpochWorkWindows(anyCollection())).thenReturn(List.of(
                window("run-A", 2, 0, 120),
                new EpochWorkWindowProjection("run-A", 5, T0.plusSeconds(600), null)));

        assertThat(service.getLatestEpochWorkDurationByRunIds(List.of("run-A"))).isEmpty();
    }

    @Test
    @DisplayName("An epoch that has written no step row is invisible, so the previous epoch answers")
    void epochWithoutAnyStepRowIsInvisibleToTheAggregate() {
        // The reachable version of the case above, pinned because it is a real limit of
        // the measure rather than a guarantee: a just-fired epoch has no step row at
        // all, so it produces NO group and the query cannot know it exists. The latest
        // MEASURABLE epoch answers, and the run history shows the previous execution
        // until the new epoch's first node completes.
        //
        // That is the honest reading of "last execution duration" while a run is
        // starting, and it is strictly better than what this replaced: the old column
        // read the epoch header, whose duration counted idle time (32h42m on prod).
        when(stepDataRepository.findEpochWorkWindows(anyCollection()))
                .thenReturn(List.of(window("run-A", 2, 0, 120)));

        assertThat(service.getLatestEpochWorkDurationByRunIds(List.of("run-A")))
                .containsEntry("run-A", 120_000L);
    }

    @Test
    @DisplayName("A window with no measurable span is dropped rather than reported as zero")
    void unmeasurableWindowIsDropped() {
        // A null endpoint means the aggregate found nothing to close the window with.
        // Zero would read as "instant", which is the opposite of "unknown".
        when(stepDataRepository.findEpochWorkWindows(anyCollection()))
                .thenReturn(List.of(new EpochWorkWindowProjection("run-A", 1, T0, null)));

        assertThat(service.getLatestEpochWorkDurationByRunIds(List.of("run-A"))).isEmpty();
    }

    @Test
    @DisplayName("A negative window is dropped: no figure beats a wrong one")
    void negativeWindowIsDropped() {
        // Rows that disagree, or clock skew between writers. Rendering "-2s" would read
        // as a rendering bug; an empty cell reads as "not measured".
        when(stepDataRepository.findEpochWorkWindows(anyCollection()))
                .thenReturn(List.of(new EpochWorkWindowProjection(
                        "run-A", 1, T0.plusSeconds(12), T0.plusSeconds(10))));

        assertThat(service.getLatestEpochWorkDurationByRunIds(List.of("run-A"))).isEmpty();
        assertThat(service.getEpochWorkDurations("run-A")).isEmpty();
    }

    @Test
    @DisplayName("An empty run list never reaches the database")
    void emptyRunListShortCircuits() {
        assertThat(service.getLatestEpochWorkDurationByRunIds(List.of())).isEmpty();
        assertThat(service.getLatestEpochWorkDurationByRunIds(null)).isEmpty();
        // JPQL rejects an empty IN collection, so the short-circuit is required, not
        // merely an optimisation.
        verify(stepDataRepository, never()).findEpochWorkWindows(anyCollection());
    }

    @Test
    @DisplayName("Each timeline epoch carries its own executed window, not the header span")
    void timelineRowsCarryTheirWorkWindow() {
        // The header span here is ~33 hours (the deferred close); the work windows are
        // seconds. Asserting both on the same rows is what pins the distinction.
        when(repository.listEpochTimestamps(anyString())).thenReturn(List.of(
                new EpochTimestampRow(1, "2026-08-01T00:00:00Z", "2026-08-02T08:42:00Z"),
                new EpochTimestampRow(2, "2026-08-02T09:00:00Z", "2026-08-02T09:00:30Z")));
        when(stepDataRepository.findEpochWorkWindows(java.util.List.of("run-A")))
                .thenReturn(List.of(window("run-A", 1, 0, 5), window("run-A", 2, 0, 30)));

        List<EpochTimestampRow> rows = service.listEpochTimestamps("run-A");

        assertThat(rows).extracting(EpochTimestampRow::epoch, EpochTimestampRow::workDurationMs)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 5_000L),
                        org.assertj.core.groups.Tuple.tuple(2, 30_000L));
        // The timestamps themselves are untouched: the timeline still positions epochs
        // by them, and a null endedAt is still how the UI spots an open epoch.
        assertThat(rows.get(0).endedAt()).isEqualTo("2026-08-02T08:42:00Z");
    }

    @Test
    @DisplayName("An epoch that has produced no step row yet gets a null duration, not a wrong one")
    void epochWithoutStepRowsHasNullDuration() {
        when(repository.listEpochTimestamps(anyString())).thenReturn(List.of(
                new EpochTimestampRow(1, "2026-08-02T09:00:00Z", null)));
        when(stepDataRepository.findEpochWorkWindows(java.util.List.of("run-A"))).thenReturn(List.of());

        assertThat(service.listEpochTimestamps("run-A").get(0).workDurationMs()).isNull();
    }

    @Test
    @DisplayName("The wire shape carries the executed window to every client")
    void wireShapeCarriesWorkDuration() {
        // The public app and the showcase snapshot each used to hand-build this map and
        // each omitted workDurationMs, so those clients silently kept computing the
        // duration from the header span. One shared shape is what stops that recurring.
        Map<String, Object> wire =
                new EpochTimestampRow(4, "2026-08-02T09:00:00Z", "2026-08-02T09:00:30Z", 12_000L).toWireMap();

        assertThat(wire).containsExactlyInAnyOrderEntriesOf(Map.of(
                "epoch", 4,
                "startedAt", "2026-08-02T09:00:00Z",
                "endedAt", "2026-08-02T09:00:30Z",
                "workDurationMs", 12_000L));
    }

    @Test
    @DisplayName("Renumbering an epoch for a showcase keeps its measured window")
    void renumberingKeepsTheWorkDuration() {
        // A single-epoch publication is renumbered to 1. Only the NUMBER is meant to
        // change; the previous reflective rebuild dropped everything it forgot to copy.
        EpochTimestampRow renumbered =
                new EpochTimestampRow(37, "2026-08-02T09:00:00Z", null, 12_000L).withEpoch(1);

        assertThat(renumbered.epoch()).isEqualTo(1);
        assertThat(renumbered.workDurationMs()).isEqualTo(12_000L);
        assertThat(renumbered.startedAt()).isEqualTo("2026-08-02T09:00:00Z");
        assertThat(renumbered.endedAt()).isNull();
    }

    @Test
    @DisplayName("A run with no epochs does not query the step rows at all")
    void noEpochsSkipsTheStepQuery() {
        when(repository.listEpochTimestamps(anyString())).thenReturn(List.of());

        assertThat(service.listEpochTimestamps("run-A")).isEmpty();
        verify(stepDataRepository, never()).findEpochWorkWindows(anyCollection());
    }
}
