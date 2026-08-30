package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeInstallPingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single definition of "the fleet numbers", so a mistake here is a mistake on the dashboard AND
 * in the stats endpoint at once, reported identically by both.
 *
 * <p>What these tests are mostly about is the CUTOFFS. A window that is silently 45 days instead of
 * 30 produces a plausible number under a label that says 30d: nothing errors, nothing looks odd, and
 * the figure is simply wrong for as long as nobody re-derives it by hand. That is only catchable by
 * capturing the instants the repository is actually asked for.
 */
class CeFleetReaderTest {

    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");

    /** A ledger answering the one counts statement with {@code [total, a7, a30, n7, n30]}. */
    private static CeInstallPingRepository ledger(long... counts) {
        long[] row = counts.length == 5 ? counts : new long[]{0, 0, 0, 0, 0};
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.fleetCounts(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{row[0], row[1], row[2], row[3], row[4]}));
        when(repository.versionBreakdown(any(), anyInt())).thenReturn(List.of());
        return repository;
    }

    @Test
    @DisplayName("the windows are exactly 7 and 30 days back from the given instant, in that order")
    void windowsAreSevenAndThirtyDays() {
        CeInstallPingRepository repository = ledger();
        ArgumentCaptor<Instant> shortAgo = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> longAgo = ArgumentCaptor.forClass(Instant.class);

        new CeFleetReader(repository).read(NOW, 10);

        // Swap the two arguments and the statement puts the 30-day figure under the "New this week"
        // panel, where a larger-than-expected number reads as growth rather than as a bug.
        verify(repository).fleetCounts(shortAgo.capture(), longAgo.capture());
        assertThat(shortAgo.getValue()).isEqualTo(Instant.parse("2026-08-20T10:00:00Z"));
        assertThat(longAgo.getValue()).isEqualTo(Instant.parse("2026-07-28T10:00:00Z"));
    }

    @Test
    @DisplayName("all five counts come from ONE statement, so they describe one ledger")
    void everyCountComesFromOneStatement() {
        CeInstallPingRepository repository = ledger();

        new CeFleetReader(repository).read(NOW, 10);

        // Five separate counts under the default READ COMMITTED each take their own snapshot, so an
        // install arriving between the first and the last makes active30d exceed total and the
        // churn panel (total - active30d) renders negative. Raising the isolation instead is a live
        // hazard behind PgBouncer's transaction pooling, so ONE statement is the whole mechanism:
        // splitting it back up has to fail here.
        verify(repository).fleetCounts(any(), any());
        org.mockito.Mockito.verifyNoMoreInteractions(
                org.mockito.Mockito.ignoreStubs(repository));
    }

    @Test
    @DisplayName("the version breakdown is taken over the LONG window, and honours the limit")
    void versionBreakdownUsesTheLongWindow() {
        CeInstallPingRepository repository = ledger();

        new CeFleetReader(repository).read(NOW, 10);

        // Over 7 days instead of 30 the bars would still render, just describing a different and
        // much smaller fleet than the panel title claims. The limit is what bounds the label
        // cardinality, so it has to reach the query rather than be applied afterwards.
        verify(repository).versionBreakdown(Instant.parse("2026-07-28T10:00:00Z"), 10);
    }

    @Test
    @DisplayName("the snapshot carries the numbers back in the fields that name them")
    void snapshotFieldsAreNotTransposed() {
        CeInstallPingRepository repository = ledger(412, 260, 380, 35, 120);
        when(repository.versionBreakdown(any(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{"0.2.13", 200L}));

        CeFleetReader.Snapshot fleet = new CeFleetReader(repository).read(NOW, 10);

        // Five longs read out of one row by position, then five longs into a record constructor:
        // transposing any two compiles, and every downstream assertion that stubs one flat value
        // for all windows would still pass. The statement selects total FIRST and the record takes
        // it FIFTH, so this is the assertion that keeps that deliberate mismatch honest.
        assertThat(fleet.active7d()).isEqualTo(260);
        assertThat(fleet.active30d()).isEqualTo(380);
        assertThat(fleet.new7d()).isEqualTo(35);
        assertThat(fleet.new30d()).isEqualTo(120);
        assertThat(fleet.total()).isEqualTo(412);
        assertThat(fleet.takenAt()).isEqualTo(NOW);
        assertThat(fleet.versions())
                .containsExactly(new CeFleetReader.VersionCount("0.2.13", 200));
    }

    @Test
    @DisplayName("the read never raises the isolation level, which PgBouncer would leak")
    void theReadDoesNotTouchSessionIsolation() throws NoSuchMethodException {
        Transactional tx = CeFleetReader.class
                .getMethod("read", Instant.class, int.class).getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        // DEFAULT, and this is not a style assertion. Every service reaches Postgres through
        // PgBouncer in pool_mode=transaction, whose server_reset_query is DEALLOCATE ALL and so
        // does NOT reset session isolation; pgjdbc sets the level with SET SESSION CHARACTERISTICS,
        // its own implicit transaction under autocommit, which PgBouncer then returns to the SHARED
        // pool still raised. Unrelated auth writes would start raising 40001 serialization failures
        // they do not retry, permanently, with nothing pointing here. The consistency this class
        // needs comes from fleetCounts being ONE statement instead.
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    @Test
    @DisplayName("a version the query could not name is dropped rather than labelled")
    void nullVersionRowsAreDropped() {
        CeInstallPingRepository repository = ledger(50, 50, 50, 0, 0);
        // A good row AFTER the bad ones on purpose: with the nulls last, "skip this row" and
        // "stop at the first bad row" produce the same list and the mutant survives.
        when(repository.versionBreakdown(any(), anyInt())).thenReturn(List.<Object[]>of(
                new Object[]{"0.2.13", 30L},
                new Object[]{null, 12L},
                new Object[]{"   ", 8L},
                new Object[]{"0.2.12", 5L}));

        CeFleetReader.Snapshot fleet = new CeFleetReader(repository).read(NOW, 10);

        // A Prometheus series tagged version="null" is worse than no series. Dropping the row
        // leaves its installs unaccounted for in the window, which is precisely what the "other"
        // bar is for: 50 - 30 = 20, the 12 and the 8 together.
        assertThat(fleet.versions()).containsExactly(
                new CeFleetReader.VersionCount("0.2.13", 30),
                new CeFleetReader.VersionCount("0.2.12", 5));
        assertThat(fleet.versionsRemainder()).isEqualTo(15);
    }

    @Test
    @DisplayName("the version remainder is what the breakdown did not account for")
    void remainderIsTheUnaccountedTail() {
        CeFleetReader.Snapshot fleet = new CeFleetReader.Snapshot(200, 250, 5, 20, 300,
                List.of(new CeFleetReader.VersionCount("0.2.13", 60),
                        new CeFleetReader.VersionCount("0.2.12", 40)),
                NOW);

        // Against active30d, not total: the breakdown is taken over the 30-day window, so measuring
        // the tail against the whole ledger would silently count churned installs as running an
        // unknown version.
        assertThat(fleet.versionsRemainder()).isEqualTo(150);
    }

    @Test
    @DisplayName("the remainder never goes negative when the counts disagree")
    void remainderIsClampedAtZero() {
        // The counts come from separate statements, and a future refactor that drops the shared
        // transaction can make the breakdown sum exceed the window count for one refresh. A negative
        // row would be published as a bar pointing the wrong way rather than as an error.
        CeFleetReader.Snapshot fleet = new CeFleetReader.Snapshot(10, 10, 0, 0, 10,
                List.of(new CeFleetReader.VersionCount("0.2.13", 25)), NOW);

        assertThat(fleet.versionsRemainder()).isZero();
    }
}
