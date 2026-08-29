package com.apimarketplace.auth.web.version;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the self-hosted fleet numbers as Prometheus gauges, so the Grafana overview can show
 * them next to everything else rather than only through an authenticated curl.
 *
 * <p>Gated on the same {@code ce.installs.telemetry.enabled} flag as collection: an install that
 * stores nothing has nothing to publish. Reads through {@link CeFleetReader}, so the gauges and the
 * stats endpoint cannot drift into two different definitions of the same five numbers.
 *
 * <p>Refreshed on a schedule rather than computed per scrape, because these are {@code COUNT} and
 * {@code GROUP BY} statements over the ledger and a scrape interval is measured in seconds while
 * the data moves over days. {@code fixedDelay} with no initial delay, so the first read happens as
 * the context refreshes. There is deliberately NO {@code ApplicationReadyEvent} listener here:
 * {@code @Scheduled} tasks are registered on {@code ContextRefreshedEvent} and therefore run first
 * anyway. One was tried, and it did harm. It always found the scheduled run's stamp less than a
 * minute old, skipped, and logged the below-the-floor warning on every boot of a correctly
 * configured pod, which also consumed the one-shot flag that warning uses and so disarmed it for an
 * interval that really was too short.
 *
 * <p>Until a refresh SUCCEEDS the fleet gauges are not registered at all, so a pod that has never
 * managed to read the ledger reports nothing rather than zero. The {@code auth} job is scraped
 * through a Service ClusterIP that round-robins across replicas into a single series, so a zero from
 * such a pod would land on the same line as a healthy pod's value and read as a fleet that vanished:
 * exactly the lie the failure path below exists to avoid, arriving by the one route that path cannot
 * cover, since it only protects values that were once good. {@link #LAST_SUCCESS} IS registered up
 * front, at 0, because it is the health signal: an age measured from the epoch is what says this pod
 * has never succeeded.
 *
 * <p>The queries in the dashboard use {@code max by (...)}. Be precise about what that buys:
 * today's {@code auth} job scrapes a Service ClusterIP, so Prometheus sees ONE series whose value
 * alternates between pods, and {@code max} aggregates a single sample and repairs nothing. It is
 * insurance for the day that job moves to per-pod service discovery, as the {@code orchestrator}
 * job already had to, where {@code sum}
 * would silently multiply the fleet by the replica count. On the same ClusterIP target, deferring
 * registration until the first success turns a struggling pod's contribution into a GAP in that
 * shared series rather than a zero on it: better, but still not a per-pod signal.
 *
 * <p>Cardinality policy, following {@code AgentPrometheusMetrics}: no PII tags, bounded tags. The
 * {@code version} label is the one risk, because it comes from data a caller supplies: an anonymous
 * caller can mint installs reporting any number of distinct published-looking versions. Only the
 * {@value #TRACKED_VERSIONS} most common get their own series and the remainder is folded into
 * {@code version="other"}, which bounds the CONCURRENT series count whatever arrives. Be precise
 * about what that is and is not: {@code CeInstallPingRecorder.sanitizeVersion} pins the SHAPE of the
 * value (a dotted number with an optional suffix, or the literal {@code "dev"}), not its membership
 * in any published set, so a caller can still mint unbounded DISTINCT values and churn up to
 * {@value #TRACKED_VERSIONS} new series per refresh in the TSDB. What the fold guarantees is that no
 * single scrape ever carries more than eleven, and that the value is always label-safe. Loosen the
 * shape check and it becomes a label-escaping problem in an unrelated change.
 */
@Component
@ConditionalOnProperty(name = "ce.installs.telemetry.enabled", havingValue = "true", matchIfMissing = false)
public class CeInstallFleetMetrics {

    private static final Logger log = LoggerFactory.getLogger(CeInstallFleetMetrics.class);

    /** Installs seen inside a window: the live-fleet numbers. */
    static final String ACTIVE = "ce_fleet_installs_active";
    /** Installs whose FIRST sighting falls inside a window. */
    static final String NEW = "ce_fleet_installs_new";
    /** Every install in the ledger, windowless, so it carries no window label. */
    static final String KNOWN = "ce_fleet_installs_known";
    /** Installs per running version over the long window. */
    static final String BY_VERSION = "ce_fleet_installs_by_version";
    /** When the gauges above were last refreshed successfully, as a unix timestamp. */
    static final String LAST_SUCCESS = "ce_fleet_metrics_last_success_timestamp_seconds";
    /** How many versions get their own series before the tail is folded into "other". */
    static final int TRACKED_VERSIONS = 10;
    /**
     * Floor on the refresh interval.
     *
     * <p>A refresh occupies Boot's SINGLE shared scheduler thread for the whole read: two
     * statements, the counts row and the version group-by, each carrying a 2s timeout, so a
     * pathological tick can hold it for about four seconds while the retention purge and the credit
     * jobs queue behind it. Normal ticks are milliseconds. Nothing bounds that per-tick cost, so
     * what has to be bounded is the CADENCE: at the shipped PT5M this is a fraction of a percent of
     * the thread even in the worst case, and at PT1S it would be all of it.
     *
     * <p>The DATABASE cost is the one that grows, and only for one of the two. The version
     * breakdown filters on {@code last_seen_at} alone and uses that column's index. The counts row
     * has no {@code WHERE} at all, only aggregate {@code FILTER} clauses, so nothing narrows it:
     * at the documented 5,000,000-row ceiling it is the one that plausibly exceeds its 2s timeout.
     * The remedies, cheapest first, are a longer interval, a covering
     * {@code (last_seen_at, first_seen_at)} index that lets Postgres serve it index-only when the
     * visibility map is clean, or a materialised count. Unlike the stats endpoint these now run unconditionally,
     * per pod, forever. When they do start timing out the gauges stop moving and the ONLY signal is
     * {@link #LAST_SUCCESS} and the "Metrics age" panel it feeds, which is why that panel is on the
     * row rather than being treated as an implementation detail.
     */
    static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    private static final String ACTIVE_HELP =
            "Self-hosted installs seen at least once inside the window named by the window label";
    private static final String NEW_HELP =
            "Self-hosted installs whose FIRST sighting falls inside the window named by the label";

    private final CeFleetReader reader;
    private final Clock clock;

    /** When a refresh was last ATTEMPTED, which is what the floor below is measured against. */
    private volatile Instant lastAttempt;
    /** Whether the floor has already been complained about; it would otherwise log every tick. */
    private final AtomicBoolean throttleReported = new AtomicBoolean();

    private final AtomicLong active7d = new AtomicLong();
    private final AtomicLong active30d = new AtomicLong();
    private final AtomicLong new7d = new AtomicLong();
    private final AtomicLong new30d = new AtomicLong();
    private final AtomicLong known = new AtomicLong();
    private final AtomicLong lastSuccess = new AtomicLong();
    private final MultiGauge byVersion;
    /** The fleet gauges are only registered once there is a real value to put in them. */
    private final AtomicBoolean fleetGaugesRegistered = new AtomicBoolean();
    private final MeterRegistry registry;

    @Autowired
    public CeInstallFleetMetrics(CeFleetReader reader, MeterRegistry registry) {
        this(reader, registry, Clock.systemUTC());
    }

    /**
     * Clock-injecting constructor for tests. The public one carries {@code @Autowired}
     * deliberately: a second constructor with none leaves the container unable to choose, and that
     * surfaces as the application refusing to start rather than as anything pointing here.
     */
    CeInstallFleetMetrics(CeFleetReader reader, MeterRegistry registry, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        this.registry = registry;
        // Not a fleet number: the age of the ones below, and the only thing that tells a frozen
        // refresh apart from a fleet that simply has not changed. Registered unconditionally for
        // that reason.
        //
        // Seeded at BOOT, not at 0. At 0 a pod whose first refresh fails reports an age of 57
        // years, which is instantly past any staleness threshold, and the alert's min_over_time
        // then retains that sample for its whole window: one failed refresh at startup, recovered
        // five minutes later, would page the on-call. From boot the reading is simply true, "these
        // numbers are as old as this pod", it crosses the threshold only if the pod really never
        // manages a read, and what still identifies that case exactly is the ABSENCE of the fleet
        // gauges, which are not registered until a refresh succeeds.
        lastSuccess.set(clock.instant().getEpochSecond());
        Gauge.builder(LAST_SUCCESS, lastSuccess, AtomicLong::doubleValue)
                .description("Unix time of the last successful CE fleet metrics refresh")
                .register(registry);
        this.byVersion = MultiGauge.builder(BY_VERSION)
                .description("Self-hosted installs per running version, seen in the last 30 days")
                .register(registry);
    }

    /** Publishes the fleet gauges, once, the first time there is a real value behind them. */
    private void registerFleetGauges() {
        if (!fleetGaugesRegistered.compareAndSet(false, true)) {
            return;
        }
        // One description per metric FAMILY, not per series: Prometheus exposition carries a
        // single # HELP line for all of ce_fleet_installs_active, so a per-window sentence would
        // describe the 7d series with the 30d text (or the reverse) in the scrape output.
        gauge(registry, ACTIVE, "7d", active7d, ACTIVE_HELP);
        gauge(registry, ACTIVE, "30d", active30d, ACTIVE_HELP);
        gauge(registry, NEW, "7d", new7d, NEW_HELP);
        gauge(registry, NEW, "30d", new30d, NEW_HELP);
        Gauge.builder(KNOWN, known, AtomicLong::doubleValue)
                .description("Self-hosted installs the ledger has ever seen and not yet purged")
                .register(registry);
    }

    private static void gauge(MeterRegistry registry, String name, String window,
                              AtomicLong value, String help) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .tag("window", window)
                .description(help)
                .register(registry);
    }

    /**
     * Re-reads the ledger.
     *
     * <p>Best-effort: a failure keeps the last published values rather than dropping the series to
     * zero, because a gauge reading 0 during a database blip is indistinguishable on a graph from a
     * fleet that vanished. That is only safe because {@link #LAST_SUCCESS} says how old the values
     * are, so a refresh that has been failing for hours is visible rather than merely quiet.
     */
    @Scheduled(fixedDelayString = "${ce.installs.telemetry.metrics-interval:PT5M}")
    public void refresh() {
        if (throttled()) {
            return;
        }
        try {
            CeFleetReader.Snapshot fleet = reader.read(clock.instant(), TRACKED_VERSIONS);
            // known FIRST, deliberately. These are five independent writes and a scrape can land
            // between any two of them, pairing an old value with a new one. The churn card is
            // known - active30d, so writing known last is the ordering that can momentarily show a
            // SMALLER ledger against a LARGER active count: a negative churn, which is exactly the
            // number the single-statement ledger read exists to prevent, reintroduced one layer up.
            // This way the transient error is a churn that is briefly too large, and positive. The
            // card's clamp_min covers the rest.
            known.set(fleet.total());
            active7d.set(fleet.active7d());
            active30d.set(fleet.active30d());
            new7d.set(fleet.new7d());
            new30d.set(fleet.new30d());
            // true, not false: MultiGauge keeps the FIRST value for an already-registered tag set
            // otherwise, so every version bar would freeze at its first observed count for the life
            // of the pod while looking perfectly healthy.
            byVersion.register(versionRows(fleet), true);
            // After the values are in, never before: registering the gauges any earlier publishes
            // the zero this whole ordering exists to keep off the wire.
            registerFleetGauges();
            lastSuccess.set(clock.instant().getEpochSecond());
            // RuntimeException only. An Error would cancel the scheduled task permanently, and
            // swallowing one to keep a dashboard fed is the wrong trade: LAST_SUCCESS stops
            // advancing either way, so the "Metrics age" panel still says it happened.
        } catch (RuntimeException failure) {
            // WARN, like both siblings: the values are deliberately retained, so a recurring
            // statement timeout on a large ledger produces a flat line that reads as a healthy
            // stable fleet and says nothing at all at DEBUG.
            // Two messages, because on a pod whose FIRST refresh fails there is nothing being
            // kept: the fleet gauges are not registered at all, and saying "keeping values from
            // 1970-01-01" would assert the opposite of what happened.
            // The gauges, not the stamp, are what say "never succeeded": the stamp starts at boot.
            if (!fleetGaugesRegistered.get()) {
                log.warn("CE fleet metrics have never been read, so no fleet gauges are published "
                        + "yet: {}", failure.getMessage());
            } else {
                log.warn("CE fleet metrics not refreshed, keeping values from {}: {}",
                        Instant.ofEpochSecond(lastSuccess.get()), failure.getMessage());
            }
        }
    }

    /**
     * Enforces {@link #MIN_INTERVAL} on the refresh rate, whatever the schedule was configured to.
     *
     * <p>The floor lives here rather than on the configured interval because
     * {@code @Scheduled(fixedDelayString)} reads that property itself: clamping the value in the
     * constructor would have produced a field nothing consults, so a one-second interval in a
     * values file would still have run a count and a group-by every second on the single scheduler
     * thread this service shares with twenty other jobs, while a test asserting the clamp passed.
     *
     * @return true when this call is too soon after the previous one and should be skipped
     */
    private boolean throttled() {
        Instant now = clock.instant();
        Instant previous = lastAttempt;
        Duration elapsed = previous == null ? null : Duration.between(previous, now);
        // isNegative first: this is wall-clock time, so an NTP step backwards of N would otherwise
        // put every refresh below the floor for N and freeze the whole metric family, which is the
        // one failure shape this class exists to prevent. A step back admits the refresh.
        if (elapsed != null && !elapsed.isNegative() && elapsed.compareTo(MIN_INTERVAL) < 0) {
            if (throttleReported.compareAndSet(false, true)) {
                // Once, not per tick: the point is to tell the operator their configured interval
                // is not the one in effect, not to become the log volume it is protecting against.
                log.warn("ce.installs.telemetry.metrics-interval is below the {}s floor, so fleet "
                                + "refreshes are being skipped to keep the shared scheduler thread "
                                + "free; raise the interval to at least {}",
                        MIN_INTERVAL.toSeconds(), MIN_INTERVAL);
            }
            return true;
        }
        lastAttempt = now;
        return false;
    }

    /** The tracked versions as their own rows, with whatever the limit cut off summed into one. */
    private static List<MultiGauge.Row<?>> versionRows(CeFleetReader.Snapshot fleet) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (CeFleetReader.VersionCount version : fleet.versions()) {
            rows.add(MultiGauge.Row.of(Tags.of("version", version.version()), version.installs()));
        }
        long other = fleet.versionsRemainder();
        if (other > 0) {
            rows.add(MultiGauge.Row.of(Tags.of("version", "other"), other));
        }
        return rows;
    }
}
