package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "external-paid-monolith")
public class CloudSettlementOutboxDispatcher {

    private final JdbcTemplate jdbc;
    private final PaidMonolithCreditClient authority;
    private final ObjectMapper json;

    public CloudSettlementOutboxDispatcher(JdbcTemplate jdbc,
                                           PaidMonolithCreditClient authority,
                                           ObjectMapper json) {
        this.jdbc = jdbc;
        this.authority = authority;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${billing.external.settlement-retry-ms:5000}")
    @Transactional
    public void dispatch() {
        var rows = jdbc.query("""
                SELECT id, operation_id, action, payload::text, attempt_count
                FROM auth.cloud_settlement_outbox
                WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= now()
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 25
                """, (rs, row) -> new Pending(
                rs.getObject("id", UUID.class),
                rs.getObject("operation_id", UUID.class),
                rs.getString("action"),
                rs.getString("payload"),
                rs.getInt("attempt_count")));
        for (Pending pending : rows) {
            try {
                Object response;
                if ("COMMIT".equals(pending.action())) {
                    var request = json.readValue(pending.payload(),
                            CloudCreditAuthorityService.CommitRequest.class);
                    response = authority.commit(pending.operationId(), request);
                } else {
                    var request = json.readValue(pending.payload(),
                            CloudCreditAuthorityService.ReleaseRequest.class);
                    response = authority.release(pending.operationId(), request);
                }
                jdbc.update("""
                        UPDATE auth.cloud_settlement_outbox
                        SET status='DELIVERED', delivered_at=now(), last_error=NULL
                        WHERE id=?
                        """, pending.id());
                jdbc.update("""
                        UPDATE auth.cloud_credit_operation
                        SET state=?, response_payload=CAST(? AS jsonb), updated_at=now()
                        WHERE operation_id=?
                        """, state(response), json.writeValueAsString(response),
                        pending.operationId());
            } catch (Exception failure) {
                int attempt = pending.attemptCount() + 1;
                if (isPermanent(failure)) {
                    jdbc.update("""
                            UPDATE auth.cloud_settlement_outbox
                            SET status='DEAD', attempt_count=?, last_error=?,
                                terminal_at=now()
                            WHERE id=?
                            """, attempt, bounded(failure.getMessage()), pending.id());
                    jdbc.update("""
                            UPDATE auth.cloud_credit_operation
                            SET state='SETTLEMENT_FAILED', updated_at=now()
                            WHERE operation_id=? AND state IN ('RESERVED','EXPIRED')
                            """, pending.operationId());
                    continue;
                }
                long capSeconds = Math.min(300, 1L << Math.min(8, attempt));
                long delay = ThreadLocalRandom.current().nextLong(
                        Math.max(1, capSeconds / 2), capSeconds + 1);
                jdbc.update("""
                        UPDATE auth.cloud_settlement_outbox
                        SET status='FAILED', attempt_count=?, next_attempt_at=?,
                            last_error=?
                        WHERE id=?
                        """, attempt, Timestamp.from(Instant.now().plusSeconds(delay)),
                        bounded(failure.getMessage()), pending.id());
            }
        }
    }

    private static boolean isPermanent(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PaidMonolithCreditClient.PermanentAuthorityException
                    || current instanceof com.fasterxml.jackson.core.JsonProcessingException
                    || current instanceof IllegalArgumentException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String state(Object response) {
        if (response instanceof CloudCreditAuthorityService.SettlementResponse settlement) {
            return settlement.state();
        }
        throw new IllegalArgumentException("Unknown settlement response");
    }

    private static String bounded(String message) {
        String value = message == null ? "settlement retry failed" : message;
        return value.substring(0, Math.min(2000, value.length()));
    }

    private record Pending(UUID id, UUID operationId, String action,
                           String payload, int attemptCount) {}
}
