package com.apimarketplace.auth.service;

/**
 * Deletes one workspace-owned storage object while preserving tenant/key ownership checks.
 *
 * <p>The auth purge flow owns this port because storage access differs by deployment mode:
 * microservices use the internal storage HTTP client, while the monolith delegates directly
 * to the in-process storage service. Implementations return {@code false} when deletion is
 * refused or the object is absent; {@link WorkspaceDataPurger} keeps deletion best-effort.
 */
@FunctionalInterface
public interface WorkspaceStorageObjectDeleter {

    boolean delete(String tenantId, String key);
}
