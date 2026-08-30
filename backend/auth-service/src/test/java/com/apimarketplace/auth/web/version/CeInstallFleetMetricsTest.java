package com.apimarketplace.auth.web.version;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * These gauges are read by a dashboard rather than by a person, so a wrong number here is a wrong
 * number nobody thinks to question. The two failure shapes worth tests are a value that silently
 * stops moving and a value that reads zero while the fleet is fine: both render as a perfectly
 * healthy-looking panel.
 */
class CeInstallFleetMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
    private static final Duration EVERY_5_MIN = Duration.ofMinutes(5);

    /**
     * A clock the test moves by hand. A fixed one cannot be used for anything that refreshes twice
     * now that the refresh floor is enforced against the clock rather than against a configured
     * value: two refreshes at the same instant are, correctly, one refresh.
     */
    private static final class TickingClock extends Clock {
        private Instant now = NOW;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private static TickingClock clock() {
        return new TickingClock();
    }

    private static CeFleetReader.Snapshot snapshot(long active7, long active30, long new7,
                                                   long new30, long total,
                                                   List<CeFleetReader.VersionCount> versions) {
        return new CeFleetReader.Snapshot(active7, active30, new7, new30, total, versions, NOW);
    }

    /** A reader that answers each successive refresh with the next snapshot given. */
    private static CeFleetReader readerReturning(CeFleetReader.Snapshot... snapshots) {
        CeFleetReader reader = mock(CeFleetReader.class);
        when(reader.read(any(), anyInt()))
                .thenReturn(snapshots[0], Arrays.copyOfRange(snapshots, 1, snapshots.length));
        return reader;
    }

    private static Map<String, Double> gauges(MeterRegistry registry, String name, String tag) {
        return registry.find(name).gauges().stream()
                .collect(Collectors.toMap(g -> String.valueOf(g.getId().getTag(tag)),
                        io.micrometer.core.instrument.Gauge::value));
    }

    private static double single(MeterRegistry registry, String name) {
        return registry.find(name).gauge().value();
    }

    @Test
    @DisplayName("each window is published under its own metric and label")
    void publishesEveryWindow() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(snapshot(260, 380, 35, 120, 412,
                List.of(new CeFleetReader.VersionCount("0.2.13", 200))));

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        assertThat(gauges(registry, CeInstallFleetMetrics.ACTIVE, "window"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("7d", 260.0, "30d", 380.0));
        assertThat(gauges(registry, CeInstallFleetMetrics.NEW, "window"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("7d", 35.0, "30d", 120.0));
        assertThat(single(registry, CeInstallFleetMetrics.KNOWN)).isEqualTo(412.0);

        // Prometheus exposition carries ONE # HELP per metric family, so the two series of a
        // family must share a description or the scrape output describes the 7d series with the
        // 30d sentence. Swapping the two constants is otherwise a green change.
        assertThat(registry.find(CeInstallFleetMetrics.ACTIVE).gauges())
                .extracting(gauge -> gauge.getId().getDescription())
                .containsOnly("Self-hosted installs seen at least once inside the window named by "
                        + "the window label");
        assertThat(registry.find(CeInstallFleetMetrics.NEW).gauges())
                .extracting(gauge -> gauge.getId().getDescription())
                .containsOnly("Self-hosted installs whose FIRST sighting falls inside the window "
                        + "named by the label");
        // The other three families too. HELP is what an operator reads in the scrape output or in
        // Grafana's metric browser when they meet these names for the first time, and only two of
        // the five were pinned.
        assertThat(registry.find(CeInstallFleetMetrics.KNOWN).gauge().getId().getDescription())
                .isEqualTo("Self-hosted installs the ledger has ever seen and not yet purged");
        assertThat(registry.find(CeInstallFleetMetrics.LAST_SUCCESS).gauge().getId()
                .getDescription())
                .isEqualTo("Unix time of the last successful CE fleet metrics refresh");
        assertThat(registry.find(CeInstallFleetMetrics.BY_VERSION).gauges())
                .extracting(gauge -> gauge.getId().getDescription())
                .containsOnly("Self-hosted installs per running version, seen in the last 30 days");
    }

    @Test
    @DisplayName("active, new and known are three metrics, so summing the fleet stays meaningful")
    void activeAndNewAreSeparateMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(snapshot(260, 380, 35, 120, 412, List.of()));

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        // Folding these into one ce_fleet_installs{window=active_7d|new_7d|total} reads fine until
        // something applies the sum() a generic panel defaults to and gets active+new+total: a
        // number with no meaning that still renders. Asserting the three constants merely differ
        // would have passed for any three distinct strings, including three wrong ones.
        assertThat(registry.find(CeInstallFleetMetrics.ACTIVE).gauges()).hasSize(2);
        assertThat(registry.find(CeInstallFleetMetrics.NEW).gauges()).hasSize(2);
        assertThat(registry.find(CeInstallFleetMetrics.KNOWN).gauges()).hasSize(1);
        assertThat(registry.find(CeInstallFleetMetrics.KNOWN).gauge().getId().getTag("window"))
                .as("the ledger total is windowless, so it must carry no window label")
                .isNull();
    }

    @Test
    @DisplayName("the refresh is scheduled off the documented property, and nothing else runs it")
    void theRefreshIsWiredToTheSchedule() throws NoSuchMethodException {
        Scheduled scheduled = CeInstallFleetMetrics.class
                .getMethod("refresh").getAnnotation(Scheduled.class);

        // Drop or mistype this annotation and every gauge freezes at its first value for the life
        // of the pod: the "value that silently stops moving" this class exists to prevent, arriving
        // through the wiring rather than through the data. No behavioural test can see it, because
        // every one of them calls refresh() directly.
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${ce.installs.telemetry.metrics-interval:PT5M}");

        // And no startup listener. One was tried: @Scheduled tasks are registered on
        // ContextRefreshedEvent, which PRECEDES ApplicationReadyEvent, so the listener always found
        // the scheduled run's stamp seconds old, skipped, and logged the below-the-floor warning on
        // every boot of a correctly configured pod, burning the one-shot flag that warning uses.
        assertThat(CeInstallFleetMetrics.class.getMethods())
                .as("a startup listener would re-arm the false below-the-floor warning on boot")
                .noneMatch(method -> method.isAnnotationPresent(EventListener.class));
    }

    @Test
    @DisplayName("the refresh floor is one minute")
    void theFloorIsOneMinute() {
        // Pinned to a literal, not to itself. Every other test here advances either 1 second or 5
        // minutes, so the floor was only constrained to somewhere in between: a 4-minute value
        // would make the shipped PT5M interval fragile to tick jitter with nothing noticing.
        assertThat(CeInstallFleetMetrics.MIN_INTERVAL).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("only the ten most common versions get their own series")
    void tenVersionsAreTracked() {
        // The literal matters: this is the cardinality bound on a caller-supplied label, so it is
        // a security property, and every other test that mentions it reads the constant back.
        assertThat(CeInstallFleetMetrics.TRACKED_VERSIONS).isEqualTo(10);
    }

    @Test
    @DisplayName("the ledger is read at the clock's instant, for exactly the tracked versions")
    void readsAtTheClockInstantWithTheTrackedLimit() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(snapshot(1, 1, 1, 1, 1, List.of()));

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        // Both windows are measured back from this instant inside the reader, so passing anything
        // else here silently shifts every figure on the row.
        verify(reader).read(eq(NOW), eq(CeInstallFleetMetrics.TRACKED_VERSIONS));
    }

    @Test
    @DisplayName("a pod that has never succeeded publishes no fleet gauges at all, not zeros")
    void neverSucceededPublishesNothing() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = mock(CeFleetReader.class);
        doThrow(new IllegalStateException("statement timeout")).when(reader).read(any(), anyInt());

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        // The keep-the-last-values path only protects values that were once good. A pod that never
        // read the ledger has none, and the auth job is scraped through a ClusterIP that
        // round-robins across replicas into ONE series, so a 0 from this pod lands on the same line
        // as a healthy pod's number and sawtooths the panel to zero: the exact lie the failure path
        // claims to prevent, arriving by the one route it cannot cover.
        assertThat(registry.find(CeInstallFleetMetrics.ACTIVE).gauges()).isEmpty();
        assertThat(registry.find(CeInstallFleetMetrics.NEW).gauges()).isEmpty();
        assertThat(registry.find(CeInstallFleetMetrics.KNOWN).gauges()).isEmpty();
        // The health signal must still be there, seeded at BOOT rather than at 0. At 0 it reads
        // 57 years of age instantly, which is past any staleness threshold, and the alert's
        // min_over_time then retains that sample for its whole window: one failed refresh at
        // startup, recovered on the next tick, would page the on-call. From boot the reading is
        // true and harmless, and what identifies "never succeeded" exactly is the absence of the
        // fleet gauges asserted above.
        assertThat(single(registry, CeInstallFleetMetrics.LAST_SUCCESS))
                .isEqualTo(NOW.getEpochSecond());
    }

    @Test
    @DisplayName("the never-succeeded failure says so, instead of claiming to keep 1970 values")
    void neverSucceededWarnsWithoutClaimingToKeepValues() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = mock(CeFleetReader.class);
        doThrow(new IllegalStateException("connection refused")).when(reader).read(any(), anyInt());
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock());

        Logger logger = (Logger) LoggerFactory.getLogger(CeInstallFleetMetrics.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Level original = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(events);
        try {
            metrics.refresh();
        } finally {
            logger.detachAppender(events);
            logger.setLevel(original);
        }

        // The other branch's wording, on this branch, reads "keeping values from 1970-01-01": it
        // asserts the opposite of what happened, since nothing is registered yet, and sends whoever
        // reads it looking for values that do not exist.
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("never been read");
            assertThat(event.getFormattedMessage()).doesNotContain("1970");
            assertThat(event.getFormattedMessage()).doesNotContain("keeping values");
        });
    }

    @Test
    @DisplayName("a gauge is never registered before the value behind it is set")
    void gaugesCarryTheirValueTheInstantTheyAppear() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Map<String, Double> atRegistration = new java.util.LinkedHashMap<>();
        // Reads each gauge the moment it enters the registry. A scrape landing between the
        // registration and the set() would see exactly this, and it is the only way to observe an
        // ordering whose real window is microseconds: after the fact the values agree either way.
        registry.config().onMeterAdded(meter -> {
            if (meter instanceof io.micrometer.core.instrument.Gauge gauge) {
                atRegistration.put(meter.getId().getName() + meter.getId().getTags(), gauge.value());
            }
        });
        CeFleetReader reader = readerReturning(snapshot(260, 380, 35, 120, 412, List.of()));

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        assertThat(atRegistration)
                .as("the fleet gauges must carry their real value from the moment they exist")
                .containsEntry(CeInstallFleetMetrics.ACTIVE + "[tag(window=7d)]", 260.0)
                .containsEntry(CeInstallFleetMetrics.KNOWN + "[]", 412.0);
    }

    @Test
    @DisplayName("the fleet gauges appear on the first success")
    void gaugesAppearOnFirstSuccess() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(snapshot(260, 380, 35, 120, 412, List.of()));
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock());
        assertThat(registry.find(CeInstallFleetMetrics.ACTIVE).gauges()).isEmpty();

        metrics.refresh();

        // Registered AFTER the values are set, never before, or the first scrape catches the 0.
        assertThat(gauges(registry, CeInstallFleetMetrics.ACTIVE, "window")).containsEntry("7d", 260.0);
    }

    @Test
    @DisplayName("the ledger total is published before the window counts")
    void knownIsPublishedFirst() {
        MeterRegistry registry = new SimpleMeterRegistry();
        // A mocked snapshot, so the ORDER its accessors are read in is observable. The AtomicLongs
        // behind the gauges are private and the window is microseconds wide, so the read order is
        // the only proxy for the publication order, and it is an exact one: each set() call takes
        // its value straight from the accessor.
        CeFleetReader.Snapshot fleet = mock(CeFleetReader.Snapshot.class);
        when(fleet.total()).thenReturn(412L);
        when(fleet.active7d()).thenReturn(260L);
        when(fleet.active30d()).thenReturn(380L);
        when(fleet.new7d()).thenReturn(35L);
        when(fleet.new30d()).thenReturn(120L);
        when(fleet.versions()).thenReturn(List.of());
        CeFleetReader reader = mock(CeFleetReader.class);
        when(reader.read(any(), anyInt())).thenReturn(fleet);

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        // Five independent writes, and a scrape can land between any two of them. The churn card
        // is known - active30d, so writing known LAST is the ordering that can momentarily pair a
        // smaller ledger with a larger active count: a negative churn, the exact number the
        // single-statement ledger read exists to prevent, reintroduced one layer up. This way the
        // transient error is a churn that is briefly too large, and positive.
        InOrder order = inOrder(fleet);
        order.verify(fleet).total();
        order.verify(fleet).active30d();
    }

    @Test
    @DisplayName("a version count that changes is republished, not frozen at its first value")
    void versionValuesAreOverwritten() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(
                snapshot(100, 100, 0, 0, 100, List.of(new CeFleetReader.VersionCount("0.2.13", 100))),
                snapshot(100, 100, 0, 0, 100, List.of(new CeFleetReader.VersionCount("0.2.13", 40))));
        TickingClock clock = clock();
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock);

        metrics.refresh();
        clock.advance(EVERY_5_MIN);
        metrics.refresh();

        // MultiGauge.register(rows, false) keeps the FIRST value for an already-registered tag set.
        // Flip that boolean and every version bar freezes at its first observed count for the life
        // of the pod while looking perfectly healthy: a rollout would appear never to happen.
        assertThat(gauges(registry, CeInstallFleetMetrics.BY_VERSION, "version"))
                .containsEntry("0.2.13", 40.0);
    }

    @Test
    @DisplayName("a version that disappears from the fleet stops being published")
    void staleVersionSeriesAreRemoved() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(
                snapshot(50, 50, 0, 0, 50, List.of(new CeFleetReader.VersionCount("0.2.12", 50))),
                snapshot(50, 50, 0, 0, 50, List.of(new CeFleetReader.VersionCount("0.2.13", 50))));
        TickingClock clock = clock();
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock);

        metrics.refresh();
        clock.advance(EVERY_5_MIN);
        metrics.refresh();

        // Registering gauges one by one would leave 0.2.12 on the chart for ever at its last value,
        // so a fully rolled-out release would look like it still had installs on it.
        assertThat(gauges(registry, CeInstallFleetMetrics.BY_VERSION, "version"))
                .containsOnlyKeys("0.2.13");
    }

    @Test
    @DisplayName("installs beyond the tracked versions are published as one 'other' bar")
    void theTailIsFoldedIntoOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        List<CeFleetReader.VersionCount> top = new java.util.ArrayList<>();
        for (int i = 0; i < CeInstallFleetMetrics.TRACKED_VERSIONS; i++) {
            top.add(new CeFleetReader.VersionCount("9." + i + ".0", 10));
        }
        // 250 installs in the 30-day window; the top ten account for 100, so 150 are in the tail.
        CeFleetReader reader = readerReturning(snapshot(200, 250, 5, 20, 300, top));

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        Map<String, Double> byVersion = gauges(registry, CeInstallFleetMetrics.BY_VERSION, "version");
        // The label is caller-supplied: an anonymous caller can mint installs reporting any number
        // of distinct published-looking versions, and one series each is a cardinality bomb. The
        // bound has to hold whatever arrives, which is why the tail is summed rather than dropped.
        assertThat(byVersion).hasSize(CeInstallFleetMetrics.TRACKED_VERSIONS + 1);
        assertThat(byVersion).containsEntry("other", 150.0);
        assertThat(byVersion).containsEntry("9.0.0", 10.0);
    }

    @Test
    @DisplayName("no 'other' bar when every install is already accounted for")
    void noOtherBarWhenTheTailIsEmpty() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(snapshot(50, 50, 5, 10, 50,
                List.of(new CeFleetReader.VersionCount("0.2.13", 30),
                        new CeFleetReader.VersionCount("0.2.12", 20))));

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        // A permanent "other: 0" bar would sit on the dashboard implying a tail that is not there.
        assertThat(gauges(registry, CeInstallFleetMetrics.BY_VERSION, "version"))
                .containsOnlyKeys("0.2.13", "0.2.12");
    }

    @Test
    @DisplayName("the age of the numbers is published, so a frozen refresh is visible")
    void publishesLastSuccess() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(snapshot(1, 1, 1, 1, 1, List.of()));

        new CeInstallFleetMetrics(reader, registry, clock()).refresh();

        // The values are deliberately kept on failure, which is only safe if something says how old
        // they are: otherwise a refresh failing for hours looks exactly like a stable fleet.
        assertThat(single(registry, CeInstallFleetMetrics.LAST_SUCCESS))
                .isEqualTo(NOW.getEpochSecond());
    }

    @Test
    @DisplayName("a failed refresh keeps the last values, warns, and does not re-stamp their age")
    void failedRefreshKeepsValuesAndWarns() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(snapshot(260, 380, 35, 120, 412,
                List.of(new CeFleetReader.VersionCount("0.2.13", 200))));
        TickingClock clock = clock();
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock);
        metrics.refresh();
        double stampedAt = single(registry, CeInstallFleetMetrics.LAST_SUCCESS);
        clock.advance(EVERY_5_MIN);

        // doThrow, not when(...).thenThrow: the latter CALLS the mock to build the stub, which runs
        // the answer already registered on this method with null arguments.
        doThrow(new IllegalStateException("statement timeout")).when(reader).read(any(), anyInt());
        Logger logger = (Logger) LoggerFactory.getLogger(CeInstallFleetMetrics.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Level original = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(events);
        try {
            assertThatCode(metrics::refresh).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(events);
            logger.setLevel(original);
        }

        // A gauge dropping to 0 during a database blip is indistinguishable on a graph from a fleet
        // that vanished, and this is exactly the metric someone would panic about.
        assertThat(gauges(registry, CeInstallFleetMetrics.ACTIVE, "window")).containsEntry("7d", 260.0);
        assertThat(gauges(registry, CeInstallFleetMetrics.BY_VERSION, "version"))
                .containsEntry("0.2.13", 200.0);
        // The stamp must NOT move, or the one signal saying the numbers are stale would call them
        // fresh at the exact moment they stop being fresh.
        assertThat(single(registry, CeInstallFleetMetrics.LAST_SUCCESS)).isEqualTo(stampedAt);
        // WARN, like both sibling schedulers: at DEBUG a permanently frozen dashboard is silent.
        assertThat(events.list).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("not refreshed"));
    }

    @Test
    @DisplayName("an Error is not swallowed, so the scheduled task is allowed to die loudly")
    void anErrorPropagates() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = mock(CeFleetReader.class);
        doThrow(new OutOfMemoryError("heap")).when(reader).read(any(), anyInt());
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock());

        // RuntimeException only, deliberately. Widening the catch to Throwable would keep feeding
        // a dashboard from a JVM that is already failing, and would hide the one condition where
        // stopping is the right answer. The scheduler cancelling the task is visible through
        // LAST_SUCCESS and the Metrics age panel; a swallowed Error is visible nowhere.
        assertThatThrownBy(metrics::refresh).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("a refresh too soon after the last one is skipped, and said once")
    void refreshesFasterThanTheFloorAreSkipped() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(
                snapshot(10, 10, 0, 0, 10, List.of()),
                snapshot(99, 99, 0, 0, 99, List.of()));
        TickingClock clock = clock();
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock);
        metrics.refresh();

        Logger logger = (Logger) LoggerFactory.getLogger(CeInstallFleetMetrics.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Level original = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(events);
        try {
            clock.advance(Duration.ofSeconds(1));
            metrics.refresh();
            clock.advance(Duration.ofSeconds(1));
            metrics.refresh();
        } finally {
            logger.detachAppender(events);
            logger.setLevel(original);
        }

        // The floor has to be enforced HERE rather than by clamping the configured interval, since
        // @Scheduled(fixedDelayString) reads that property itself: a clamped field would be a field
        // nothing consults, and a one-second interval in a values file would still run a count and
        // a group-by every second on the scheduler thread twenty other jobs share.
        assertThat(gauges(registry, CeInstallFleetMetrics.ACTIVE, "window")).containsEntry("7d", 10.0);
        verify(reader).read(any(), anyInt());
        // Once, not per tick: the warning must not become the log volume it protects against.
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("floor");
            // With toMinutes() instead of toSeconds() this reads "below the 1s floor" while the
            // floor is 60s, sending the operator to look for a config value that is already fine.
            assertThat(event.getFormattedMessage())
                    .contains(String.valueOf(CeInstallFleetMetrics.MIN_INTERVAL.toSeconds()) + "s");
        });
    }

    @Test
    @DisplayName("the floor admits a refresh at exactly MIN_INTERVAL and skips one a second short")
    void theFloorBoundaryIsExact() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader shortOfIt = readerReturning(
                snapshot(10, 10, 0, 0, 10, List.of()), snapshot(99, 99, 0, 0, 99, List.of()));
        TickingClock a = clock();
        CeInstallFleetMetrics tooSoon = new CeInstallFleetMetrics(shortOfIt, registry, a);
        tooSoon.refresh();
        a.advance(CeInstallFleetMetrics.MIN_INTERVAL.minusSeconds(1));
        tooSoon.refresh();
        assertThat(gauges(registry, CeInstallFleetMetrics.ACTIVE, "window")).containsEntry("7d", 10.0);

        MeterRegistry onTime = new SimpleMeterRegistry();
        CeFleetReader exactly = readerReturning(
                snapshot(10, 10, 0, 0, 10, List.of()), snapshot(99, 99, 0, 0, 99, List.of()));
        TickingClock b = clock();
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(exactly, onTime, b);
        metrics.refresh();
        b.advance(CeInstallFleetMetrics.MIN_INTERVAL);
        metrics.refresh();

        // Both halves, because only the pair pins the comparison: with < the boundary refreshes and
        // with <= it does not, and a one-second-short tick is what a real scheduler produces.
        assertThat(gauges(onTime, CeInstallFleetMetrics.ACTIVE, "window")).containsEntry("7d", 99.0);
    }

    @Test
    @DisplayName("the floor is fixed, not sliding: a skipped refresh does not push the next one out")
    void theFloorDoesNotSlide() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(
                snapshot(10, 10, 0, 0, 10, List.of()),
                snapshot(99, 99, 0, 0, 99, List.of()));
        TickingClock clock = clock();
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock);
        metrics.refresh();

        clock.advance(Duration.ofSeconds(40));
        metrics.refresh();
        clock.advance(Duration.ofSeconds(40));
        metrics.refresh();

        // Stamping lastAttempt on a SKIPPED call turns the floor into a sliding window: under a
        // sub-floor configured interval the refresh would then starve for ever instead of admitting
        // one a minute, and both plain throttle tests still pass. 40s + 40s is 80s from the first
        // admitted refresh, so the third call must go through.
        assertThat(gauges(registry, CeInstallFleetMetrics.ACTIVE, "window")).containsEntry("7d", 99.0);
    }

    @Test
    @DisplayName("a clock that steps backwards does not freeze the refresh")
    void aBackwardsClockStepDoesNotFreezeTheRefresh() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(
                snapshot(10, 10, 0, 0, 10, List.of()),
                snapshot(99, 99, 0, 0, 99, List.of()));
        TickingClock clock = clock();
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock);
        metrics.refresh();

        clock.advance(Duration.ofHours(-2));
        metrics.refresh();

        // This is wall-clock time, so an NTP step backwards of two hours makes the elapsed duration
        // NEGATIVE, which is below any floor: without the isNegative guard every refresh would be
        // skipped for two hours and the whole metric family would silently stop moving, which is
        // the exact failure the class is written to prevent.
        assertThat(gauges(registry, CeInstallFleetMetrics.ACTIVE, "window")).containsEntry("7d", 99.0);
    }

    @Test
    @DisplayName("a refresh at the configured interval is not skipped")
    void refreshesAtTheConfiguredIntervalAreKept() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CeFleetReader reader = readerReturning(
                snapshot(10, 10, 0, 0, 10, List.of()),
                snapshot(99, 99, 0, 0, 99, List.of()));
        TickingClock clock = clock();
        CeInstallFleetMetrics metrics = new CeInstallFleetMetrics(reader, registry, clock);

        metrics.refresh();
        clock.advance(EVERY_5_MIN);
        metrics.refresh();

        // The guard must throttle, not stop: an inverted comparison would freeze every gauge after
        // the first refresh, which looks exactly like a fleet that never changes.
        assertThat(gauges(registry, CeInstallFleetMetrics.ACTIVE, "window")).containsEntry("7d", 99.0);
    }
}
