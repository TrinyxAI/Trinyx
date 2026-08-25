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
    public void delivered(UUID outboxId, UUID operationId, String requestHash,
                          UUID claimToken, String state, String responseJson) {
        int acknowledged = jdbc.update("""
                UPDATE auth.cloud_settlement_outbox
                SET status='DELIVERED', delivered_at=now(), last_error=NULL,
                    claim_token=NULL
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                """, outboxId, claimToken);
        if (acknowledged != 1) {
            return;
        }
        if (terminalState(state)) {
            jdbc.update("""
                    UPDATE auth.cloud_settlement_outbox
                    SET status='DELIVERED', delivered_at=now(), last_error=NULL,
                        claim_token=NULL
                    WHERE operation_id=? AND action='OUTCOME_UNKNOWN' AND request_hash=?
                      AND status IN ('PENDING','PROCESSING','FAILED')
                    """, operationId, requestHash);
        }
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state=?, response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE operation_id=?
                  AND (state NOT IN ('COMMITTED','COMMITTED_DELINQUENT','RELEASED')
                       OR state=?)
                """, state, responseJson, operationId, state);
    }

    private static boolean terminalState(String state) {
        return "COMMITTED".equals(state)
                || "COMMITTED_DELINQUENT".equals(state)
                || "RELEASED".equals(state);
    }

    @Transactional
    public void dead(UUID outboxId, UUID operationId, UUID claimToken,
                     int attempt, String error) {
        int acknowledged = jdbc.update("""
                UPDATE auth.cloud_settlement_outbox
                SET status='DEAD', attempt_count=?, last_error=?, terminal_at=now(),
                    claim_token=NULL
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                """, attempt, error, outboxId, claimToken);
        if (acknowledged != 1) {
            return;
        }
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state='SETTLEMENT_FAILED', updated_at=now()
                WHERE operation_id=? AND state IN ('RESERVED','DISPATCHING','EXPIRED','OUTCOME_UNKNOWN','OUTCOME_UNKNOWN_EXPIRED')
                """, operationId);
    }
}
