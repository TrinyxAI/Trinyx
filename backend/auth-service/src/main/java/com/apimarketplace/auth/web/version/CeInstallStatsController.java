package com.apimarketplace.auth.web.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aggregate read of the self-hosted fleet: how many installs are live, how many are new, and which
 * version they run.
 *
 * <p>Without this the ledger is a table nobody looks at, and the number that was collected to
 * replace unreliable adoption signals goes unread. Aggregates ONLY: there is no per-install lookup
 * here and none in the repository, which is what keeps an anonymous counter from drifting into a
 * per-install tracker.
 *
 * <p>Protected by the {@code X-Internal-Auth} shared secret at the controller, like
 * {@link CeReleaseAnnounceController} and the gateway's cache-invalidate endpoint, and gated on the
 * same {@code ce.installs.telemetry.enabled} flag as collection: an install that stores nothing has
 * nothing to serve.
 *
 * <p>Unlike those two, this one is a GET on a path anyone can request from the public internet,
 * which makes it cacheable by default. Every response therefore carries {@code no-store} and
 * {@code Vary: X-Internal-Auth}: an edge or CDN rule that keys on the URL and ignores request
 * headers would otherwise be able to replay an authorized body to an anonymous caller.
 */
@RestController
@ConditionalOnProperty(name = "ce.installs.telemetry.enabled", havingValue = "true", matchIfMissing = false)
public class CeInstallStatsController {

    private static final Logger log = LoggerFactory.getLogger(CeInstallStatsController.class);
    /** Versions listed in the breakdown; beyond this the tail is noise for a fleet read. */
    static final int VERSION_LIMIT = 15;
    /** Below this the shared secret is worth guessing; warned about at startup. */
    static final int MIN_SECRET_LENGTH = 16;

    private final CeFleetReader reader;
    private final String sharedSecret;
    private final Clock clock;

    @Autowired
    public CeInstallStatsController(
            CeFleetReader reader,
            @Value("${ce.installs.telemetry.secret:}") String sharedSecret) {
        this(reader, sharedSecret, Clock.systemUTC());
    }

    /**
     * Clock-injecting constructor for tests. Without it a test can only compare the captured window
     * start against its own {@code Instant.now()}, which is a different instant, so an assertion on
     * "7 days" fails whenever the two clock reads straddle a millisecond boundary.
     *
     * <p>The public constructor above carries {@code @Autowired} deliberately: a second constructor
     * with none leaves the container unable to choose.
     */
    CeInstallStatsController(
            CeFleetReader reader,
            String sharedSecret,
            Clock clock) {
        this.reader = reader;
        this.sharedSecret = sharedSecret;
        this.clock = clock;
        if (sharedSecret == null || sharedSecret.isBlank()) {
            // Fails closed, which is right, but silently: say it at startup rather than letting the
            // first read come back 401 with no explanation.
            log.warn("CE install stats are exposed but ce.installs.telemetry.secret is blank - "
                    + "every read will be rejected with 401 until the secret is set");
        } else if (sharedSecret.length() < MIN_SECRET_LENGTH) {
            // Rejections are logged at DEBUG on purpose (see below), so a guessing attack against
            // this path is quiet. A short secret is the one thing that makes it worth attempting,
            // and the only part of that an operator can still fix.
            log.warn("ce.installs.telemetry.secret is only {} characters - it guards a publicly "
                            + "reachable path whose rejections are not logged, so use at least {}",
                    sharedSecret.length(), MIN_SECRET_LENGTH);
        }
    }

    @GetMapping("/api/ce/installs/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @RequestHeader(value = "X-Internal-Auth", required = false) String presented) {

        if (!authorized(presented)) {
            // DEBUG, not WARN: this path is on the gateway public allowlist, so anyone can reach it
            // and a WARN per rejected request is an unauthenticated log-volume amplifier. A secret
            // that is missing from CONFIGURATION is what an operator needs told, and that is
            // already a WARN at startup.
            log.debug("CE install stats read rejected: bad or missing X-Internal-Auth");
            return uncacheable(ResponseEntity.status(HttpStatus.UNAUTHORIZED))
                    .body(Map.of("error", "unauthorized"));
        }

        try {
            CeFleetReader.Snapshot fleet = reader.read(clock.instant(), VERSION_LIMIT);
            return uncacheable(ResponseEntity.ok()).body(Map.of(
                    "total", fleet.total(),
                    // "Active" is measured over 7 days rather than 24 hours on purpose: the poller
                    // runs daily and on startup, so an install that is simply switched off
                    // overnight, or whose single daily attempt failed, is still a live install.
                    "active7d", fleet.active7d(),
                    "active30d", fleet.active30d(),
                    "new7d", fleet.new7d(),
                    "new30d", fleet.new30d(),
                    "versions", versionBreakdown(fleet),
                    // The breakdown covers a window, and a payload that does not say which one
                    // leaves the reader guessing what the counts are of.
                    "versionsWindowDays", CeFleetReader.LONG_WINDOW.toDays(),
                    "generatedAt", fleet.takenAt().toString()));
        } catch (RuntimeException failure) {
            // Every aggregate carries a 2s statement timeout, and count(*) is a heap scan that
            // grows with the ledger. A 500 with a stack trace would arrive exactly when the table
            // is large enough to be worth looking at, which is the worst moment to be unreadable.
            // Say what happened instead.
            log.warn("CE install stats could not be read: {}", failure.getMessage());
            return uncacheable(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE))
                    .body(Map.of("error", "stats_unavailable",
                            "detail", "the ledger could not be aggregated in time; retry, and if it "
                                    + "persists check the size of auth.ce_install_ping"));
        }
    }

    /** Maps the snapshot's version counts into a stable JSON shape. */
    private static List<Map<String, Object>> versionBreakdown(CeFleetReader.Snapshot fleet) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CeFleetReader.VersionCount version : fleet.versions()) {
            rows.add(Map.of("version", version.version(), "installs", version.installs()));
        }
        return rows;
    }

    /** Marks a response private and unstorable, and keys any cache on the auth header. */
    private static ResponseEntity.BodyBuilder uncacheable(ResponseEntity.BodyBuilder builder) {
        return builder
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.VARY, "X-Internal-Auth");
    }

    private boolean authorized(String presented) {
        return InternalAuthSecret.matches(sharedSecret, presented);
    }
}
