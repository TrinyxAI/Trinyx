package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeInstallPingRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The one definition of "the fleet numbers", read once and shared by everything that reports them.
 *
 * <p>Extracted because there are two consumers, {@link CeInstallStatsController} and
 * {@link CeInstallFleetMetrics}, and they had grown their own copies of the same two windows, the
 * same five counts and the same row mapping. Two definitions can drift, and the drift would be invisible: the gauge
 * label says {@code 7d} whatever window the query actually used.
 *
 * <p>All five counts come back from ONE statement, because one statement is one snapshot. Five
 * separate counts under the default READ COMMITTED each take their own, so an install arriving
 * between the first and the last makes {@code active30d} exceed {@code total} and the dashboard's
 * churn panel ({@code total - active30d}) renders a negative number: not a theoretical interleaving
 * on a ledger a whole fleet writes to. Raising the isolation instead would have been a live hazard
 * behind PgBouncer, for reasons {@code CeInstallPingRepository.fleetCounts} sets out.
 *
 * <p>The version breakdown is a second statement and therefore a second snapshot, which is why
 * {@link Snapshot#versionsRemainder()} clamps at zero rather than trusting the arithmetic.
 */
@Service
@ConditionalOnProperty(name = "ce.installs.telemetry.enabled", havingValue = "true", matchIfMissing = false)
public class CeFleetReader {

    /** The window every "new installs" and "active installs" figure is reported over. */
    static final Duration SHORT_WINDOW = Duration.ofDays(7);
    static final Duration LONG_WINDOW = Duration.ofDays(30);

    /** Column positions in the {@code fleetCounts} row, in the order the statement selects them. */
    private static final int TOTAL = 0;
    private static final int ACTIVE_SHORT = 1;
    private static final int ACTIVE_LONG = 2;
    private static final int NEW_SHORT = 3;
    private static final int NEW_LONG = 4;

    /** Postgres hands counts back as {@code Long}, H2 as {@code BigInteger}: read both. */
    private static long at(Object[] row, int column) {
        return ((Number) row[column]).longValue();
    }

    private final CeInstallPingRepository repository;

    public CeFleetReader(CeInstallPingRepository repository) {
        this.repository = repository;
    }

    /**
     * One consistent set of fleet numbers.
     *
     * @param now          the instant both windows are measured back from
     * @param versionLimit how many versions to break out before the caller folds the rest
     */
    @Transactional(readOnly = true)
    public Snapshot read(Instant now, int versionLimit) {
        Instant shortAgo = now.minus(SHORT_WINDOW);
        Instant longAgo = now.minus(LONG_WINDOW);
        List<VersionCount> versions = new ArrayList<>();
        for (Object[] row : repository.versionBreakdown(longAgo, versionLimit)) {
            // A version that is null or blank is dropped rather than labelled, because a Prometheus
            // series tagged version="null" is worse than no series: the installs behind it are then
            // simply unaccounted for in the window, which is exactly what "other" is for. The query
            // COALESCEs, so this is defence in depth on the mapper both consumers now share.
            String version = row[0] == null ? null : row[0].toString();
            if (version == null || version.isBlank()) {
                continue;
            }
            versions.add(new VersionCount(version, ((Number) row[1]).longValue()));
        }
        Object[] counts = repository.fleetCounts(shortAgo, longAgo).get(0);
        return new Snapshot(
                at(counts, ACTIVE_SHORT),
                at(counts, ACTIVE_LONG),
                at(counts, NEW_SHORT),
                at(counts, NEW_LONG),
                at(counts, TOTAL),
                versions,
                now);
    }

    /** One version and how many installs report it. */
    public record VersionCount(String version, long installs) {
    }

    /**
     * @param active7d  installs seen in the last 7 days: the live-fleet number
     * @param active30d installs seen in the last 30 days
     * @param new7d     installs whose FIRST sighting was in the last 7 days
     * @param new30d    installs whose FIRST sighting was in the last 30 days
     * @param total     every install in the ledger, including ones that stopped reporting
     * @param versions  the most common versions over the long window, most common first
     * @param takenAt   when the snapshot was read
     */
    public record Snapshot(
            long active7d,
            long active30d,
            long new7d,
            long new30d,
            long total,
            List<VersionCount> versions,
            Instant takenAt) {

        /** Installs in the long window not covered by {@link #versions}, never negative. */
        public long versionsRemainder() {
            long accounted = versions.stream().mapToLong(VersionCount::installs).sum();
            return Math.max(0, active30d - accounted);
        }
    }
}
