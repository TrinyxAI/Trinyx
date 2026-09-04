package com.apimarketplace.auth.service;

/**
 * Deletes one workspace-owned storage object under an exact durable erasure authority.
 *
 * <p>The auth purge flow owns this port because storage access differs by deployment mode:
 * microservices use the internal storage HTTP client, while the monolith delegates directly
 * to the in-process storage service. Implementations return {@code true} only when physical
 * deletion reached an idempotent terminal (deleted, or already absent where the backend exposes
 * that semantic); {@code false} means the durable erasure dispatcher must retry.
 */
@FunctionalInterface
public interface WorkspaceStorageObjectDeleter {

    boolean delete(java.util.UUID erasureId, String organizationId, String tenantId, String key);
}
