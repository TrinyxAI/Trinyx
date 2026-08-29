package com.apimarketplace.publication.service;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a presentation-only snapshot of an Interface to embed as the "landing page"
 * of a non-interface publication (TABLE, SKILL, AGENT).
 *
 * <p>Unlike {@link com.apimarketplace.publication.service.resource.InterfaceResourceStrategy},
 * this snapshot is <strong>never cloned into the acquirer's tenant</strong> - it is rendered
 * directly from the publication's payload (same role as app-store screenshots). Because it
 * isn't executed in the acquirer's context, we skip workflow-action sanitization and
 * embedded-table recursion: the landing page is read-only marketing material.
 *
 * <p>Validates ownership at snapshot time so a publisher cannot attach someone else's interface
 * as their landing page.
 */
@Component
public class LandingInterfaceSnapshotter {

    private static final Logger logger = LoggerFactory.getLogger(LandingInterfaceSnapshotter.class);

    /** Internal, never served: see where it is written. */
    public static final String INTERNAL_SOURCE_TENANT_KEY = "_sourceTenantId";

    /**
     * Drop the internal keys from a landing map about to be served.
     *
     * <p>The landing map is echoed key-for-key by more than one anonymously readable
     * endpoint, so a key added for the file copy reaches every marketplace visitor unless it
     * is removed at each exit. Convention: internal keys start with {@code _}, and this is
     * what enforces it. Returns a copy; the argument is not modified, because callers pass
     * the map that lives inside the stored snapshot.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> snapshotWithoutInternalLandingKeys(Map<String, Object> snapshot) {
        if (snapshot == null) return null;
        Object landing = snapshot.get("landingInterface");
        if (!(landing instanceof Map<?, ?> landingMap)) return snapshot;
        Map<String, Object> copy = new LinkedHashMap<>(snapshot);
        copy.put("landingInterface", withoutInternalKeys((Map<String, Object>) landingMap));
        return copy;
    }

    public static Map<String, Object> withoutInternalKeys(Map<String, Object> landing) {
        if (landing == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>(landing);
        copy.keySet().removeIf(k -> k != null && k.startsWith("_"));
        return copy;
    }

    private final InterfaceClient interfaceClient;

    public LandingInterfaceSnapshotter(InterfaceClient interfaceClient) {
        this.interfaceClient = interfaceClient;
    }

    /**
     * Build a presentation snapshot for {@code interfaceId} owned by {@code tenantId}.
     *
     * @throws IllegalArgumentException when the interface does not exist or is not owned by the tenant.
     */
    public Map<String, Object> buildSnapshot(UUID interfaceId, String tenantId) {
        return buildSnapshot(interfaceId, tenantId, null);
    }

    /**
     * Build a presentation snapshot for {@code interfaceId} in the caller's
     * active workspace.
     *
     * @throws IllegalArgumentException when the interface does not exist or is outside the active workspace.
     */
    public Map<String, Object> buildSnapshot(UUID interfaceId, String tenantId, String organizationId) {
        if (interfaceId == null) {
            throw new IllegalArgumentException("interfaceId is required");
        }
        InterfaceDto iface = hasText(organizationId)
                ? interfaceClient.getInterface(interfaceId, tenantId, organizationId)
                : interfaceClient.getInterface(interfaceId, tenantId);
        if (iface == null) {
            throw new IllegalArgumentException("Landing interface not found: " + interfaceId);
        }
        validateScope(iface, tenantId, organizationId);
        if ("web_search".equalsIgnoreCase(iface.getInterfaceType())) {
            throw new IllegalArgumentException(
                "Web-search interfaces cannot be used as a landing page. Pick a page-type interface instead.");
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("interfaceId", iface.getId().toString());
        snapshot.put("name", iface.getName());
        snapshot.put("description", iface.getDescription());
        snapshot.put("htmlTemplate", iface.getHtmlTemplate());
        snapshot.put("cssTemplate", iface.getCssTemplate());
        snapshot.put("jsTemplate", iface.getJsTemplate());
        // The format travels with the templates: the marketplace cards and the landing preview
        // shape their iframe from it, and without it a vertical landing renders at 1280x800.
        snapshot.put("format", iface.getFormat());
        snapshot.put("interfaceType", iface.getInterfaceType());
        snapshot.put("data", iface.getData());
        // Who owns the interface this was built from. In the same organization that is not
        // always the publisher, and publication-service needs it to tell a file the landing
        // may legitimately copy from one a publisher merely named in a hand-written value.
        // Underscore-prefixed because the landing map is echoed key-for-key by more than one
        // anonymously readable endpoint (the landing-snapshot response, and the raw
        // planSnapshot a non-WORKFLOW publication returns from /by-id). A plain key would
        // publish the interface owner tenant - a third party in a cross-org publication - to
        // every marketplace visitor. Readers strip any leading-underscore key.
        snapshot.put(INTERNAL_SOURCE_TENANT_KEY, iface.getTenantId());
        logger.debug("Built landing-interface snapshot for {} (tenant {}, org {})",
                interfaceId, tenantId, hasText(organizationId) ? organizationId : "personal");
        return snapshot;
    }

    private static void validateScope(InterfaceDto iface, String tenantId, String organizationId) {
        if (!ScopeGuard.isInStrictScope(tenantId, organizationId, iface.getTenantId(), iface.getOrganizationId())) {
            throw new IllegalArgumentException(hasText(organizationId)
                    ? "Landing interface does not belong to organization"
                    : "Landing interface does not belong to tenant");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Parse an {@code interfaceId} field (string or UUID) from a request map.
     * Returns {@code null} if absent or blank; throws if present but unparseable.
     */
    public UUID parseInterfaceId(Object raw) {
        if (raw == null) return null;
        String s = raw.toString();
        if (s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid interfaceId format: " + s);
        }
    }
}
