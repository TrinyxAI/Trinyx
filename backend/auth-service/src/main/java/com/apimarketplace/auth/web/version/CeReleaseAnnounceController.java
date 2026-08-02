package com.apimarketplace.auth.web.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Internal write path for the CE release announcement, called by the CE release workflow once its
 * smoke-test has passed.
 *
 * <p>This is what decouples the two release cycles: publishing a CE release no longer needs a cloud
 * deploy, and a cloud deploy no longer carries a CE version. Announcing after the smoke-test is also
 * what stops an unverified build from being advertised to the whole fleet.
 *
 * <p>Gated on {@code ce.release.announce.enabled}, set only on the cloud deployment. It is
 * deliberately NOT gated on {@code auth.mode}: keycloak is also declared for self-hosted enterprise,
 * so that condition would arm this endpoint on a customer's own box.
 */
@RestController
@ConditionalOnProperty(name = "ce.release.announce.enabled", havingValue = "true", matchIfMissing = false)
public class CeReleaseAnnounceController {

    private static final Logger log = LoggerFactory.getLogger(CeReleaseAnnounceController.class);
    static final int MAX_URL_LENGTH = 500;

    private final CeReleaseStore store;
    private final String sharedSecret;
    private final String configuredOverride;

    public CeReleaseAnnounceController(
            CeReleaseStore store,
            @Value("${ce.release.announce.secret:}") String sharedSecret,
            @Value("${ce.release.latest-version:}") String configuredOverride) {
        this.store = store;
        this.sharedSecret = sharedSecret;
        // Same rule as the feed: an override that cannot be parsed is not an override. Using a
        // bare trimToNull here meant a stray "v" blocked every announcement with a message
        // saying the feed was pinned, while the feed happily ignored it and served the row.
        this.configuredOverride = VersionComparator.canonicalOrNull(configuredOverride);
        if (sharedSecret == null || sharedSecret.isBlank()) {
            // Enabled but secretless fails closed, which is right, but silently: the operator
            // would only find out when a release run comes back 401. Say it at startup instead.
            log.warn("CE release announce is ENABLED but ce.release.announce.secret is blank - "
                    + "every announcement will be rejected with 401 until the secret is set");
        }
    }

    /**
     * Announces {@code version} to every self-hosted install.
     *
     * <p>Refuses a version that is not strictly newer than the one currently advertised, so a
     * re-run of an older release cannot walk the fleet backwards. {@code force} overrides that,
     * which is the retraction path: re-announce the previous release to withdraw a bad one, with no
     * cloud deploy.
     */
    @PostMapping("/api/ce/releases/announce")
    public ResponseEntity<Map<String, Object>> announce(
            @RequestHeader(value = "X-Internal-Auth", required = false) String presented,
            @RequestBody AnnounceRequest request) {

        if (!authorized(presented)) {
            log.warn("CE release announce rejected: bad or missing X-Internal-Auth");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        String version = request == null ? null : trimToNull(request.version());
        if (version == null || VersionComparator.parseCore(version) == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "version must be a numeric major.minor.patch", "received", String.valueOf(version)));
        }

        String url = trimToNull(request.url());
        if (url != null && (!url.startsWith("https://") || url.length() > MAX_URL_LENGTH)) {
            // This string becomes the href of the "How to update" link on every self-hosted
            // install, so it must not be free-form: a javascript: URI or a typo would point the
            // whole fleet somewhere of the caller's choosing.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "url must be an https:// link of at most " + MAX_URL_LENGTH + " characters",
                    "received", url));
        }

        // Checked BEFORE any database read: this answer is deterministic and needs no DB, so a
        // pinned cloud during an outage still returns the actionable 409 instead of a 500.
        // Reported so the caller learns in ONE request that the row it is about to write will not
        // reach the feed. Without it the release job discovers the same thing 200 seconds later,
        // from a polling loop, after the row is already committed - and its re-run is then refused
        // as going backwards.
        if (configuredOverride != null) {
            log.warn("CE release announce refused: ce.release.latest-version is pinned to {}", configuredOverride);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "a ce.release.latest-version override is active, so the announcement would not reach the feed",
                    "override", configuredOverride,
                    "received", version,
                    "stored", describeStoredRelease()));
        }

        CeReleaseStore.Announced currentAnnounced = store.current();
        String currentVersion = currentAnnounced == null ? null : currentAnnounced.latestVersion();
        // Same rule as the store: an unparseable stored version is not something to be newer
        // than, and reporting it as `advertised` would name a value the feed does not serve.
        currentVersion = VersionComparator.canonicalOrNull(currentVersion);
        boolean force = request.force() != null && request.force();
        if (!force && currentVersion != null && !VersionComparator.isUpdateAvailable(currentVersion, version)) {
            log.warn("CE release announce rejected: {} is not newer than the advertised {}", version, currentVersion);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "not newer than the advertised release",
                    "advertised", currentVersion,
                    "received", version));
        }

        if (!store.announce(version, url, Boolean.TRUE.equals(request.securityFix()),
                trimToNull(request.publishedAt()), force)) {
            // Lost the race against another replica: the row already holds this release or newer.
            log.warn("CE release announce rejected at write time: {} is not newer than the stored release", version);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "not newer than the stored release", "received", version));
        }
        log.info("CE release announced: {} (security={}, forced={}, previous={})",
                version, Boolean.TRUE.equals(request.securityFix()), force, currentVersion);
        return ResponseEntity.ok(Map.of("announced", version, "previous", String.valueOf(currentVersion)));
    }


    /**
     * What the announced ROW holds, for the 409 body.
     *
     * <p>The only way to observe the row while an override is active: the public feed answers the
     * override, so it cannot tell an operator whether the row was seeded. That matters because
     * blanking the override over an unseeded row makes the feed answer "no release", which every
     * CE binary shipped before the poller's null-guard stores over its good status.
     *
     * <p>Never throws and never turns this into a 500. The refusal itself is decided without the
     * database on purpose, so a pinned cloud mid-outage still returns the actionable 409; this
     * diagnostic must not undo that.
     */
    private String describeStoredRelease() {
        try {
            CeReleaseStore.Announced announced = store.current();
            String stored = announced == null ? null : announced.latestVersion();
            return stored == null || stored.isBlank() ? "none" : stored;
        } catch (RuntimeException unavailable) {
            return "unavailable";
        }
    }

    /**
     * Constant-time comparison, and a blank configured secret authorizes nothing: enabling the
     * endpoint without setting a secret must fail closed rather than accept every caller.
     */
    private boolean authorized(String presented) {
        if (sharedSecret == null || sharedSecret.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sharedSecret.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Body of an announce call; {@code force} bypasses the newer-than check for a retraction. */
    public record AnnounceRequest(
            String version,
            String url,
            Boolean securityFix,
            String publishedAt,
            Boolean force) {
    }
}
