package com.apimarketplace.auth.web.version;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time comparison of a presented {@code X-Internal-Auth} header against a configured
 * shared secret, with the fail-closed rule both endpoints in this package depend on.
 *
 * <p>Extracted because the two callers guard paths that sit on the gateway's PUBLIC allowlist
 * ({@link CeReleaseAnnounceController} writes the release every install polls,
 * {@link CeInstallStatsController} reads the fleet ledger), so the check being identical in both is
 * a property worth having in one place rather than in two that can drift.
 */
final class InternalAuthSecret {

    /**
     * @param configured the secret this deployment expects, possibly null or blank
     * @param presented  the {@code X-Internal-Auth} header value, possibly null
     * @return true only when a non-blank secret was configured AND the header matches it
     */
    static boolean matches(String configured, String presented) {
        // Blank configuration authorizes nothing. An unset secret degrading into "no auth required"
        // on a publicly reachable path is the failure this ordering exists to prevent.
        if (configured == null || configured.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private InternalAuthSecret() {
    }
}
