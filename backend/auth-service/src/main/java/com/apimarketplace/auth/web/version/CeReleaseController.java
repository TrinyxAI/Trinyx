package com.apimarketplace.auth.web.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cloud-side public feed of the latest published self-hosted release, polled by CE installs to
 * learn whether they are behind. Unauthenticated, same model as n8n's public {@code /api/versions}
 * feed.
 *
 * <p>A caller MAY present its anonymous install id ({@link CeInstallHeaders#INSTALL_ID}); when it
 * does, and only where collection is switched on, the sighting is recorded so the live self-hosted
 * fleet can be counted. The header is optional and the answer does not depend on it: an install
 * that opted out is served identically. Nothing identifying is read from the request - notably not
 * the IP, which unlike an opaque per-instance UUID would be personal data.
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
    private final ObjectProvider<CeInstallPingRecorder> pingRecorder;

    /**
     * @param store        the announced-release row, injected as an {@link ObjectProvider} so this
     *                     controller still constructs in a context without persistence (CE, slice tests).
     * @param pingRecorder present only where fleet collection is switched on (the cloud
     *                     deployment); absent everywhere else, and the feed then ignores the header
     *                     entirely.
     */
    public CeReleaseController(
            @Value("${ce.release.latest-version:}") String latestVersion,
            @Value("${ce.release.url:}") String releaseUrl,
            @Value("${ce.release.security:false}") boolean securityFix,
            @Value("${ce.release.published-at:}") String publishedAt,
            ObjectProvider<CeReleaseStore> store,
            ObjectProvider<CeInstallPingRecorder> pingRecorder) {
        this.latestVersion = blankToNull(latestVersion);
        this.releaseUrl = blankToNull(releaseUrl);
        this.securityFix = securityFix;
        this.publishedAt = blankToNull(publishedAt);
        this.store = store;
        this.pingRecorder = pingRecorder;
    }

    /**
     * @param current   the caller's running version (optional). The comparison is still done
     *                  client-side by the CE install; this only tells the fleet ledger which
     *                  release an install is actually running.
     * @param installId the caller's anonymous install id (optional). Unparseable values are
     *                  ignored rather than rejected: this header must never be able to fail an
     *                  update check.
     */
    @GetMapping("/api/ce/releases/latest")
    public LatestRelease latest(
            @RequestParam(value = "current", required = false) String current,
            @RequestHeader(value = CeInstallHeaders.INSTALL_ID, required = false) String installId) {

        LatestRelease answer = resolveLatest();
        // After the answer, not before. The write is inline on the request thread of the endpoint
        // the whole fleet polls for security releases, so anything it might cost - a contended row,
        // a saturated pool - is paid once the payload is already in hand rather than in front of
        // it. A lookup that could not be answered at all therefore records no sighting, which is
        // also the honest outcome: we never served that install anything.
        recordSighting(installId, current);
        return answer;
    }

    /** Resolves what to advertise: the pin if usable, else the announced row, else no release. */
    private LatestRelease resolveLatest() {
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

    /**
     * Notes that this install exists, when collection is on. Best-effort: the recorder cannot
     * throw, and the release feed returns the same payload whether or not the sighting was stored.
     * Called AFTER the answer is resolved, for the reason given at the call site.
     */
    private void recordSighting(String installId, String version) {
        if (pingRecorder == null || installId == null || installId.isBlank()) {
            return;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(installId.trim());
        } catch (IllegalArgumentException notAUuid) {
            log.debug("Ignoring malformed install id on the release feed");
            return;
        }
        try {
            // Resolving the bean is inside the guard too: a lazy bean whose creation fails would
            // otherwise throw here and 500 the endpoint the whole fleet polls for security
            // releases.
            CeInstallPingRecorder recorder = pingRecorder.getIfAvailable();
            if (recorder != null) {
                recorder.record(parsed, version);
            }
        } catch (RuntimeException failure) {
            // The recorder promises never to throw, but that promise lives across a class boundary
            // and this is the whole fleet's update lifeline. Cheap insurance against a future edit
            // to the recorder turning every release check into a 500.
            log.warn("CE install sighting not recorded: {}", failure.getMessage());
        }
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
