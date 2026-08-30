package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeInstallPingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The fleet read is the only consumer of everything this feature collects, so its auth is the whole
 * of its security: it sits on a gateway-allowlisted path and is protected at the controller alone.
 */
class CeInstallStatsControllerTest {

    private static final String SECRET = "s3cret-fleet-read";
    /** Fixed, so a window assertion is exact rather than a race against the test's own clock. */
    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static CeInstallStatsController controller(CeInstallPingRepository repository) {
        return new CeInstallStatsController(new CeFleetReader(repository), SECRET, CLOCK);
    }

    private static CeInstallPingRepository ledgerWith(long total, long active, long created) {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        // A DISTINCT value per window, never one flat number for both. Stubbing them equal meant
        // active7d and active30d were asserted equal, so swapping the two in the payload left every
        // test green while /stats reported the 30-day figure as the live fleet number.
        when(repository.fleetCounts(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{total, active, active * 2, created, created * 2}));
        when(repository.versionBreakdown(any(), anyInt())).thenReturn(List.of(
                new Object[]{"0.2.13", 180L},
                new Object[]{"0.2.12", 44L}));
        return repository;
    }

    @Test
    @DisplayName("no secret presented is rejected and reads nothing")
    void missingSecretIsRejected() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        CeInstallStatsController controller = controller(repository);

        ResponseEntity<Map<String, Object>> response = controller.stats(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Rejecting after querying would still put the ledger behind an unauthenticated request.
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a wrong secret is rejected")
    void wrongSecretIsRejected() {
        CeInstallStatsController controller =
                controller(mock(CeInstallPingRepository.class));

        assertThat(controller.stats("not-the-secret").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a blank configured secret fails closed rather than letting everyone in")
    void blankSecretFailsClosed() {
        CeInstallStatsController controller =
                new CeInstallStatsController(
                        new CeFleetReader(mock(CeInstallPingRepository.class)), "", CLOCK);

        // An unset secret must not degrade into "no auth required" on a gateway-allowlisted path.
        assertThat(controller.stats("").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.stats("anything").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("returns the fleet aggregates and the version breakdown")
    void returnsAggregates() {
        CeInstallStatsController controller =
                controller(ledgerWith(412L, 380L, 120L));

        ResponseEntity<Map<String, Object>> response = controller.stats(SECRET);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("total")).isEqualTo(412L);
        // Distinct values per window: this is what makes a swapped pair of expressions fail here.
        assertThat(body.get("active7d")).isEqualTo(380L);
        assertThat(body.get("active30d")).isEqualTo(760L);
        assertThat(body.get("new7d")).isEqualTo(120L);
        assertThat(body.get("new30d")).isEqualTo(240L);
        assertThat(body.get("versions")).isEqualTo(List.of(
                Map.of("version", "0.2.13", "installs", 180L),
                Map.of("version", "0.2.12", "installs", 44L)));
    }

    @Test
    @DisplayName("the windows really are 7 and 30 days back from now")
    void windowsAreSevenAndThirtyDays() {
        CeInstallPingRepository repository = ledgerWith(1L, 1L, 1L);

        controller(repository).stats(SECRET);

        ArgumentCaptor<Instant> shortAgo = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> longAgo = ArgumentCaptor.forClass(Instant.class);
        verify(repository).fleetCounts(shortAgo.capture(), longAgo.capture());
        // Labels on a dashboard are not evidence: without this the card could say "7 days" while
        // querying any window at all, and nobody would see the difference. Exact equality is only
        // possible because the controller takes a Clock: comparing the captured value against the
        // test's own Instant.now() is a race, and toDays() truncated it to 6 whenever the two
        // clock reads straddled a millisecond.
        assertThat(Duration.between(shortAgo.getValue(), NOW)).isEqualTo(Duration.ofDays(7));
        assertThat(Duration.between(longAgo.getValue(), NOW)).isEqualTo(Duration.ofDays(30));
    }

    @Test
    @DisplayName("the payload names the window its version breakdown covers")
    void payloadNamesTheVersionWindow() {
        Map<String, Object> body = controller(ledgerWith(1L, 1L, 1L)).stats(SECRET).getBody();

        // A breakdown with no stated window leaves the reader guessing what the counts are of.
        assertThat(body).containsEntry("versionsWindowDays", 30L);
        assertThat(body).containsEntry("generatedAt", NOW.toString());
    }

    @Test
    @DisplayName("the breakdown is asked for at most VERSION_LIMIT rows")
    void breakdownIsLimited() {
        CeInstallPingRepository repository = ledgerWith(1L, 1L, 1L);

        controller(repository).stats(SECRET);

        // An unbounded GROUP BY over a table an anonymous caller can write to is a payload of
        // whatever size that caller chooses.
        verify(repository).versionBreakdown(any(), eq(CeInstallStatsController.VERSION_LIMIT));
        // Pinned to a literal too, or the assertion above reads the constant back to itself and any
        // value at all satisfies it. 15 here against the gauges' 10 is deliberate and documented:
        // this one bounds a JSON payload, that one bounds Prometheus label cardinality.
        assertThat(CeInstallStatsController.VERSION_LIMIT).isEqualTo(15);
    }

    @Test
    @DisplayName("both answers forbid caching and vary on the auth header")
    void responsesAreUncacheable() {
        CeInstallStatsController controller = controller(ledgerWith(1L, 1L, 1L));

        for (ResponseEntity<Map<String, Object>> response :
                java.util.List.of(controller.stats(SECRET), controller.stats(null))) {
            // This is a GET on a path anyone can reach from the internet. An edge rule that keys on
            // the URL and ignores request headers would otherwise be able to serve a previously
            // authorized body to an anonymous caller, which is the whole reason these are set.
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
            assertThat(response.getHeaders().getVary()).contains("X-Internal-Auth");
        }
    }

    @Test
    @DisplayName("a wrong secret of the SAME length is rejected")
    void sameLengthWrongSecretIsRejected() {
        CeInstallStatsController controller = controller(mock(CeInstallPingRepository.class));

        // Every other rejection test presents a secret of a different length, and
        // MessageDigest.isEqual short-circuits on length: replacing the constant-time comparison
        // with equals() would pass all of them. This is the one control between the public
        // internet and the fleet ledger, so the comparison itself needs an assertion.
        String sameLength = "X".repeat(SECRET.length());
        assertThat(sameLength).hasSameSizeAs(SECRET).isNotEqualTo(SECRET);
        assertThat(controller.stats(sameLength).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an aggregate that times out answers 503 rather than a stack trace")
    void unreadableLedgerAnswers503() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.versionBreakdown(any(), anyInt()))
                .thenThrow(new IllegalStateException("statement timeout"));

        ResponseEntity<Map<String, Object>> response = controller(repository).stats(SECRET);

        // Every aggregate carries a 2s timeout and scans a table that grows with the fleet, so
        // they fail exactly when it is big enough to be worth looking at. A 500 there leaves the
        // operator with no window onto the data at the only moment they need one.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "stats_unavailable");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }
}
