package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the cloud-side {@link CeReleaseController} release feed.
 *
 * <p>The resolution order is the contract: {@code ce.release.*} config wins when set (the manual
 * pin / rollback / hide escape hatch), otherwise the announced row, otherwise nulls. Getting that
 * order wrong is silent: the feed keeps answering, just with the wrong release.
 */
class CeReleaseControllerTest {

    /** No store bean at all, i.e. a context without persistence (CE, slice tests). */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<CeReleaseStore> noStore() {
        ObjectProvider<CeReleaseStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    /** No fleet collection, which is every deployment except the cloud one. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<CeInstallPingRecorder> noRecorder() {
        ObjectProvider<CeInstallPingRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    /** A store bean holding {@code announced} (may be null for "nothing announced"). */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<CeReleaseStore> storeWith(CeReleaseStore.Announced announced) {
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenReturn(announced);
        ObjectProvider<CeReleaseStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    @Test
    @DisplayName("configured release is advertised verbatim")
    void configuredRelease() {
        CeReleaseController controller = new CeReleaseController(
                "0.3.0", "https://example.test/releases/0.3.0", true, "2026-06-25T09:00:00Z", noStore(), noRecorder());

        LatestRelease r = controller.latest("0.2.0", null);

        assertThat(r.latestVersion()).isEqualTo("0.3.0");
        assertThat(r.releaseUrl()).isEqualTo("https://example.test/releases/0.3.0");
        assertThat(r.securityFix()).isTrue();
        assertThat(r.publishedAt()).isEqualTo("2026-06-25T09:00:00Z");
    }

    @Test
    @DisplayName("unconfigured (blank) release advertises no version (nulls), not empty strings")
    void unconfiguredRelease() {
        CeReleaseController controller = new CeReleaseController("", "  ", false, "", noStore(), noRecorder());

        LatestRelease r = controller.latest(null, null);

        assertThat(r.latestVersion()).isNull();
        assertThat(r.releaseUrl()).isNull();
        assertThat(r.securityFix()).isFalse();
        assertThat(r.publishedAt()).isNull();
    }

    @Test
    @DisplayName("config WINS over a different announced row, so a pin cannot be overwritten by a release")
    void configOverridesAnnouncedRow() {
        // The escape hatch. If the row won instead, pinning or hiding a bad release would be
        // impossible without a cloud deploy, which is the coupling this whole change removes.
        CeReleaseController controller = new CeReleaseController(
                "0.3.0", "https://example.test/pinned", false, null,
                storeWith(new CeReleaseStore.Announced("9.9.9", "https://example.test/announced", true, null)), noRecorder());

        LatestRelease r = controller.latest(null, null);

        assertThat(r.latestVersion()).isEqualTo("0.3.0");
        assertThat(r.releaseUrl()).isEqualTo("https://example.test/pinned");
        assertThat(r.securityFix()).isFalse();
    }

    @Test
    @DisplayName("with config blank the announced row is advertised")
    void announcedRowIsUsedWhenConfigBlank() {
        CeReleaseController controller = new CeReleaseController(
                "", "", false, "",
                storeWith(new CeReleaseStore.Announced(
                        "0.4.1", "https://example.test/releases/0.4.1", true, "2026-07-30T10:00:00Z")), noRecorder());

        LatestRelease r = controller.latest("0.2.7", null);

        assertThat(r.latestVersion()).isEqualTo("0.4.1");
        assertThat(r.releaseUrl()).isEqualTo("https://example.test/releases/0.4.1");
        assertThat(r.securityFix()).isTrue();
        assertThat(r.publishedAt()).isEqualTo("2026-07-30T10:00:00Z");
    }

    @Test
    @DisplayName("a stored tag-style 'v0.4.1' is advertised as '0.4.1'")
    void leadingVIsStripped() {
        // Tags carry the v; the CE comparison and the version card do not. Advertising "v0.4.1"
        // against a running "0.4.1" is the kind of mismatch that shows a permanent update banner.
        CeReleaseController controller = new CeReleaseController(
                "", "", false, "",
                storeWith(new CeReleaseStore.Announced("v0.4.1", null, false, null)), noRecorder());

        assertThat(controller.latest(null, null).latestVersion()).isEqualTo("0.4.1");
    }

    @Test
    @DisplayName("a PINNED tag-style 'v0.3.0' in config is also advertised as '0.3.0'")
    void leadingVIsStrippedOnTheConfigPathToo() {
        // The config path used to skip the strip. An operator pinning the tag verbatim would then
        // ship "v0.3.0" to the fleet, which never equals a running "0.3.0": a permanent banner
        // that no upgrade clears.
        CeReleaseController controller = new CeReleaseController("v0.3.0", null, false, null, noStore(), noRecorder());

        assertThat(controller.latest(null, null).latestVersion()).isEqualTo("0.3.0");
    }

    @Test
    @DisplayName("a store that cannot answer PROPAGATES rather than degrading into a null version")
    void lookupFailurePropagates() {
        // The whole safety argument. Catching this and returning nulls would answer 200-with-null,
        // which every CE binary shipped before the null-guard fix stores over its good status,
        // blanking the update banner fleet-wide. Letting it 500 makes those pollers throw and keep
        // what they had. A future try/catch here would break the fleet and turn no other test red.
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenThrow(new CeReleaseStore.LookupUnavailableException(new IllegalStateException("db")));
        @SuppressWarnings("unchecked")
        ObjectProvider<CeReleaseStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);

        CeReleaseController controller = new CeReleaseController("", "", false, "", provider, noRecorder());

        assertThatThrownBy(() -> controller.latest(null, null))
                .isInstanceOf(CeReleaseStore.LookupUnavailableException.class);
    }

    @Test
    @DisplayName("a configured override answers even when the store cannot be read at all")
    void configOverrideSurvivesAStoreOutage() {
        // The override is read before the store is consulted, so a pinned value keeps the feed
        // alive through a database outage.
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenThrow(new CeReleaseStore.LookupUnavailableException(new IllegalStateException("db")));
        @SuppressWarnings("unchecked")
        ObjectProvider<CeReleaseStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);

        CeReleaseController controller = new CeReleaseController("0.3.0", null, false, null, provider, noRecorder());

        assertThat(controller.latest(null, null).latestVersion()).isEqualTo("0.3.0");
    }

