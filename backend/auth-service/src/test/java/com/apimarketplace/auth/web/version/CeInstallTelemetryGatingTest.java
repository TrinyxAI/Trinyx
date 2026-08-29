package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeInstallPingRepository;
import com.apimarketplace.auth.repository.CeInstallRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the four switches this feature is steered by, against the bean each one is supposed to
 * create or withhold.
 *
 * <p>These are the failure modes no other test can see. A condition that never matches leaves the
 * feature silently inert, with a fleet count stuck at zero and nothing in the logs; a condition that
 * always matches arms cloud-side collection on a customer-owned box. Both ship green.
 *
 * <p>The property spellings matter as much as the logic: nothing sets these as dotted properties,
 * they arrive from the environment ({@code CE_VERSIONCHECK_SENDINSTALLID},
 * {@code CE_INSTALLS_TELEMETRY_ENABLED}) through Spring's relaxed binding, and a documented opt-out
 * that binds to nothing is worse than no opt-out at all. The last two tests therefore go through a
 * real {@link SystemEnvironmentPropertySource}: {@code withPropertyValues} adds an ORDINARY source,
 * for which the dashes-removed uppercase mapping does not apply, so asserting the env spelling
 * through it would prove the opposite of its name. Same reasoning, and same mechanics, as
 * {@code CeReleasePropertyBindingTest.feedReadsTheEnvironmentSpelling}.
 */
class CeInstallTelemetryGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CeInstallRepository.class, () -> mock(CeInstallRepository.class))
            .withBean(CeInstallPingRepository.class, CeInstallTelemetryGatingTest::emptyLedger)
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                    io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
            .withUserConfiguration(CeInstallIdProvider.class, CeInstallPingRecorder.class,
                    CeInstallStatsController.class, CeInstallPingRetentionScheduler.class,
                    CeFleetReader.class, CeInstallFleetMetrics.class,
                    CeVersionCheckScheduler.class)
            .withBean(VersionUpdateService.class, VersionUpdateService::new)
            .withBean(ReleaseFeedClient.class, () -> current -> null);

    /** An empty ledger: the counts statement still returns its one row, of zeroes. */
    private static CeInstallPingRepository emptyLedger() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        // An aggregate with no GROUP BY always returns exactly one row, so a bare mock answering
        // an empty list is not a state production can reach; without this the fleet read 503s here
        // for a reason that has nothing to do with what these tests are about.
        when(repository.fleetCounts(any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L, 0L}));
        return repository;
    }

    @Test
    @DisplayName("CE sends its install id by default")
    void ceSendsInstallIdByDefault() {
        runner.withPropertyValues("auth.mode=embedded")
                .run(context -> assertThat(context).hasSingleBean(CeInstallIdProvider.class));
    }

    @Test
    @DisplayName("ce.version-check.send-install-id=false removes the identity bean entirely")
    void optOutRemovesTheProvider() {
        // The opt-out is the bean's absence rather than a flag some caller must remember to read,
        // so nothing downstream can accidentally keep sending it.
        runner.withPropertyValues("auth.mode=embedded", "ce.version-check.send-install-id=false")
                .run(context -> assertThat(context).doesNotHaveBean(CeInstallIdProvider.class));
    }

    @Test
    @DisplayName("the cloud never identifies itself: no identity bean outside the embedded edition")
    void cloudHasNoInstallIdentity() {
        runner.withPropertyValues("auth.mode=keycloak")
                .run(context -> assertThat(context).doesNotHaveBean(CeInstallIdProvider.class));
    }

    @Test
    @DisplayName("with auth.mode unset (cloud default) there is still no identity bean")
    void defaultModeIsCloudAndHasNoIdentity() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CeInstallIdProvider.class));
    }

    @Test
    @DisplayName("collection is off unless ce.installs.telemetry.enabled is explicitly true")
    void collectionIsOffByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CeInstallPingRecorder.class);
            assertThat(context).doesNotHaveBean(CeInstallStatsController.class);
            assertThat(context).doesNotHaveBean(CeInstallPingRetentionScheduler.class);
            assertThat(context).doesNotHaveBean(CeInstallFleetMetrics.class);
            assertThat(context).doesNotHaveBean(CeFleetReader.class);
        });
    }

    @Test
    @DisplayName("collection stays off on a self-hosted enterprise install running keycloak mode")
    void keycloakAloneDoesNotArmCollection() {
        // The trap CeReleaseAnnounceController documents: keycloak is also declared for self-hosted
        // enterprise, so gating collection on auth.mode would arm it on a customer's own machine.
        runner.withPropertyValues("auth.mode=keycloak")
                .run(context -> assertThat(context).doesNotHaveBean(CeInstallPingRecorder.class));
    }

    @Test
    @DisplayName("ce.installs.telemetry.enabled=true arms the collector and the fleet read together")
    void flagArmsCollectionAndRead() {
        runner.withPropertyValues("ce.installs.telemetry.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(CeInstallPingRecorder.class);
                    // An install that stores nothing must have nothing to serve, and vice versa:
                    // one flag, so the two cannot end up disagreeing.
                    assertThat(context).hasSingleBean(CeInstallStatsController.class);
                    // The purge is gated on the same flag: a ledger that collects must also be
                    // bounded, and one that collects nothing must run no purge.
                    assertThat(context).hasSingleBean(CeInstallPingRetentionScheduler.class);
                    // Same flag: the Grafana gauges must not exist where nothing is collected.
                    assertThat(context).hasSingleBean(CeInstallFleetMetrics.class);
                    // And the shared reader on the same flag again. Gate it any differently and
                    // both consumers become unsatisfiable dependencies, which is not a feature
                    // quietly missing: it is auth-service refusing to start.
                    assertThat(context).hasSingleBean(CeFleetReader.class);
                });
    }


    /** Adds {@code vars} the way the process environment would, relaxed binding included. */
    private ApplicationContextRunner withEnvironment(Map<String, Object> vars) {
        return runner.withInitializer(context -> {
            context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
            // Boot attaches this in a real application; a bare test context does not, and it is
            // what performs the mapping under test.
            ConfigurationPropertySources.attach(context.getEnvironment());
        });
    }

    @Test
    @DisplayName("the documented opt-out binds through its environment-variable spelling")
    void optOutBindsFromTheEnvironmentSpelling() {
        // CE_VERSIONCHECK_SENDINSTALLID is the spelling README-CE.md, .env.ce.example and all three
        // compose files tell an operator to set. If relaxed binding stopped mapping it onto
        // ce.version-check.send-install-id, every one of those documents would be a lie and the
        // install would keep identifying itself with nothing to show it had ignored the setting.
        withEnvironment(Map.of("AUTH_MODE", "embedded",
                        "CE_VERSIONCHECK_SENDINSTALLID", "false"))
                .run(context -> assertThat(context).doesNotHaveBean(CeInstallIdProvider.class));

        withEnvironment(Map.of("AUTH_MODE", "embedded"))
                .run(context -> assertThat(context).hasSingleBean(CeInstallIdProvider.class));
    }

    @Test
    @DisplayName("collection arms through the environment-variable spelling values-prod.yaml sets")
    void collectionArmsFromTheEnvironmentSpelling() {
        // CE_INSTALLS_TELEMETRY_ENABLED is what the chart sets. A spelling that bound to nothing
        // would leave the cloud collecting nothing, silently, with a green deploy.
        withEnvironment(Map.of("CE_INSTALLS_TELEMETRY_ENABLED", "true"))
                .run(context -> {
                    assertThat(context).hasSingleBean(CeInstallPingRecorder.class);
                    assertThat(context).hasSingleBean(CeInstallPingRetentionScheduler.class);
                    // Same flag: the Grafana gauges must not exist where nothing is collected.
                    assertThat(context).hasSingleBean(CeInstallFleetMetrics.class);
                    // And the shared reader on the same flag again. Gate it any differently and
                    // both consumers become unsatisfiable dependencies, which is not a feature
                    // quietly missing: it is auth-service refusing to start.
                    assertThat(context).hasSingleBean(CeFleetReader.class);
                });
    }

    @Test
    @DisplayName("the whole update check switches off through its environment-variable spelling")
    void updateCheckSwitchesOffFromTheEnvironmentSpelling() {
        // README-CE.md promises that with this set "nothing at all leaves your install on this
        // path". CE_VERSIONCHECK_ENABLED is added to all three compose files by this change, so
        // the spelling is part of its contract; a binding that resolved to nothing would leave
        // every install still polling while its operator believed otherwise.
        withEnvironment(Map.of("AUTH_MODE", "embedded", "CE_VERSIONCHECK_ENABLED", "false"))
                .run(context -> assertThat(context).doesNotHaveBean(CeVersionCheckScheduler.class));

        withEnvironment(Map.of("AUTH_MODE", "embedded"))
                .run(context -> assertThat(context).hasSingleBean(CeVersionCheckScheduler.class));
    }

    @Test
    @DisplayName("disabling the update check also removes the install identity")
    void disablingTheCheckAlsoStopsTheIdentity() {
        // Two flags, and the coarser one has to win. With no request going out there is nothing to
        // carry an id, and an install that polls nothing must not still read one and announce at
        // startup that it sends it with a daily check that no longer runs.
        withEnvironment(Map.of("AUTH_MODE", "embedded", "CE_VERSIONCHECK_ENABLED", "false"))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CeVersionCheckScheduler.class);
                    assertThat(context).doesNotHaveBean(CeInstallIdProvider.class);
                });
    }

    @Test
    @DisplayName("the fleet-read secret binds through its environment-variable spelling")
    void secretBindsFromTheEnvironmentSpelling() {
        // A spelling that bound to nothing would leave the secret blank, which fails closed, so the
        // symptom is "every fleet read 401s forever" with nothing saying why. That is precisely the
        // silent-death class this file exists to close, and it is the one property here that
        // security depends on.
        withEnvironment(Map.of("CE_INSTALLS_TELEMETRY_ENABLED", "true",
                        "CE_INSTALLS_TELEMETRY_SECRET", "a-secret-long-enough"))
                .run(context -> assertThat(context.getBean(CeInstallStatsController.class)
                        .stats("a-secret-long-enough").getStatusCode().value()).isEqualTo(200));
    }

    @Test
    @DisplayName("the numeric bounds bind through their environment-variable spellings")
    void boundsBindFromTheEnvironmentSpellings() {
        // These are what values-prod.yaml would set to change the ceiling or the budget. A spelling
        // that resolved to nothing would silently leave the shipped defaults in place, and the only
        // symptom is a bound that is not the one the operator configured.
        withEnvironment(Map.of("CE_INSTALLS_TELEMETRY_ENABLED", "true",
                        "CE_INSTALLS_TELEMETRY_MAXROWS", "7",
                        "CE_INSTALLS_TELEMETRY_MAXREFRESHESPERMINUTE", "13",
                        "CE_INSTALLS_TELEMETRY_MAXWRITESPERMINUTE", "9",
                        "CE_INSTALLS_TELEMETRY_MININTERVALHOURS", "11",
                        "CE_INSTALLS_TELEMETRY_RETENTIONDAYS", "365"))
                .run(context -> {
                    assertThat(boundOf(context.getBean(CeInstallPingRecorder.class), "maxRows"))
                            .isEqualTo(7L);
                    assertThat(boundOf(context.getBean(CeInstallPingRecorder.class), "maxWritesPerMinute"))
                            .isEqualTo(9);
                    // The one bound the docs call load-critical, and the only one whose spelling
                    // had no coverage anywhere.
                    assertThat(boundOf(context.getBean(CeInstallPingRecorder.class), "maxRefreshesPerMinute"))
                            .isEqualTo(13);
                    assertThat(boundOf(context.getBean(CeInstallPingRecorder.class), "minInterval"))
                            .isEqualTo(java.time.Duration.ofHours(11));
                    assertThat(boundOf(context.getBean(CeInstallPingRetentionScheduler.class), "retention"))
                            .isEqualTo(java.time.Duration.ofDays(365));
                });
    }

    /** Reads a configured bound off a bean; none of them are exposed, by design. */
    private static Object boundOf(Object bean, String field) throws Exception {
        java.lang.reflect.Field f = bean.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(bean);
    }
}
