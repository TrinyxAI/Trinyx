package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseAnnounceController.AnnounceRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the internal announce endpoint. Every rejection path must leave the advertised
 * release UNCHANGED: this endpoint drives what the entire self-hosted fleet is told, and a bad write
 * cannot be undone by the installs that already polled it.
 */
class CeReleaseAnnounceControllerTest {

    private static final String SECRET = "s3cret-value";

    private static CeReleaseStore storeAdvertising(String version) {
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenReturn(
                version == null ? null : new CeReleaseStore.Announced(version, null, false, null));
        when(store.announce(any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(true);
        return store;
    }

    private static AnnounceRequest req(String version, Boolean force) {
        return new AnnounceRequest(version, "https://example.test/notes", false, null, force);
    }

    @Test
    @DisplayName("a valid newer version with the right secret is announced")
    void announcesNewerVersion() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        var response = controller.announce(SECRET, req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(store).announce("0.2.8", "https://example.test/notes", false, null, false);
    }

    @Test
    @DisplayName("a wrong secret is rejected and nothing is written")
    void wrongSecretRejected() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        var response = controller.announce("not-the-secret", req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a missing secret header is rejected and nothing is written")
    void missingSecretRejected() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        var response = controller.announce(null, req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("with no secret configured every call is rejected, so enabling without a secret fails closed")
    void blankConfiguredSecretRejectsEverything() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, "", null);

        // Presenting the same blank value must NOT authorize.
        assertThat(controller.announce("", req("0.2.8", null)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a real secret presented against a blank configured one is rejected")
    void nonBlankSecretAgainstBlankConfigRejected() {
        // The named risk is enabling the endpoint WITHOUT setting a secret. A caller then presents
        // something arbitrary; a naive equals() on two blanks would not catch this case at all.
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, "", null);

        assertThat(controller.announce("anything", req("0.2.8", null)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a null request object is refused with 400 rather than throwing (defensive; Spring rejects it first)")
    void missingBodyRefused() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        assertThat(controller.announce(SECRET, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a USABLE config override refuses the write immediately instead of letting it be masked")
    void activeOverrideRefusesTheWrite() {
        // Without this the release job writes the row, then spends 200 s polling a feed that keeps
        // answering the pinned value, fails, and its re-run is refused as going backwards. One
        // request now tells it exactly what is wrong.
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, "0.2.7");

        var response = controller.announce(SECRET, req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("override", "0.2.7");
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("an UNREADABLE pin is not treated as an active pin, so it cannot block a release")
    void unreadablePinDoesNotBlockAnnouncing() {
        // The feed ignores an unparseable pin and serves the row; this path treated any non-blank
        // string as an active override. A stray "v" therefore blocked every announcement with a
        // message claiming the feed was pinned, while the feed was not - and the documented
        // recovery was a cloud deploy, the very thing this feature removes.
        for (String pin : new String[] { "v", "V", " v ", "latest", "2026.07.30.1", "next" }) {
            CeReleaseStore store = storeAdvertising("0.2.7");
            CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, pin);

            assertThat(controller.announce(SECRET, req("0.2.8", null)).getStatusCode())
                    .as("pin %s", pin)
                    .isEqualTo(HttpStatus.OK);
            verify(store).announce("0.2.8", "https://example.test/notes", false, null, false);
        }
    }

    @Test
    @DisplayName("the pin refusal never depends on the database being reachable")
    void pinRefusalDoesNotDependOnTheDatabase() {
        // This used to assert `never()).current()`. That was the MECHANISM, and it is no longer
        // true: the 409 now reports the stored release, because while a pin is active nothing
        // else can tell an operator whether the row was seeded. The property that actually
        // matters is unchanged and is what this asserts - the refusal itself must not need the
        // database, so a pinned cloud mid-incident still gets the actionable 409 rather than a
        // 500. pinRefusalSurvivesADatabaseOutage covers the diagnostic's own degradation.
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenThrow(
                new CeReleaseStore.LookupUnavailableException(new IllegalStateException("db down")));
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, "0.2.7");

        var response = controller.announce(SECRET, req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("override", "0.2.7");
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("an unreadable STORED version does not wedge every future announcement")
    void unreadableStoredVersionDoesNotWedgeAnnouncing() {
        // isUpdateAvailable() is conservatively false when the left side cannot be parsed, so a
        // garbage row refused every non-forced announce for good - while the feed served no
        // release at the same time. Wedged in both directions, recoverable only with force.
        for (String stored : new String[] { "latest", "v", "  ", "2026.07.30.1" }) {
            CeReleaseStore store = storeAdvertising(stored);
            CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

            assertThat(controller.announce(SECRET, req("0.2.8", null)).getStatusCode())
                    .as("stored %s", stored)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("the pin refusal reports the STORED release, which is the only way to see it during the cutover")
    void pinRefusalReportsTheStoredRelease() {
        // While the pin is active the public feed answers the pin, so nothing else can tell an
        // operator whether the row was seeded - and blanking the pin over an unseeded row makes
        // the feed answer "no release", which every pre-null-guard CE binary stores over its good
        // status. This 409 is the verification step for the cutover.
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, "0.2.7");

        var response = controller.announce(SECRET, req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("stored", "0.2.7");
    }

    @Test
    @DisplayName("an unseeded row is reported as 'none', which is the signal to STOP the cutover")
    void pinRefusalReportsAnUnseededRow() {
        CeReleaseStore store = storeAdvertising(null);
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, "0.2.7");

        var response = controller.announce(SECRET, req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("stored", "none");
    }

    @Test
    @DisplayName("a database outage still yields the actionable 409, not a 500")
    void pinRefusalSurvivesADatabaseOutage() {
        // The refusal is decided without the database on purpose. Adding this diagnostic must not
        // undo that: an operator mid-incident needs the 409 that says "blank the pin", not a 500.
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenThrow(
                new CeReleaseStore.LookupUnavailableException(new IllegalStateException("db down")));
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, "0.2.7");

        var response = controller.announce(SECRET, req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("stored", "unavailable");
    }

    @Test
    @DisplayName("a blank configured override is not treated as an active pin")
    void blankOverrideIsNotAPin() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, "   ");

        assertThat(controller.announce(SECRET, req("0.2.8", null)).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(store).announce("0.2.8", "https://example.test/notes", false, null, false);
    }

    @Test
    @DisplayName("a store that refuses the write at commit time is reported as 409, not as success")
    void writeTimeRefusalIsReported() {
        // The cross-replica case the two-layer guard exists for: the cached read said "newer", the
        // row disagreed. Ignoring the return value reported success while nothing was written.
        CeReleaseStore store = storeAdvertising("0.2.7");
        when(store.announce(any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(false);
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        assertThat(controller.announce(SECRET, req("0.2.8", null)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a non-https or over-long url is refused, since it becomes an href on every install")
    void badUrlRefused() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        // Literal, not MAX_URL_LENGTH: deriving it from the constant makes the test follow any
        // change to the cap instead of pinning it.
        StringBuilder tooLong = new StringBuilder("https://example.test/");
        while (tooLong.length() <= 501) {
            tooLong.append('x');
        }
        String[] bad = { "http://example.test/notes", "javascript:alert(1)", "ftp://example.test",
                "//example.test", tooLong.toString() };
        for (String url : bad) {
            var response = controller.announce(SECRET,
                    new AnnounceRequest("0.2.8", url, false, null, null));
            assertThat(response.getStatusCode()).as("url %s", url).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("an older version is refused with 409 and the advertised release is unchanged")
    void olderVersionRefused() {
        // The fleet must never be walked backwards by a re-run of an older release.
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        var response = controller.announce(SECRET, req("0.2.6", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("re-announcing the SAME version is refused, so a re-run cannot restamp the release")
    void sameVersionRefused() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        assertThat(controller.announce(SECRET, req("0.2.7", null)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("force accepts an older version, which is the retraction path with no cloud deploy")
    void forceAllowsRetraction() {
        CeReleaseStore store = storeAdvertising("0.2.8");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        var response = controller.announce(SECRET, req("0.2.7", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(store).announce("0.2.7", "https://example.test/notes", false, null, true);
    }

    @Test
    @DisplayName("an unparseable version is refused with 400 even with force, so a typo cannot wedge the feed")
    void unparseableVersionRefused() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        for (String bad : new String[] { "latest", "2026.07.30.1", "v", "", "  ", null }) {
            var response = controller.announce(SECRET, req(bad, true));
            assertThat(response.getStatusCode())
                    .as("version %s must be refused", bad)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("the first announcement is accepted when nothing is advertised yet")
    void firstAnnouncementAccepted() {
        // The row ships empty on purpose (CE must not seed one), so the very first push has no
        // predecessor to be newer than.
        CeReleaseStore store = storeAdvertising(null);
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        var response = controller.announce(SECRET, req("0.2.8", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(store).announce("0.2.8", "https://example.test/notes", false, null, false);
    }

    @Test
    @DisplayName("the security flag reaches the store, so a security release can go red without a deploy")
    void securityFlagIsStored() {
        CeReleaseStore store = storeAdvertising("0.2.7");
        CeReleaseAnnounceController controller = new CeReleaseAnnounceController(store, SECRET, null);

        controller.announce(SECRET, new AnnounceRequest(
                "0.2.8", "https://example.test/notes", true, "2026-07-30T10:00:00Z", null));

        verify(store).announce("0.2.8", "https://example.test/notes", true, "2026-07-30T10:00:00Z", false);
    }
}
