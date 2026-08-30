package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeInstallPingRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Drops installs not seen for {@code ce.installs.telemetry.retention-days} (default 180).
 *
 * <p>Storage limitation, for installs that were alive and then went away. It is NOT the defence
 * against abuse: an earlier version tried to make it one, by deleting rows seen exactly once on the
 * theory that a fabricated id never returns. That is a property of a lazy attacker rather than of
 * the design (sending each id twice defeats it), and it deleted the single-sighting churn cohort,
 * which is exactly the population a retention metric is about. The bound on rows an anonymous
 * caller can create lives on the insert in {@link CeInstallPingRecorder}, where it can be exact.
 *
 * <p>Held under a {@link SchedulerLock}, so only one replica runs it. Every auth pod fires this
 * cron and the deletes are on a table an anonymous caller can grow, so two pods running them at
 * once is a duplicated long transaction, a doubled WAL burst and contending locks.
 * {@code CeLinkRetentionScheduler} documents the same hazard for its own daily job.
 *
 * <p>Batched, and capped per run. The run that most needs to succeed is the one with the most to
 * remove, which is exactly the run a single statement would turn into a long transaction. Anything
 * over the cap waits for the next run: nothing reads a row that is about to be deleted, and the
 * cap is what keeps this job off the single scheduler thread the rest of the service shares.
 *
 * <p>Best-effort: a failed pass is logged and retried on the next run, and each batch is its own
 * transaction so partial progress is kept.
 */
@Component
@ConditionalOnProperty(name = "ce.installs.telemetry.enabled", havingValue = "true", matchIfMissing = false)
public class CeInstallPingRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CeInstallPingRetentionScheduler.class);

    /**
     * Floor on the confirmed-install window. A retention shorter than the widest window the stats
     * read reports (30 days) would delete the rows those numbers are computed from, so active30d
     * would quietly become activeNd with nothing saying so.
     */
    static final int MIN_RETENTION_DAYS = 31;
    /**
     * Rows removed per statement, and the most any single run will remove.
     *
     * <p>Deliberately modest. Boot's scheduler pool is one thread by default and this service runs
     * several other {@code @Scheduled} beans on it (OAuth2 refresh, credit reconciliation,
     * subscription renewal), so a purge that ran for half an hour would block all of them. Forty
     * batches is 200,000 rows a night, which drains even a ledger at its five-million ceiling
     * inside a month while keeping this job to minutes.
     */
    static final int BATCH_SIZE = 5_000;
    static final int MAX_BATCHES_PER_RUN = 40;

    private final CeInstallPingRepository repository;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public CeInstallPingRetentionScheduler(
            CeInstallPingRepository repository,
            @Value("${ce.installs.telemetry.retention-days:180}") int retentionDays) {
        this(repository, retentionDays, Clock.systemUTC());
    }

    /**
     * Clock-injecting constructor for tests. The public one above carries {@code @Autowired}
     * deliberately: a second constructor with none leaves the container unable to choose, and that
     * surfaces as the application refusing to start rather than as anything pointing here.
     */
    CeInstallPingRetentionScheduler(
            CeInstallPingRepository repository,
            int retentionDays,
            Clock clock) {
        this.repository = repository;
        this.retention = Duration.ofDays(Math.max(MIN_RETENTION_DAYS, retentionDays));
        this.clock = clock;
        // Silently widening a retention an operator chose deliberately is the kind of override that
        // is only ever discovered by wondering why rows are still there.
        if (retentionDays < MIN_RETENTION_DAYS) {
            log.warn("ce.installs.telemetry.retention-days={} is below the {}-day floor and has been "
                            + "raised to it: a shorter window would delete the rows the fleet read "
                            + "reports over its own 30-day window",
                    retentionDays, MIN_RETENTION_DAYS);
        }
    }

    /** Daily at 04:00 UTC. */
    @Scheduled(cron = "${ce.installs.telemetry.retention-cron:0 0 4 * * *}", zone = "UTC")
    @SchedulerLock(name = "ce_install_ledger_purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purge() {
        Instant cutoff = clock.instant().minus(retention);
        int removed = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                int inBatch = repository.purgeUnseenSince(cutoff, BATCH_SIZE);
                removed += inBatch;
                if (inBatch < BATCH_SIZE) {
                    break;
                }
            }
            if (removed > 0) {
                log.info("CE install ledger: purged {} installs unseen since {}", removed, cutoff);
            }
        } catch (RuntimeException failure) {
            log.warn("CE install ledger purge stopped after {} rows, retrying on the next run: {}",
                    removed, failure.getMessage());
        }
    }
}