    @Test
    @DisplayName("a half-deleted pin ('v') advertises nulls on the CONFIG branch, not an empty version")
    void halfDeletedPinAdvertisesNulls() {
        // The regression the previous round missed: the strip was fixed on the row branch only,
        // three lines away. "v" strips to "", and an empty version on the wire blanks the update
        // banner on every install shipped before the poller's null-guard.
        for (String pin : new String[] { "v", "V", " v " }) {
            CeReleaseController controller = new CeReleaseController(
                    pin, "https://example.test/x", true, null, noStore(), noRecorder());
            assertThat(controller.latest(null, null).latestVersion()).as("pin %s", pin).isNull();
        }
    }

    @Test
    @DisplayName("a stored 'v' advertises nulls on the ROW branch too")
    void storedBareVAdvertisesNulls() {
        CeReleaseController controller = new CeReleaseController(
                "", "", false, "", storeWith(new CeReleaseStore.Announced("v", "https://example.test/x", true, null)), noRecorder());

        assertThat(controller.latest(null, null).latestVersion()).isNull();
    }

    @Test
    @DisplayName("an unparseable pin is not advertised verbatim")
    void unparseablePinIsNotAdvertised() {
        // "latest" was served as-is: a CE install compares it numerically, finds nothing, and the
        // pin silently does nothing while looking set.
        for (String pin : new String[] { "latest", "2026.07.30.1", "next" }) {
            CeReleaseController controller = new CeReleaseController(pin, null, false, null, noStore(), noRecorder());
            assertThat(controller.latest(null, null).latestVersion()).as("pin %s", pin).isNull();
        }
    }

    @Test
    @DisplayName("an UNUSABLE pin does not hide a good announced release")
    void unusablePinFallsThroughToTheRow() {
        // The defect the previous round locked in as correct: an unreadable pin returned "no
        // release" WITHOUT consulting the row, so one typo both hid an available release and
        // emitted the payload the whole design exists to avoid. An override that cannot be read
        // is not an override.
        for (String pin : new String[] { "v", "V", " v ", "latest", "2026.07.30.1" }) {
            CeReleaseController controller = new CeReleaseController(
                    pin, "https://example.test/pinned", false, null,
                    storeWith(new CeReleaseStore.Announced("0.4.1", "https://example.test/4", true, null)), noRecorder());

            assertThat(controller.latest(null, null).latestVersion()).as("pin %s", pin).isEqualTo("0.4.1");
            assertThat(controller.latest(null, null).releaseUrl()).as("pin %s", pin).isEqualTo("https://example.test/4");
        }
    }

