package com.apimarketplace.auth.web.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cloud-side public feed of the latest published self-hosted release, polled by CE installs to
 * learn whether they are behind. Anonymous (no auth, no install identifier). Same model as n8n's
 * public {@code /api/versions} feed.
 *
 * <p>Resolution order: the {@code ce.release.*} properties WIN when set, then the announced row
 * ({@link CeReleaseStore}), then "no release". The properties are the manual override for pinning,
 * rolling back or hiding a bad release; in normal operation they are blank and the row, written by
 * the release run, answers. Announcing therefore needs no cloud deploy, and a cloud deploy carries
 * no CE version, which is what stopped the advertised version from silently drifting.
 *
 * <p>Routed under the existing {@code /api/ce/**} gateway route and allowlisted in the gateway
 * public-endpoints set so an unlinked CE can reach it. The monolith exposes this controller too,
 * where the table is empty and it advertises "no release" - harmless, since CE polls the cloud's
 * URL rather than its own.
 *
 * <p>A lookup that cannot be answered at all propagates ({@link CeReleaseStore.LookupUnavailableException},
 * i.e. a 500) rather than degrading into a null version: on the wire "no release" and "I cannot
 * tell you" are the same answer, and every CE binary shipped before the poller's null-guard stores
 * that null over a good status and blanks its own update banner.
 */
@RestController
public class CeReleaseController {

    private static final Logger log = LoggerFactory.getLogger(CeReleaseController.class);

    private final String latestVersion;
    private final String releaseUrl;
    private final boolean securityFix;
    private final String publishedAt;
    private final ObjectProvider<CeReleaseStore> store;

    /**
     * @param store the announced-release row, injected as an {@link ObjectProvider} so this
     *              controller still constructs in a context without persistence (CE, slice tests).
     */
    public CeReleaseController(
            @Value("${ce.release.latest-version:}") String latestVersion,
            @Value("${ce.release.url:}") String releaseUrl,
            @Value("${ce.release.security:false}") boolean securityFix,
            @Value("${ce.release.published-at:}") String publishedAt,
            ObjectProvider<CeReleaseStore> store) {
        this.latestVersion = blankToNull(latestVersion);
        this.releaseUrl = blankToNull(releaseUrl);
        this.securityFix = securityFix;
        this.publishedAt = blankToNull(publishedAt);
        this.store = store;
    }

    /**
     * @param current the caller's running version (optional, accepted for future
     *                analytics; the comparison is done client-side by the CE install).
     */
    @GetMapping("/api/ce/releases/latest")
    public LatestRelease latest(@RequestParam(value = "current", required = false) String current) {
        // Config WINS when set: it is the manual override for pinning, rolling back, or hiding a
        // bad release without waiting on a release run. In normal operation it is blank and the
        // announced row answers. Leaving a value in config freezes the feed at it.
        // An override that cannot be read is NOT an override. Returning "no release" here both
        // emitted the banner-blanking payload AND hid a perfectly good announced row for as long
        // as the bad pin stayed deployed - two failures from one typo. Falling through matches
        // what the config bootstrap already does when it refuses to seed an unparseable version.
        String pinned = normalize(latestVersion);
        if (pinned != null) {
            return new LatestRelease(pinned, releaseUrl, securityFix, publishedAt);
        }
        if (latestVersion != null) {
            log.warn("ce.release.latest-version '{}' is not a usable version - ignoring the pin and "
                    + "serving the announced release instead", latestVersion);
        }
        CeReleaseStore resolved = store == null ? null : store.getIfAvailable();
        CeReleaseStore.Announced announced = resolved == null ? null : resolved.current();
        String version = announced == null ? null : normalize(announced.latestVersion());
        if (version == null) {
            return new LatestRelease(null, null, false, null);
        }
        return new LatestRelease(
                version,
                blankToNull(announced.releaseUrl()),
                announced.securityFix(),
                blankToNull(announced.publishedAt()));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * The single normaliser both branches use: strips the tag's leading {@code v}, then rejects
     * anything that is not a usable version.
     *
     * <p>Returns {@code null} rather than a degraded string. An empty or unparseable version on the
     * wire is worse than "no release": a CE install compares it numerically, so it either blanks
     * the banner or shows one no upgrade can clear. Advertising nothing is the honest answer.
     */
    private static String normalize(String version) {
        // Delegates so the feed, the announce pin guard and the bootstrap cannot disagree about
        // what counts as a version. The local strip-then-parse this replaces stripped one leading
        // v more than parseCore does, which is how "vv0.2.7" became a pin here and not there.
        return VersionComparator.canonicalOrNull(version);
    }

    /** Wire shape of the release feed; mirrored by the CE poller's deserializer. */
    public record LatestRelease(
            String latestVersion,
            String releaseUrl,
            boolean securityFix,
            String publishedAt) {
    }
}
