package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeReleaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the property KEYS the cutover depends on.
 *
 * <p>Every other test in this feature constructs these beans with explicit constructor arguments,
 * so the `@Value` keys themselves had no coverage at all: renaming
 * {@code ce.release.latest-version} to anything else left the whole suite green while silently
 * dropping the pin in production. The pin is what answers the feed across the two-deploy cutover
 * and what the bootstrap copies into the row, so losing it takes the fleet to "no release".
 *
 * <p>The deployment sets these as environment variables ({@code CE_RELEASE_LATESTVERSION}), which
 * Spring maps onto the dotted keys by relaxed binding. Asserting the dotted key is what makes that
 * mapping meaningful: it is the half this repo owns.
 */
class CeReleasePropertyBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CeReleaseStore.class, () -> new CeReleaseStore(mock(CeReleaseRepository.class)));

    @Test
    @DisplayName("the feed reads ce.release.latest-version, ce.release.url and ce.release.security")
    void feedReadsTheDocumentedKeys() {
        runner.withBean(CeReleaseController.class)
                .withPropertyValues(
                        "ce.release.latest-version=0.9.9",
                        "ce.release.url=https://example.test/v0.9.9",
                        "ce.release.security=true",
                        "ce.release.published-at=2026-07-31T00:00:00Z")
                .run(context -> {
                    CeReleaseController.LatestRelease r =
                            context.getBean(CeReleaseController.class).latest(null, null);
                    assertThat(r.latestVersion()).isEqualTo("0.9.9");
                    assertThat(r.releaseUrl()).isEqualTo("https://example.test/v0.9.9");
                    assertThat(r.securityFix()).isTrue();
                    assertThat(r.publishedAt()).isEqualTo("2026-07-31T00:00:00Z");
                });
    }

    @Test
    @DisplayName("the same keys reach the feed through their environment-variable spelling")
    void feedReadsTheEnvironmentSpelling() {
        // CE_RELEASE_LATESTVERSION is what values-prod.yaml actually sets; if relaxed binding ever
        // stopped mapping it onto ce.release.latest-version the pin would silently do nothing.
        // Registered as a SystemEnvironmentPropertySource on purpose: withPropertyValues adds an
        // ordinary source, for which the relaxed uppercase mapping does NOT apply - so that
        // spelling would resolve to nothing and the test would prove the opposite of its name.
        runner.withBean(CeReleaseController.class)
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.SystemEnvironmentPropertySource(
                                    org.springframework.core.env.StandardEnvironment
                                            .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                    java.util.Map.of("CE_RELEASE_LATESTVERSION", "0.9.9")));
                    // Boot attaches this in a real application; the bare test context does not, and
                    // it is what performs the dashes-removed uppercase mapping.
                    org.springframework.boot.context.properties.source.ConfigurationPropertySources
                            .attach(context.getEnvironment());
                })
                .run(context -> assertThat(context.getBean(CeReleaseController.class).latest(null, null).latestVersion())
                        .isEqualTo("0.9.9"));
    }

    @Test
    @DisplayName("the config bootstrap reads the same key the feed does")
    void bootstrapReadsTheSameKey() {
        // If these two ever drifted apart, the feed would serve a pin the bootstrap never copies
        // into the row, and blanking the pin would take the fleet to "no release".
        CeReleaseStore store = mock(CeReleaseStore.class);
        new ApplicationContextRunner()
                .withBean(CeReleaseStore.class, () -> store)
                .withBean(CeReleaseConfigBootstrap.class)
                .withPropertyValues(
                        "ce.release.latest-version=0.9.9",
                        "ce.release.url=https://example.test/v0.9.9",
                        "ce.release.security=true",
                        "ce.release.published-at=2026-07-31T00:00:00Z")
                .run(context -> {
                    context.getBean(CeReleaseConfigBootstrap.class).seedFromConfigIfEmpty();
                    // The FULL tuple: asserting only the version made a broken url/security key
                    // indistinguishable from a deliberate null, and a seeded row without its
                    // release-notes URL shows the whole fleet "Update available" with a dead link.
                    org.mockito.Mockito.verify(store).announceIfAbsent(
                            "0.9.9", "https://example.test/v0.9.9", true, "2026-07-31T00:00:00Z");
                });
    }

    @Test
    @DisplayName("the announce endpoint reads ce.release.announce.secret")
    void announceReadsTheSecretKey() {
        // Renaming this key left the whole suite green while, in production, the constructor gets
        // "" and the fail-closed branch 401s EVERY announcement forever: the release ships and the
        // fleet is never told, which is the silent drift this feature exists to delete. Every
        // other test builds this controller with explicit constructor args, so the @Value site had
        // no coverage at all.
        runner.withBean(CeReleaseAnnounceController.class)
                .withPropertyValues(
                        "ce.release.announce.enabled=true",
                        "ce.release.announce.secret=the-shared-secret")
                .run(context -> {
                    CeReleaseAnnounceController controller =
                            context.getBean(CeReleaseAnnounceController.class);
                    assertThat(controller.announce("the-shared-secret",
                            new CeReleaseAnnounceController.AnnounceRequest(
                                    "0.9.9", "https://example.test/v0.9.9", false, null, null))
                            .getStatusCode())
                            .as("a matching secret must not be rejected")
                            .isNotEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    assertThat(controller.announce("wrong",
                            new CeReleaseAnnounceController.AnnounceRequest(
                                    "0.9.9", null, false, null, null))
                            .getStatusCode())
                            .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    @DisplayName("the announce endpoint's pin guard reads ce.release.latest-version")
    void announceReadsThePinKey() {
        // The 409 that tells a release run in ONE request that its write would be masked. If the
        // key stops binding, the guard dies silently: the job gets a 200, writes the row, then
        // burns 200s polling a pinned feed and fails with a diagnosis that no longer applies.
        runner.withBean(CeReleaseAnnounceController.class)
                .withPropertyValues(
                        "ce.release.announce.enabled=true",
                        "ce.release.announce.secret=s",
                        "ce.release.latest-version=0.2.7")
                .run(context -> {
                    var response = context.getBean(CeReleaseAnnounceController.class).announce("s",
                            new CeReleaseAnnounceController.AnnounceRequest(
                                    "0.9.9", null, false, null, null));
                    assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(response.getBody()).containsEntry("override", "0.2.7");
                });
    }

    @Test
    @DisplayName("with no property set the feed advertises no release, which is every CE install")
    void noPropertiesMeansNoRelease() {
        runner.withBean(CeReleaseController.class)
                .run(context -> assertThat(context.getBean(CeReleaseController.class).latest(null, null).latestVersion())
                        .isNull());
    }
}
