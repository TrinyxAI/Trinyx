package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The release feed answers the whole self-hosted fleet, and now also counts it. These tests pin the
 * rule that keeps the second job from ever damaging the first: the payload is identical whether the
 * caller identifies itself, sends nonsense, or the ledger is broken.
 */
class CeReleaseFeedSightingTest {

    private static final String INSTALL = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CeReleaseStore> noStore() {
        ObjectProvider<CeReleaseStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CeInstallPingRecorder> recording(CeInstallPingRecorder recorder) {
        ObjectProvider<CeInstallPingRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        return provider;
    }

    private static CeReleaseController feedWith(ObjectProvider<CeInstallPingRecorder> recorder) {
        return new CeReleaseController("0.3.0", "https://example.test/r", false, null, noStore(), recorder);
    }

    @Test
    @DisplayName("an identified caller is counted, with the version it says it runs")
    void identifiedCallerIsCounted() {
        CeInstallPingRecorder recorder = mock(CeInstallPingRecorder.class);

        LatestRelease answer = feedWith(recording(recorder)).latest("0.2.13", INSTALL);

        verify(recorder).record(UUID.fromString(INSTALL), "0.2.13");
        assertThat(answer.latestVersion()).isEqualTo("0.3.0");
    }

    @Test
    @DisplayName("an anonymous caller is served a byte-identical answer and counted as nothing")
    void anonymousCallerIsServedIdentically() {
        CeInstallPingRecorder recorder = mock(CeInstallPingRecorder.class);
        CeReleaseController feed = feedWith(recording(recorder));

        LatestRelease identified = feed.latest("0.2.13", INSTALL);
        LatestRelease anonymous = feed.latest("0.2.13", null);

        // Opting out must cost the operator nothing. Comparing the whole record, not just the
        // version: a sighting path that touched releaseUrl or securityFix would pass a
        // version-only assertion while serving opted-out installs a different answer.
        assertThat(anonymous).isEqualTo(identified);
        assertThat(anonymous.releaseUrl()).isEqualTo("https://example.test/r");
        verify(recorder).record(UUID.fromString(INSTALL), "0.2.13");
        verifyNoMoreInteractions(recorder);
    }

    @Test
    @DisplayName("a malformed install id is ignored, not rejected")
    void malformedInstallIdIsIgnored() {
        CeInstallPingRecorder recorder = mock(CeInstallPingRecorder.class);

        LatestRelease answer = feedWith(recording(recorder)).latest("0.2.13", "not-a-uuid");

        // Answering 400 here would let a corrupted header stop an install from ever learning about
        // a security release.
        verifyNoInteractions(recorder);
        assertThat(answer.latestVersion()).isEqualTo("0.3.0");
    }

    @Test
    @DisplayName("a blank install id header is ignored")
    void blankInstallIdIsIgnored() {
        CeInstallPingRecorder recorder = mock(CeInstallPingRecorder.class);

        assertThat(feedWith(recording(recorder)).latest("0.2.13", "   ").latestVersion()).isEqualTo("0.3.0");

        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("with collection switched off the header is simply unused")
    void collectionOffIgnoresTheHeader() {
        // This is every deployment except the cloud one, including a self-hosted enterprise install
        // running keycloak mode, which must never collect its own fleet.
        assertThat(feedWith(recording(null)).latest("0.2.13", INSTALL).latestVersion()).isEqualTo("0.3.0");
    }

    @Test
    @DisplayName("a broken ledger cannot break the release feed")
    void brokenLedgerDoesNotBreakTheFeed() {
        CeInstallPingRecorder recorder = mock(CeInstallPingRecorder.class);
        doThrow(new IllegalStateException("ledger exploded")).when(recorder).record(any(), any());
        CeReleaseController feed = feedWith(recording(recorder));

        assertThatCode(() -> assertThat(feed.latest("0.2.13", INSTALL).latestVersion()).isEqualTo("0.3.0"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a lookup that cannot be answered records no sighting")
    void unanswerableLookupRecordsNothing() {
        CeInstallPingRecorder recorder = mock(CeInstallPingRecorder.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CeReleaseStore> failing = mock(ObjectProvider.class);
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenThrow(new CeReleaseStore.LookupUnavailableException(new IllegalStateException("db down")));
        when(failing.getIfAvailable()).thenReturn(store);
        CeReleaseController feed =
                new CeReleaseController("", "", false, null, failing, recording(recorder));

        assertThatThrownBy(() -> feed.latest("0.2.13", INSTALL))
                .isInstanceOf(CeReleaseStore.LookupUnavailableException.class);

        // The sighting is recorded AFTER the answer is resolved, so a request we could not serve
        // records nothing: we never told that install anything. Move the call back above
        // resolveLatest() and every other test in this feature stays green.
        verifyNoInteractions(recorder);
    }
}
