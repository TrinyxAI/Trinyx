package com.apimarketplace.publication.service;

import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PublicationSlugBackfillRunner}.
 *
 * <p>The runner drains "rows without a slug" by repeatedly re-querying from
 * offset 0, because rows leave the result set as they are filled. That shape is
 * efficient but has one failure mode worth pinning hard: a row that can never be
 * slugged would be selected forever. These tests exist mostly to prove the loop
 * always terminates.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationSlugBackfillRunner: idempotent, always-terminating backfill")
class PublicationSlugBackfillRunnerTest {

    @Mock private WorkflowPublicationRepository repository;
    @Mock private PublicationSlugBackfillWorker worker;

    private PublicationSlugBackfillRunner runner() {
        return new PublicationSlugBackfillRunner(repository, worker);
    }

    @Test
    @DisplayName("Nothing to do: no rows are touched")
    void noRowsWithoutSlugIsANoOp() {
        when(repository.findIdsWithoutPublicSlug(any(Pageable.class))).thenReturn(List.of());

        runner().run(null);

        verify(worker, never()).assignOne(any());
        verify(repository, times(1)).findIdsWithoutPublicSlug(any(Pageable.class));
    }

    @Test
    @DisplayName("Each pending row is handed to the worker, then the drain stops")
    void assignsEveryPendingRow() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findIdsWithoutPublicSlug(any(Pageable.class)))
                .thenReturn(List.of(a, b))
                .thenReturn(List.of());
        when(worker.assignOne(any())).thenReturn(true);

        runner().run(null);

        verify(worker).assignOne(a);
        verify(worker).assignOne(b);
        verify(repository, times(2)).findIdsWithoutPublicSlug(any(Pageable.class));
    }

    @Test
    @DisplayName("A row that keeps failing stops the drain instead of looping forever")
    void stallingBatchTerminatesTheLoop() {
        UUID stuck = UUID.randomUUID();
        // The repository keeps returning the same row because it never gets a
        // slug. Without the no-progress guard this drains until MAX_BATCHES,
        // hammering the DB on every boot.
        when(repository.findIdsWithoutPublicSlug(any(Pageable.class))).thenReturn(List.of(stuck));
        when(worker.assignOne(stuck)).thenReturn(false);

        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();

        verify(worker, times(1)).assignOne(stuck);
        verify(repository, times(1)).findIdsWithoutPublicSlug(any(Pageable.class));
    }

    @Test
    @DisplayName("A row that throws does not abort the batch or the run")
    void oneFailingRowDoesNotAbortTheRun() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(repository.findIdsWithoutPublicSlug(any(Pageable.class)))
                .thenReturn(List.of(failing, healthy))
                .thenReturn(List.of());
        when(worker.assignOne(failing)).thenThrow(new RuntimeException("unique index race"));
        when(worker.assignOne(healthy)).thenReturn(true);

        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();

        // A slug collision with a concurrent publish must not cost the rest of
        // the catalog its backfill.
        verify(worker).assignOne(healthy);
    }

    @Test
    @DisplayName("An all-throwing batch stops rather than spinning")
    void allThrowingBatchStops() {
        UUID failing = UUID.randomUUID();
        when(repository.findIdsWithoutPublicSlug(any(Pageable.class))).thenReturn(List.of(failing));
        when(worker.assignOne(failing)).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();

        // Throwing counts as no progress, same as returning false.
        verify(repository, times(1)).findIdsWithoutPublicSlug(any(Pageable.class));
    }

    @Test
    @DisplayName("The drain is bounded even if the repository never empties")
    void drainIsBounded() {
        // Pathological stub: every batch reports progress but the result set
        // never shrinks. The MAX_BATCHES ceiling is the last line of defence
        // against an unbounded boot.
        when(repository.findIdsWithoutPublicSlug(any(Pageable.class)))
                .thenReturn(List.of(UUID.randomUUID()));
        when(worker.assignOne(any())).thenReturn(true);

        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();

        verify(repository, atMost(500)).findIdsWithoutPublicSlug(any(Pageable.class));
    }

    @Test
    @DisplayName("Batches are requested from offset 0 (rows leave the set as they are filled)")
    void alwaysQueriesFirstPage() {
        when(repository.findIdsWithoutPublicSlug(any(Pageable.class)))
                .thenReturn(List.of(UUID.randomUUID()))
                .thenReturn(List.of());
        when(worker.assignOne(any())).thenReturn(true);

        runner().run(null);

        // Paging forward instead would skip rows: offset N no longer points at
        // the same rows once the earlier ones stopped matching the predicate.
        verify(repository, times(2)).findIdsWithoutPublicSlug(
                org.mockito.ArgumentMatchers.argThat(p -> p.getPageNumber() == 0));
    }
}
