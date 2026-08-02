package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.VersionInfo;
import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * CE-only poller that learns the latest published release from the cloud release
 * feed and feeds {@link VersionUpdateService}, so the Settings &gt; Information
 * card can tell a self-hosted user they are behind.
 *
 * <p>Gated to the embedded (CE) edition and to {@code ce.version-check.enabled}
 * (default on, opt-out for privacy). It never runs in cloud (keycloak mode). The
 * request carries only the running version and no install identifier.
 *
 * <p>Best-effort: a failed fetch is logged at debug and leaves the previously
 * known status untouched; the next run retries.
 */
@Component
@ConditionalOnExpression("'${auth.mode:keycloak}' == 'embedded' && '${ce.version-check.enabled:true}' == 'true'")
public class CeVersionCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(CeVersionCheckScheduler.class);

    private final VersionUpdateService versionUpdateService;
    private final ReleaseFeedClient feedClient;
    private final GitProperties gitProperties;

    public CeVersionCheckScheduler(VersionUpdateService versionUpdateService,
                                   ReleaseFeedClient feedClient,
                                   ObjectProvider<GitProperties> gitProperties) {
        this.versionUpdateService = versionUpdateService;
        this.feedClient = feedClient;
        this.gitProperties = gitProperties.getIfAvailable();
    }

    /** Check once shortly after the app is ready, so the card is accurate without waiting for the daily cron. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        checkNow();
    }

    /** Daily refresh at 05:00 UTC (low-traffic window). */
    @Scheduled(cron = "${ce.version-check.cron:0 0 5 * * *}", zone = "UTC")
    public void checkDaily() {
        checkNow();
    }

    /** Fetch the feed and update the held status. Best-effort: never throws. */
    public void checkNow() {
        String current = VersionInfo.resolveVersion(gitProperties);
        try {
            LatestRelease body = feedClient.fetchLatest(current);
            // A 200 carrying no version counts as "no release", exactly like a null body.
            // Storing it would overwrite a good status with nulls and blank the update
            // banner, and an install cannot tell that apart from "you are up to date".
            // Any feed hiccup that answers 200-with-null must therefore be inert here.
            if (body == null || body.latestVersion() == null || body.latestVersion().isBlank()) {
                log.debug("CE version check: feed advertised no release (running={})", current);
                return;
            }
            versionUpdateService.update(new UpdateStatus(
                    body.latestVersion(),
                    body.releaseUrl(),
                    body.securityFix(),
                    body.publishedAt(),
                    Instant.now()));
            log.debug("CE version check: latest={} (running={})", body.latestVersion(), current);
        } catch (RuntimeException failure) {
            log.debug("CE version check failed - keeping previous status: {}", failure.getMessage());
        }
    }
}
