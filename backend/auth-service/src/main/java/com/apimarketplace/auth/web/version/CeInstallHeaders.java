package com.apimarketplace.auth.web.version;

/**
 * The wire name of the anonymous install identifier, shared by the CE sender
 * ({@link HttpReleaseFeedClient}) and the cloud reader ({@link CeReleaseController}).
 *
 * <p>Declared once so the two ends cannot drift: a rename on one side alone would not fail any
 * build, it would silently stop counting the fleet, which is the exact failure this telemetry
 * exists to remove.
 *
 * <p>Deliberately NOT {@code X-LiveContext-Install-Id}. That name already carries the CLOUD-LINK
 * install id on the LLM relay, the catalog and skill bundle downloads, and the cloud catalog relay,
 * and that identifier is a UUID bound to a tenant. Sharing the spelling would mean any proxy, mesh
 * or operator that injects the cloud-link header on outbound calls to the cloud silently writes a
 * tenant-linkable id into a table whose entire premise is that it holds none, with nothing anywhere
 * detecting it. A separate name costs nothing and removes the whole class of collision.
 */
public final class CeInstallHeaders {

    /** Anonymous per-install UUID. Carries no IP, hostname, tenant or account. */
    public static final String INSTALL_ID = "X-LiveContext-Anon-Install-Id";

    private CeInstallHeaders() {
    }
}
