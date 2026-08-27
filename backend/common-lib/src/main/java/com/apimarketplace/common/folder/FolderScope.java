package com.apimarketplace.common.folder;

/**
 * The caller's workspace, as every folder operation sees it: the acting user plus the
 * active organization ({@code X-User-ID} / {@code X-Organization-ID}). Personal
 * workspaces carry a {@code null} organization, which
 * {@code ScopeGuard.isInStrictScope} maps onto the owner-only branch.
 */
public record FolderScope(String userId, String organizationId) {

    public boolean hasOrganization() {
        return organizationId != null && !organizationId.isBlank();
    }
}
