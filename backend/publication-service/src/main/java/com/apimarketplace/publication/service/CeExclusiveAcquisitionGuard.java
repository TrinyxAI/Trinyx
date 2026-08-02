package com.apimarketplace.publication.service;

import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Edition gate for installing a CE-exclusive publication.
 *
 * <p>A publication flagged {@code ce_exclusive} uses a feature that exists only
 * on a self-hosted install (a local-CLI agent, a vector/embedding column). On
 * managed cloud there is no CLI host and no vector column, so the clone would
 * either fail at run time or silently lose the very thing the app is built
 * around. Refusing the install up front is the honest outcome, and the badge
 * plus this error tell the user exactly why.
 *
 * <p><b>Scope - which paths this must and must not cover.</b> Every path that
 * installs INTO a workspace runs through here (workflow, agent, and standalone
 * resource acquisition). The CE download path
 * ({@code /api/ce-marketplace/**}) deliberately does NOT: that endpoint runs on
 * cloud but SERVES self-hosted installs, and it is what makes these publications
 * "CE exclusive" rather than simply unavailable.
 *
 * <p>On a self-hosted deployment {@link AppEditionProvider#isManagedCloud()} is
 * false, so this guard is a no-op and a CE user installs everything - including
 * from their own local marketplace.
 */
@Component
public class CeExclusiveAcquisitionGuard {

    private static final Logger logger = LoggerFactory.getLogger(CeExclusiveAcquisitionGuard.class);

    /**
     * Single user-facing message for the refusal. States the constraint and the
     * only move that resolves it; it carries no REST paths or internals because
     * it surfaces verbatim in the marketplace UI and in agent tool results.
     */
    public static final String BLOCKED_MESSAGE =
            "This app is Community Edition exclusive: it uses features that only run on a "
                    + "self-hosted install (local CLI agents and/or vector search). Install it on a "
                    + "self-hosted deployment to use it.";

    private final boolean managedCloud;

    public CeExclusiveAcquisitionGuard(AppEditionProvider editionProvider) {
        this.managedCloud = editionProvider.isManagedCloud();
        logger.info("[CeExclusiveAcquisitionGuard] CE-exclusive publications {}",
                managedCloud ? "BLOCKED (managed cloud)" : "installable (self-hosted)");
    }

    /**
     * Refuse the install when this deployment cannot run the publication.
     *
     * @throws CeExclusivePublicationException on managed cloud for a CE-exclusive publication
     */
    public void check(WorkflowPublicationEntity publication) {
        if (publication == null || !managedCloud || !publication.isCeExclusive()) {
            return;
        }
        logger.info("[CeExclusiveAcquisitionGuard] refusing install of CE-exclusive publication {} (features={})",
                publication.getId(), publication.getCeExclusiveFeatures());
        throw new CeExclusivePublicationException(BLOCKED_MESSAGE, publication.getCeExclusiveFeatures());
    }
}
