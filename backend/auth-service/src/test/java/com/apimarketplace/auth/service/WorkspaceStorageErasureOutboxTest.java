package com.apimarketplace.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceStorageErasureOutboxTest {

    @Test
    void enqueueIsIdempotentAndRejectsCrossTenantKeysBeforeWriting() {
        FakeJdbc jdbc = new FakeJdbc();
        WorkspaceStorageErasureOutbox outbox =
                new WorkspaceStorageErasureOutbox(jdbc);

        UUID first = outbox.enqueue("org-1", "tenant-1", "tenant-1/report.pdf");
        UUID retry = outbox.enqueue("org-1", "tenant-1", "tenant-1/report.pdf");

        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.sql).allMatch(sql -> sql.contains(
                "ON CONFLICT (organization_id, tenant_id, storage_key) DO NOTHING"));

        int writes = jdbc.sql.size();
        assertThatThrownBy(() -> outbox.enqueue(
                "org-1", "tenant-2", "tenant-1/report.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside tenant ownership");
        assertThat(jdbc.sql).hasSize(writes);
    }

    @Test
    void reclaimFencesStaleOwnerAndCurrentOwnerAloneCanComplete() {
        FakeJdbc jdbc = new FakeJdbc();
        WorkspaceStorageErasureOutbox outbox =
                new WorkspaceStorageErasureOutbox(jdbc);
        UUID id = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        var workerA = new WorkspaceStorageErasureOutbox.Erasure(
                id, "org-1", "tenant-1", "tenant-1/a.bin", 0, stale);
        var workerB = new WorkspaceStorageErasureOutbox.Erasure(
                id, "org-1", "tenant-1", "tenant-1/a.bin", 0, current);
        jdbc.currentClaimToken = current;

        assertThat(outbox.delivered(workerA)).isFalse();
        assertThat(jdbc.currentClaimToken).isEqualTo(current);
        assertThat(outbox.failed(workerA, "stale")).isFalse();
        assertThat(jdbc.currentClaimToken).isEqualTo(current);

        assertThat(outbox.delivered(workerB)).isTrue();
        assertThat(jdbc.currentClaimToken).isNull();
        assertThat(jdbc.sql).anyMatch(sql -> sql.contains(
                "status='PROCESSING' AND claim_token=?"));
    }

    @Test
    void claimUsesShortTransactionalFencedLease() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.claim = new WorkspaceStorageErasureOutbox.Erasure(
                UUID.randomUUID(), "org-1", "tenant-1", "tenant-1/a.bin",
                2, UUID.randomUUID());
        WorkspaceStorageErasureOutbox outbox =
                new WorkspaceStorageErasureOutbox(jdbc);

        assertThat(outbox.claimDue(25)).containsExactly(jdbc.claim);
        assertThat(jdbc.claimSql)
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("claim_token=gen_random_uuid()")
                .contains("RETURNING id");

        assertThat(WorkspaceStorageErasureOutbox.class
                .getMethod("claimDue", int.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(WorkspaceStorageErasureOutbox.class
                .getMethod("delivered",
                        WorkspaceStorageErasureOutbox.Erasure.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        UUID currentClaimToken;
        WorkspaceStorageErasureOutbox.Erasure claim;
        String claimSql;

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            if (statement.contains("status='PROCESSING'")
                    && statement.contains("claim_token=?")) {
                UUID supplied = (UUID) args[args.length - 1];
                if (currentClaimToken == null
                        || !currentClaimToken.equals(supplied)) {
                    return 0;
                }
                currentClaimToken = null;
            }
            return 1;
        }

        @Override
        public <T> List<T> query(
                String statement, RowMapper<T> mapper, Object... args) {
            claimSql = statement;
            if (claim == null) return List.of();
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(claim.id());
                when(rs.getString("organization_id"))
                        .thenReturn(claim.organizationId());
                when(rs.getString("tenant_id")).thenReturn(claim.tenantId());
                when(rs.getString("storage_key")).thenReturn(claim.storageKey());
                when(rs.getInt("attempt_count"))
                        .thenReturn(claim.attemptCount());
                when(rs.getObject("claim_token", UUID.class))
                        .thenReturn(claim.claimToken());
                return List.of(mapper.mapRow(rs, 0));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
