package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Cloud-side adapter. It authorizes from the signed projection, delegates all
 * spend decisions to paid-monolith and stores only operation/audit state.
 */
@Service
public class ExternalCreditProxyService {

    private final EntitlementProjectionService entitlements;
    private final PaidMonolithCreditClient authority;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ExternalCreditProxyService(EntitlementProjectionService entitlements,
                                      PaidMonolithCreditClient authority,
                                      JdbcTemplate jdbc,
                                      ObjectMapper json) {
        this.entitlements = entitlements;
        this.authority = authority;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public ReserveResult reserve(Context context, ReserveCommand command) {
        validate(context, command);
        EntitlementProjectionService.Decision decision = entitlements.authorize(
                context.installId(), context.organizationId(), context.billingSubjectId(),
                command.feature(), true);
        if (!decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }

        String requestHash = CanonicalJson.sha256(json.valueToTree(new ReserveHash(
                command.operationId(), context, decision.sequence(), command.sourceType(),
                command.estimatedCredits(), command.maximumCredits(), command.provider(),
                command.model())));
        var request = new CloudCreditAuthorityService.ReserveRequest(
                command.operationId(), context.principalId(), context.billingSubjectId(),
                context.organizationId(), context.installId(), decision.sequence(),
                command.sourceType(), command.estimatedCredits(), command.maximumCredits(),
                command.provider(), command.model(), requestHash);
        var response = authority.reserve(request);

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
                context.installId(), decision.sequence(), command.sourceType(),
                command.estimatedCredits(), command.maximumCredits(), command.provider(),
                command.model(), write(response), Timestamp.from(response.expiresAt()),
                Timestamp.from(response.expiresAt().plus(Duration.ofHours(24))));
        return new ReserveResult(response, requestHash, decision.sequence());
    }


    @Transactional(readOnly = true)
    public String requestHash(UUID operationId) {
        var values = jdbc.query("SELECT request_hash FROM auth.cloud_credit_operation WHERE operation_id=?",
                (rs, row) -> rs.getString(1), operationId);
        if (values.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EXTERNAL_RESERVATION_NOT_FOUND");
        }
        return values.getFirst();
    }

    @Transactional
    public SettlementResult commit(UUID operationId, CommitCommand command) {
        var request = new CloudCreditAuthorityService.CommitRequest(
                command.actualCredits(), command.provider(), command.model(),
                command.providerRequestId(), command.promptTokens(),
                command.completionTokens(), command.requestHash());
        try {
            var response = authority.commit(operationId, request);
            markSettled(operationId, response.state(), response);
            return new SettlementResult(response, false);
        } catch (RuntimeException failure) {
            queue(operationId, "COMMIT", command.requestHash(), request, failure);
            return new SettlementResult(null, true);
        }
    }

    @Transactional
    public SettlementResult release(UUID operationId, ReleaseCommand command) {
        var request = new CloudCreditAuthorityService.ReleaseRequest(
                command.reason(), command.requestHash());
        try {
            var response = authority.release(operationId, request);
            markSettled(operationId, response.state(), response);
            return new SettlementResult(response, false);
        } catch (RuntimeException failure) {
            queue(operationId, "RELEASE", command.requestHash(), request, failure);
            return new SettlementResult(null, true);
        }
    }

    private void markSettled(UUID operationId, String state, Object response) {
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state=?, response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE operation_id=?
                """, state, write(response), operationId);
        jdbc.update("""
                UPDATE auth.cloud_settlement_outbox
                SET status='DELIVERED', delivered_at=now(), last_error=NULL
                WHERE operation_id=? AND status IN ('PENDING','PROCESSING','FAILED')
                """, operationId);
    }

    private void queue(UUID operationId, String action, String requestHash,
                       Object payload, RuntimeException failure) {
        int inserted = jdbc.update("""
                INSERT INTO auth.cloud_settlement_outbox
                (id, operation_id, action, request_hash, payload, status, next_attempt_at, last_error)
                VALUES (?,?,?,?,CAST(? AS jsonb),'PENDING',now(),?)
                ON CONFLICT (operation_id, action, request_hash)
                DO UPDATE SET status='PENDING', next_attempt_at=LEAST(
                    auth.cloud_settlement_outbox.next_attempt_at, now()),
                    last_error=EXCLUDED.last_error
                """, UUID.randomUUID(), operationId, action, requestHash, write(payload),
                bounded(failure.getMessage()));
        if (inserted == 0) {
            throw new IllegalStateException("Could not persist settlement retry", failure);
        }
    }

    private void validate(Context context, ReserveCommand command) {
        if (context == null || context.principalId() == null || context.billingSubjectId() == null
                || context.organizationId() == null || context.installId() == null
                || command == null || command.operationId() == null
                || command.sourceType() == null || command.sourceType().isBlank()
                || command.maximumCredits() == null || command.maximumCredits().signum() <= 0
                || command.estimatedCredits() == null || command.estimatedCredits().signum() < 0
                || command.estimatedCredits().compareTo(command.maximumCredits()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_RESERVATION");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize external wallet operation", e);
        }
    }

    private static String bounded(String message) {
        String value = message == null ? "transport failure" : message;
        return value.substring(0, Math.min(2000, value.length()));
    }

    private record ReserveHash(UUID operationId, Context context, long entitlementSequence,
                               String sourceType, BigDecimal estimatedCredits,
                               BigDecimal maximumCredits, String provider, String model) {}

    public record Context(UUID principalId, UUID billingSubjectId,
                          UUID organizationId, UUID installId) {}
    public record ReserveCommand(UUID operationId, String feature, String sourceType,
                                 BigDecimal estimatedCredits, BigDecimal maximumCredits,
                                 String provider, String model) {}
    public record CommitCommand(BigDecimal actualCredits, String provider, String model,
                                String providerRequestId, Long promptTokens,
                                Long completionTokens, String requestHash) {}
    public record ReleaseCommand(String reason, String requestHash) {}
    public record ReserveResult(CloudCreditAuthorityService.ReserveResponse authority,
                                String requestHash, long entitlementSequence) {}
    public record SettlementResult(CloudCreditAuthorityService.SettlementResponse authority,
                                   boolean queued) {}
}
