package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeInstallPingRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Storage limitation for installs that were alive and then went away. These pin the cutoff, the
 * floor that keeps it from eating the window the fleet read reports over, the batching, and the
 * lock that stops two replicas running the same deletes at once.
 */
class CeInstallPingRetentionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static CeInstallPingRetentionScheduler scheduler(
            CeInstallPingRepository repository, int retentionDays) {
        return new CeInstallPingRetentionScheduler(repository, retentionDays, CLOCK);
    }

    @Test
    @DisplayName("a retention shorter than the reported window is raised to its exact floor")
    void retentionIsFlooredAtItsExactValue() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);

        scheduler(repository, 3).purge();

        var cutoff = forClass(Instant.class);
        verify(repository).purgeUnseenSince(cutoff.capture(), anyInt());
        // The exact floor, not merely "some floor above 30": a value that drifted to 5000 would
        // satisfy a greater-than assertion while silently disabling retention.
        assertThat(Duration.between(cutoff.getValue(), NOW))
                .isEqualTo(Duration.ofDays(CeInstallPingRetentionScheduler.MIN_RETENTION_DAYS));
    }

    @Test
    @DisplayName("keeps deleting while batches come back full, and stops at the per-run cap")
    void loopsUntilDrainedAndStopsAtTheCap() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.purgeUnseenSince(any(), anyInt()))
                .thenReturn(CeInstallPingRetentionScheduler.BATCH_SIZE);

        scheduler(repository, 180).purge();

        // A single unbounded DELETE on the run with the most to remove, which is the run that
        // matters, is a long transaction holding locks on a table an anonymous caller can grow.
        verify(repository, times(CeInstallPingRetentionScheduler.MAX_BATCHES_PER_RUN))
                .purgeUnseenSince(any(), anyInt());
    }

    @Test
    @DisplayName("stops as soon as a batch comes back short")
    void stopsOnTheFirstShortBatch() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.purgeUnseenSince(any(), anyInt())).thenReturn(3);

        scheduler(repository, 180).purge();

        // A short batch means that population is drained; continuing would be 399 more empty
        // deletes every night.
        verify(repository, times(1)).purgeUnseenSince(any(), anyInt());
    }

    @Test
    @DisplayName("a failed purge is swallowed and retried on the next run")
    void failureIsSwallowed() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.purgeUnseenSince(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        // Nothing depends on the purge having happened, and a scheduled task that throws leaves a
        // stacktrace a day for a job whose next run would fix it anyway.
        assertThatCode(() -> scheduler(repository, 180).purge()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the lock is held long enough that a second replica cannot re-run the purge")
    void lockIsHeldPastTheRunItself() throws Exception {
        SchedulerLock lock = CeInstallPingRetentionScheduler.class.getMethod("purge")
                .getAnnotation(SchedulerLock.class);

        // A purge with nothing to remove finishes in milliseconds and releases the lock instantly,
        // so a pod whose clock trails would acquire it and run the same DELETEs again. Every
        // sibling scheduler in this service sets lockAtLeastFor for exactly that reason.
        assertThat(lock).isNotNull();
        assertThat(Duration.parse(lock.lockAtLeastFor()))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(1));
        // The lock has to outlast the worst run and no more. 40 batches of 5000 is the cap, each
        // statement is bounded at 30s, so ten minutes covers it with room; a lock that expired
        // first would let a second replica start the same deletes mid-run, and one held far longer
        // would keep a crashed pod's lock in place through the following night.
        assertThat(Duration.parse(lock.lockAtMostFor())).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("raising a configured retention to the floor is said out loud")
    void flooringAConfiguredRetentionWarns() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(CeInstallPingRetentionScheduler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        ch.qos.logback.classic.Level original = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        logger.addAppender(events);

        try {
            scheduler(mock(CeInstallPingRepository.class), 3);
            scheduler(mock(CeInstallPingRepository.class), 180);
        } finally {
            logger.detachAppender(events);
            logger.setLevel(original);
        }

        // Silently widening a retention an operator chose deliberately is the kind of override only
        // ever discovered by wondering why rows are still there, and the sibling ledger-full
        // warning got the same treatment.
        assertThat(events.list.stream()
                .filter(e -> e.getFormattedMessage().contains("below the"))
                .count()).isEqualTo(1);
    }
}
