package com.apimarketplace.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Commits the two Cloud-side settlement state transitions atomically after the
 * remote paid authority call has completed. No network I/O is performed here.
 */
@Service
public class CloudSettlementResultWriter {

    private final JdbcTemplate jdbc;

    public CloudSettlementResultWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void delivered(UUID outboxId, UUID operationId, String state, String responseJson) {
        jdbc.update("""
                UPDATE auth.cloud_settlement_outbox
                SET status='DELIVERED', delivered_at=now(), last_error=NULL
                WHERE id=? AND status='PROCESSING'
                """, outboxId);
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state=?, response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE operation_id=?
                """, state, responseJson, operationId);
    }

    @Transactional
    public void dead(UUID outboxId, UUID operationId, int attempt, String error) {
        jdbc.update("""
                UPDATE auth.cloud_settlement_outbox
                SET status='DEAD', attempt_count=?, last_error=?, terminal_at=now()
                WHERE id=? AND status='PROCESSING'
                """, attempt, error, outboxId);
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state='SETTLEMENT_FAILED', updated_at=now()
                WHERE operation_id=? AND state IN ('RESERVED','EXPIRED')
                """, operationId);
    }
}
