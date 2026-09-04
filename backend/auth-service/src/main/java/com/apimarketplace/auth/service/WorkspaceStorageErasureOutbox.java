package com.apimarketplace.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Durable source of truth for physical workspace-object erasure.
 *
 * <p>The purge transaction inserts these rows before deleting storage metadata.
 * A separate dispatcher performs object-store I/O outside a database transaction
 * and CASes the result with a claim token. Failed and reclaimed work survives
 * application restarts.
 */
@Service
public class WorkspaceStorageErasureOutbox {

    private final JdbcTemplate jdbc;

    public WorkspaceStorageErasureOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID enqueue(String organizationId, String tenantId, String storageKey) {
        validateOwnership(organizationId, tenantId, storageKey);
        UUID id = UUID.nameUUIDFromBytes(
                ("workspace-storage-erasure:" + organizationId + ":" + tenantId + ":"
                        + storageKey).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO auth.workspace_storage_erasure_outbox
                (id, organization_id, tenant_id, storage_key, status, next_attempt_at)
                VALUES (?,?,?,?, 'PENDING', now())
                ON CONFLICT (organization_id, tenant_id, storage_key) DO NOTHING
                """, id, organizationId, tenantId, storageKey);
        return id;
    }

    @Transactional
    public List<Erasure> claimDue(int limit) {
        return jdbc.query("""
                UPDATE auth.workspace_storage_erasure_outbox
                SET status='PROCESSING', claim_token=gen_random_uuid(),
                    next_attempt_at=now() + interval '60 seconds', updated_at=now()
                WHERE id IN (
                    SELECT id FROM auth.workspace_storage_erasure_outbox
                    WHERE status IN ('PENDING','FAILED','PROCESSING')
                      AND next_attempt_at <= now()
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                RETURNING id, organization_id, tenant_id, storage_key,
                          attempt_count, claim_token
                """, (rs, row) -> new Erasure(
                rs.getObject("id", UUID.class),
                rs.getString("organization_id"),
                rs.getString("tenant_id"),
                rs.getString("storage_key"),
                rs.getInt("attempt_count"),
                rs.getObject("claim_token", UUID.class)), Math.max(1, limit));
    }

    @Transactional
    public boolean delivered(Erasure erasure) {
        return jdbc.update("""
                UPDATE auth.workspace_storage_erasure_outbox
                SET status='DELIVERED', delivered_at=now(), last_error=NULL,
                    claim_token=NULL, updated_at=now()
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                """, erasure.id(), erasure.claimToken()) == 1;
    }

    @Transactional
    public boolean failed(Erasure erasure, String error) {
        int nextAttempt = erasure.attemptCount() + 1;
        long delaySeconds = Math.min(3600L, 1L << Math.min(11, nextAttempt));
        return jdbc.update("""
                UPDATE auth.workspace_storage_erasure_outbox
                SET status='FAILED', attempt_count=?, next_attempt_at=?,
                    last_error=?, claim_token=NULL, updated_at=now()
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                """, nextAttempt, Timestamp.from(Instant.now().plusSeconds(delaySeconds)),
                bounded(error), erasure.id(), erasure.claimToken()) == 1;
    }

    static void validateOwnership(
            String organizationId, String tenantId, String storageKey) {
        if (organizationId == null || organizationId.isBlank()
                || tenantId == null || tenantId.isBlank()
                || storageKey == null || storageKey.isBlank()
                || !storageKey.startsWith(tenantId + "/")) {
            throw new IllegalArgumentException(
                    "Workspace storage erasure key is outside tenant ownership");
        }
    }

    private static String bounded(String error) {
        String value = error == null || error.isBlank()
                ? "storage object deletion failed" : error;
        return value.substring(0, Math.min(2000, value.length()));
    }

    public record Erasure(UUID id, String organizationId, String tenantId,
                          String storageKey, int attemptCount, UUID claimToken) {}
}
