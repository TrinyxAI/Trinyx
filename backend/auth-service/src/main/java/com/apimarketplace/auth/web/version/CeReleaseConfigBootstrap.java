package com.apimarketplace.auth.web.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Copies a configured {@code ce.release.latest-version} into the announced row once, when the row
 * is still empty.
 *
 * <p>This exists to remove a manual, order-dependent cutover. Without it the migration to a
 * row-backed feed needs three steps in a fixed order: deploy with the config still set, announce
 * the current release by hand, then blank the config and redeploy. Getting that order wrong is
 * silent and fleet-wide: blanking the config before a row exists makes the feed answer "no
 * release", and every CE binary shipped before the null-guard fix stores that over a good status
 * and blanks its own update banner.
 *
 * <p>With this, the config can be removed whenever: the row already holds the same value, so the
 * feed keeps answering across the change. A "remember to do X later or the fleet goes dark" step
 * is a bug, not a runbook.
 *
 * <p>Inert on self-hosted installs, where {@code ce.release.*} is unset. It only ever fills a row
 * that holds no usable version, and that decision is made by {@link CeReleaseStore#announceIfAbsent}
 * from the row it writes - not from a separate earlier read, which is what previously let a release
 * announced in between be overwritten.
 */
@Component
public class CeReleaseConfigBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CeReleaseConfigBootstrap.class);

    private final ObjectProvider<CeReleaseStore> store;
    private final String configuredVersion;
    private final String configuredUrl;
    private final boolean configuredSecurityFix;
    private final String configuredPublishedAt;

    public CeReleaseConfigBootstrap(
            ObjectProvider<CeReleaseStore> store,
            @Value("${ce.release.latest-version:}") String configuredVersion,
            @Value("${ce.release.url:}") String configuredUrl,
            @Value("${ce.release.security:false}") boolean configuredSecurityFix,
            @Value("${ce.release.published-at:}") String configuredPublishedAt) {
        this.store = store;
        this.configuredVersion = configuredVersion;
        this.configuredUrl = configuredUrl;
        this.configuredSecurityFix = configuredSecurityFix;
        this.configuredPublishedAt = configuredPublishedAt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedFromConfigIfEmpty() {
        String version = trimToNull(configuredVersion);
        if (version == null) {
            return;
        }
        if (VersionComparator.parseCore(version) == null) {
            log.warn("ce.release.latest-version '{}' is not a numeric version - not seeding the announced release",
                    version);
            return;
        }
        CeReleaseStore resolved = store.getIfAvailable();
        if (resolved == null) {
            return;
        }
        try {
            // Emptiness is decided under the row lock inside this call, not from a cached read out
            // here: a release announced between the check and the write would otherwise be
            // overwritten by the pin.
            if (resolved.announceIfAbsent(version, trimToNull(configuredUrl), configuredSecurityFix,
                    trimToNull(configuredPublishedAt))) {
                log.info("seeded the announced CE release from configuration: {}", version);
            } else {
                log.debug("a CE release is already announced - leaving it alone");
            }
        } catch (RuntimeException failure) {
            // Best effort. A second replica racing this one blocks on the row lock and then finds
            // a version already there, so it returns false rather than overwriting. The config
            // still answers the feed on its own, so a failure here costs nothing today - it only
            // means the config cannot be blanked yet, which is why the cutover verifies the seed
            // before doing that.
            log.warn("could not seed the announced CE release from configuration: {}", failure.getMessage());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
