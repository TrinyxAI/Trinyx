package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

/**
 * Short Cloud-side database transitions around paid-authority calls.
 *
 * <p>No network I/O is allowed in this component. The proxy performs HTTP before
 * entering these transactions, so a slow authority cannot pin a PostgreSQL
 * connection. Remote success followed by a local crash converges through the
 * immutable operationId/requestHash and the producer/outbox retries.
 */
@Service
public class ExternalCreditProxyStateWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ExternalCreditProxyStateWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void reserved(
            ExternalCreditProxyService.Context context,
            ExternalCreditProxyService.ReserveCommand command,
            long entitlementSequence,
            String requestHash,
            CloudCreditAuthorityService.ReserveResponse response) {
        jdbc.update("""
                INSERT INTO auth.cloud_credit_operation
                (operation_id, reservation_id, request_hash, principal_id, billing_subject_id,
                 organization_id, install_id, entitlement_sequence, source_type,
                 estimated_credits, maximum_credits, provider, model, state,
                 response_payload, expires_at, late_settlement_until)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',CAST(? AS jsonb),?,?)
                ON CONFLICT (operation_id) DO NOTHING
                """, command.operationId(), response.reservationId(), requestHash,
                context.principalId(), context.billingSubjectId(), context.organizationId(),
                context.installId(), entitlementSequence, command.sourceType(),
                command.estimatedCredits(), command.maximumCredits(), command.provider(),
                command.model(), write(response), Timestamp.from(response.expiresAt()),
                Timestamp.from(response.expiresAt().plus(Duration.ofHours(24))));
    }

    @Transactional
    public boolean dispatching(
            UUID operationId,
            CloudCreditAuthorityService.SettlementResponse response) {
        return jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state='DISPATCHING', response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE operation_id=? AND state IN ('RESERVED','DISPATCHING')
                """, write(response), operationId) == 1;
    }

    @Transactional
    public void settled(UUID operationId, String action, String requestHash,
                        String state, Object response) {
        if (terminalState(state)) {
            jdbc.update("""
                    UPDATE auth.cloud_settlement_outbox
                    SET status='DELIVERED', delivered_at=now(), last_error=NULL,
                        claim_token=NULL
                    WHERE operation_id=? AND action=? AND request_hash=?
                      AND status IN ('PENDING','PROCESSING','FAILED')
                    """, operationId, action, requestHash);
            jdbc.update("""
                    UPDATE auth.cloud_settlement_outbox
                    SET status='DELIVERED', delivered_at=now(), last_error=NULL,
                        claim_token=NULL
                    WHERE operation_id=? AND action='OUTCOME_UNKNOWN' AND request_hash=?
                      AND status IN ('PENDING','PROCESSING','FAILED')
                    """, operationId, requestHash);
        } else {
            jdbc.update("""
                    UPDATE auth.cloud_settlement_outbox
                    SET status='DELIVERED', delivered_at=now(), last_error=NULL,
                        claim_token=NULL
                    WHERE operation_id=? AND action=? AND request_hash=?
                      AND status IN ('PENDING','PROCESSING','FAILED')
                    """, operationId, action, requestHash);
        }
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state=?, response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE operation_id=?
                  AND (state NOT IN ('COMMITTED','COMMITTED_DELINQUENT','RELEASED')
                       OR state=?)
                """, state, write(response), operationId, state);
    }

    @Transactional
    public void terminal(UUID operationId, String action, String requestHash,
                         Object payload, RuntimeException failure) {
        jdbc.update("""
                INSERT INTO auth.cloud_settlement_outbox
                (id, operation_id, action, request_hash, payload, status, next_attempt_at,
                 last_error, terminal_at, claim_token)
                VALUES (?,?,?,?,CAST(? AS jsonb),'DEAD',now(),?,now(),NULL)
                ON CONFLICT (operation_id, action, request_hash)
                DO UPDATE SET status='DEAD', last_error=EXCLUDED.last_error,
                    terminal_at=now(), claim_token=NULL
                """, UUID.randomUUID(), operationId, action, requestHash, write(payload),
                bounded(failure.getMessage()));
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state='SETTLEMENT_FAILED', updated_at=now()
                WHERE operation_id=? AND state IN
                    ('RESERVED','DISPATCHING','EXPIRED','OUTCOME_UNKNOWN','OUTCOME_UNKNOWN_EXPIRED')
                """, operationId);
    }

    @Transactional
    public void queue(UUID operationId, String action, String requestHash,
                      Object payload, RuntimeException failure) {
        jdbc.update("""
                INSERT INTO auth.cloud_settlement_outbox
                (id, operation_id, action, request_hash, payload, status,
                 next_attempt_at, last_error, claim_token)
                VALUES (?,?,?,?,CAST(? AS jsonb),'PENDING',now(),?,NULL)
                ON CONFLICT (operation_id, action, request_hash)
                DO UPDATE SET status='PENDING', next_attempt_at=LEAST(
                    auth.cloud_settlement_outbox.next_attempt_at, now()),
                    last_error=EXCLUDED.last_error, claim_token=NULL
                WHERE auth.cloud_settlement_outbox.status IN ('PENDING','FAILED')
                """, UUID.randomUUID(), operationId, action, requestHash, write(payload),
                bounded(failure.getMessage()));
    }

    private static boolean terminalState(String state) {
        return "COMMITTED".equals(state)
                || "COMMITTED_DELINQUENT".equals(state)
                || "RELEASED".equals(state);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Could not serialize external wallet operation", failure);
        }
    }

    private static String bounded(String message) {
        String value = message == null ? "transport failure" : message;
        return value.substring(0, Math.min(2000, value.length()));
    }
}
