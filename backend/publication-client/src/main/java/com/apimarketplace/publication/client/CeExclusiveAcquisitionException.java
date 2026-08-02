package com.apimarketplace.publication.client;

import java.util.List;

/**
 * Client-side view of publication-service's {@code 403 code=CE_EXCLUSIVE}: the
 * publication only runs on a self-hosted install, so this managed-cloud
 * deployment refuses to install it.
 *
 * <p>Typed (rather than a generic {@code RuntimeException}) for the same reason
 * {@code LimitExceededException} is: the caller is usually an agent-facing tool
 * that must report the CONSTRAINT and the resolution, not "acquire failed".
 * {@link #getMessage()} is already the user/agent-facing sentence produced by
 * the service, so callers can surface it verbatim.
 */
public class CeExclusiveAcquisitionException extends RuntimeException {

    /** Detected feature codes ({@code CLI_AGENT}, {@code VECTOR_SEARCH}); may be empty. */
    private final List<String> features;

    public CeExclusiveAcquisitionException(String message, List<String> features) {
        super(message);
        this.features = features == null ? List.of() : List.copyOf(features);
    }

    public List<String> getFeatures() {
        return features;
    }
}
