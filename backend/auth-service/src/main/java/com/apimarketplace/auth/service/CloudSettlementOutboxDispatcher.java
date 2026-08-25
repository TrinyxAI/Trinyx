package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "external-paid-monolith")
public class CloudSettlementOutboxDispatcher {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(
            CloudSettlementOutboxDispatcher.class);

    private final JdbcTemplate jdbc;
    private final PaidMonolithCreditClient authority;
    private final ObjectMapper json;
    private final CloudSettlementResultWriter resultWriter;

    public CloudSettlementOutboxDispatcher(JdbcTemplate jdbc,
                                           PaidMonolithCreditClient authority,
                                           ObjectMapper json,
                                           CloudSettlementResultWriter resultWriter) {
        this.jdbc = jdbc;
        this.authority = authority;
        this.json = json;
        this.resultWriter = resultWriter;
    }

    @Scheduled(fixedDelayString = "${billing.external.settlement-retry-ms:5000}")
    public void dispatch() {
        // Claim and commit in one short SQL statement. HTTP delivery happens after
        // the row locks are released; PROCESSING rows become claimable after the
        // lease if this process crashes before recording the result.
        var rows = jdbc.query("""
                UPDATE auth.cloud_settlement_outbox
                SET status='PROCESSING', next_attempt_at=now() + interval '60 seconds',
                    claim_token=gen_random_uuid()
                WHERE id IN (
                    SELECT id FROM auth.cloud_settlement_outbox
                    WHERE status IN ('PENDING','FAILED','PROCESSING')
                      AND next_attempt_at <= now()
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 25
                )
                RETURNING id, operation_id, action, request_hash, payload::text, attempt_count,
                          claim_token
                """, (rs, row) -> new Pending(
                rs.getObject("id", UUID.class),
                rs.getObject("operation_id", UUID.class),
                rs.getString("action"),
                rs.getString("request_hash"),
                rs.getString("payload"),
                rs.getInt("attempt_count"),
                rs.getObject("claim_token", UUID.class)));
        for (Pending pending : rows) {
            try {
                Object response;
                if ("COMMIT".equals(pending.action())) {
                    var request = json.readValue(pending.payload(),
                            CloudCreditAuthorityService.CommitRequest.class);
                    response = authority.commit(pending.operationId(), request);
                } else if ("RELEASE".equals(pending.action())) {
                    var request = json.readValue(pending.payload(),
                            CloudCreditAuthorityService.ReleaseRequest.class);
                    response = authority.release(pending.operationId(), request);
                } else if ("OUTCOME_UNKNOWN".equals(pending.action())) {
                    var request = json.readValue(pending.payload(),
                            CloudCreditAuthorityService.OutcomeUnknownRequest.class);
                    response = authority.outcomeUnknown(pending.operationId(), request);
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported settlement action " + pending.action());
                }
                // Remote I/O is complete. Record both local state transitions in one
                // short transaction so a crash cannot leave DELIVERED + RESERVED.
                resultWriter.delivered(pending.id(), pending.operationId(),
                        pending.requestHash(), pending.claimToken(), state(response),
                        json.writeValueAsString(response));
            } catch (Exception failure) {
                int attempt = pending.attemptCount() + 1;
                if (isPermanent(failure)) {
                    resultWriter.dead(pending.id(), pending.operationId(), pending.claimToken(),
                            attempt, bounded(failure.getMessage()));
                    log.error("Settlement moved to DEAD operationId={} action={} attempt={} type={}",
                            pending.operationId(), pending.action(), attempt,
                            failure.getClass().getSimpleName());
                    continue;
                }
                long capSeconds = Math.min(300, 1L << Math.min(8, attempt));
                long delay = ThreadLocalRandom.current().nextLong(
                        Math.max(1, capSeconds / 2), capSeconds + 1);
                jdbc.update("""
                        UPDATE auth.cloud_settlement_outbox
                        SET status='FAILED', attempt_count=?, next_attempt_at=?,
                            last_error=?, claim_token=NULL
                        WHERE id=? AND status='PROCESSING' AND claim_token=?
                        """, attempt, Timestamp.from(Instant.now().plusSeconds(delay)),
                        bounded(failure.getMessage()), pending.id(), pending.claimToken());
                log.warn("Settlement retry scheduled operationId={} action={} attempt={} delaySeconds={} type={}",
                        pending.operationId(), pending.action(), attempt, delay,
                        failure.getClass().getSimpleName());
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
                           String requestHash, String payload, int attemptCount,
                           UUID claimToken) {}
}
