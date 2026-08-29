package com.apimarketplace.common.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StorageOperationCapabilityTest {

    private static final String SECRET = "storage-authority-secret-32-chars!!";

    @Test
    void workspaceErasureCapabilityBindsEveryDurableResourceField() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        String token = StorageOperationCapability.issueWorkspaceErasure(
                SECRET, id, "org-1", "tenant-1", "tenant-1/object", now);

        assertThat(StorageOperationCapability.verifyWorkspaceErasure(
                token, SECRET, id, "org-1", "tenant-1",
                "tenant-1/object", now.plusSeconds(60))).isTrue();
        assertThat(StorageOperationCapability.verifyWorkspaceErasure(
                token, SECRET, id, "org-2", "tenant-1",
                "tenant-1/object", now.plusSeconds(60))).isFalse();
        assertThat(StorageOperationCapability.verifyWorkspaceErasure(
                token, SECRET, id, "org-1", "tenant-2",
                "tenant-1/object", now.plusSeconds(60))).isFalse();
        assertThat(StorageOperationCapability.verifyWorkspaceErasure(
                token, SECRET, id, "org-1", "tenant-1",
                "tenant-1/other", now.plusSeconds(60))).isFalse();
    }

    @Test
    void capabilityExpiresAndRejectsTampering() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        String token = StorageOperationCapability.issueWorkspaceErasure(
                SECRET, id, "org", "tenant", "tenant/object", now);

        assertThat(StorageOperationCapability.verifyWorkspaceErasure(
                token, SECRET, id, "org", "tenant", "tenant/object",
                now.plusSeconds(301))).isFalse();
        assertThat(StorageOperationCapability.verifyWorkspaceErasure(
                token + "x", SECRET, id, "org", "tenant", "tenant/object",
                now.plusSeconds(1))).isFalse();
    }
}