    @Test
    @DisplayName("a USABLE pin still wins over the announced row")
    void usablePinStillWins() {
        CeReleaseController controller = new CeReleaseController(
                "0.3.0", "https://example.test/pinned", false, null,
                storeWith(new CeReleaseStore.Announced("0.4.1", "https://example.test/4", true, null)), noRecorder());

        assertThat(controller.latest(null, null).latestVersion()).isEqualTo("0.3.0");
    }

    @Test
    @DisplayName("blank url and publishedAt on the row are advertised as null, not as empty strings")
    void blankRowFieldsBecomeNulls() {
        // The CE card renders these directly; an empty string is a link to nowhere rather than an
        // absent link. Dropping the blank-to-null on these two fields turned nothing red.
        CeReleaseController controller = new CeReleaseController(
                "", "", false, "",
                storeWith(new CeReleaseStore.Announced("0.4.1", "   ", false, "  ")), noRecorder());

        LatestRelease r = controller.latest(null, null);

        assertThat(r.latestVersion()).isEqualTo("0.4.1");
        assertThat(r.releaseUrl()).isNull();
        assertThat(r.publishedAt()).isNull();
    }

    @Test
    @DisplayName("the feed and the announce guard agree on what counts as a pin, including double-v")
    void feedAndAnnounceGuardAgreeOnPins() {
        // They disagreed: the feed stripped one leading v and THEN parsed (parseCore strips one
        // too), so "vv0.2.7" was an active pin for the feed and no pin at all for the write path.
        // The write then returned 200 and stored a row the feed would never serve - the masked
        // announcement the 409 exists to prevent. Single-v inputs cannot discriminate, which is
        // why the fix shipped with no coverage.
        for (String pin : new String[] { "vv0.2.7", "vV0.2.7", "v0.2.7", "V0.2.7", "0.2.7" }) {
            boolean feedTreatsAsPin = new CeReleaseController(
                    pin, null, false, null,
                    storeWith(new CeReleaseStore.Announced("0.4.1", null, false, null)), noRecorder())
                    .latest(null, null).latestVersion().equals("0.4.1") ? false : true;

            CeReleaseStore store = mock(CeReleaseStore.class);
            when(store.current()).thenReturn(new CeReleaseStore.Announced("0.4.1", null, false, null));
            when(store.announce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
            boolean announceTreatsAsPin = new CeReleaseAnnounceController(store, "s", pin)
                    .announce("s", new CeReleaseAnnounceController.AnnounceRequest(
                            "9.9.9", null, false, null, null))
                    .getStatusCode() == org.springframework.http.HttpStatus.CONFLICT;

            assertThat(announceTreatsAsPin)
                    .as("pin %s: feed says active=%s, announce says active=%s", pin, feedTreatsAsPin, announceTreatsAsPin)
                    .isEqualTo(feedTreatsAsPin);
        }
    }

    @Test
    @DisplayName("config blank and nothing announced advertises nulls, exactly as CE does today")
    void nothingAnnouncedAdvertisesNulls() {
        CeReleaseController controller = new CeReleaseController("", "", false, "", storeWith(null), noRecorder());

        LatestRelease r = controller.latest(null, null);

        assertThat(r.latestVersion()).isNull();
        assertThat(r.releaseUrl()).isNull();
        assertThat(r.securityFix()).isFalse();
    }

    @Test
    @DisplayName("an announced row with a blank version advertises nulls rather than an empty version")
    void blankAnnouncedVersionAdvertisesNulls() {
        // A 200 carrying an empty version is what blanks the update banner on already-shipped CE
        // binaries, so the cloud must never emit one.
        CeReleaseController controller = new CeReleaseController(
                "", "", false, "",
                storeWith(new CeReleaseStore.Announced("  ", "https://example.test/x", true, null)), noRecorder());

        LatestRelease r = controller.latest(null, null);

        assertThat(r.latestVersion()).isNull();
        assertThat(r.releaseUrl()).isNull();
        assertThat(r.securityFix()).isFalse();
    }
}
