package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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

    public ReserveResult reserveLlm(Context context, LlmReserveCommand command) {
        if (command == null || command.provider() == null || command.provider().isBlank()
                || command.model() == null || command.model().isBlank()
                || command.estimatedPromptTokens() < 0
                || command.maximumCompletionTokens() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_BUDGET");
        }
        // These positive placeholders satisfy the generic Cloud audit schema only.
        // Paid-monolith ignores them for LLM sources and authoritatively prices the
        // token ceiling below before it takes the wallet hold.
        BigDecimal hint = new BigDecimal("0.000001");
        return reserve(context, new ReserveCommand(command.operationId(), command.feature(),
                command.sourceType(), hint, hint, command.provider(), command.model(),
                command.estimatedPromptTokens(), command.maximumCompletionTokens()));
    }

    public SettlementResult commitLlm(UUID operationId, LlmCommitCommand command) {
        int prompt = nonNegative(command.promptTokens());
        int completion = nonNegative(command.completionTokens());
        int cacheCreation = nonNegative(command.cacheCreationTokens());
        int cacheRead = nonNegative(command.cacheReadTokens());
        int cached = nonNegative(command.cachedTokens());
        int reasoning = nonNegative(command.reasoningTokens());
        // Cloud supplies raw provider usage only. The paid authority deliberately
        // ignores this placeholder and recomputes the debit from its own pricing.
        return commit(operationId, new CommitCommand(BigDecimal.ZERO,
                command.provider(), command.model(), command.providerRequestId(),
                (long) prompt, (long) completion, command.requestHash(),
                cacheCreation, cacheRead, cached, reasoning));
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
                command.model(), command.estimatedPromptTokens(),
                command.maximumCompletionTokens())));
        var request = new CloudCreditAuthorityService.ReserveRequest(
                command.operationId(), context.principalId(), context.billingSubjectId(),
                context.organizationId(), context.installId(), decision.sequence(),
                command.sourceType(), command.estimatedCredits(), command.maximumCredits(),
                command.provider(), command.model(), requestHash,
                command.estimatedPromptTokens(), command.maximumCompletionTokens());
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

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SettlementResult commit(UUID operationId, CommitCommand command) {
        var request = new CloudCreditAuthorityService.CommitRequest(
                command.actualCredits(), command.provider(), command.model(),
                command.providerRequestId(), command.promptTokens(),
                command.completionTokens(), command.requestHash(),
                command.cacheCreationTokens(), command.cacheReadTokens(),
                command.cachedTokens(), command.reasoningTokens());
        try {
            var response = authority.commit(operationId, request);
            markSettled(operationId, response.state(), response);
            return new SettlementResult(response, false);
        } catch (PaidMonolithCreditClient.PermanentAuthorityException permanent) {
            terminal(operationId, "COMMIT", command.requestHash(), request, permanent);
            throw new ResponseStatusException(HttpStatusCode.valueOf(permanent.statusCode()),
                    "BILLING_AUTHORITY_TERMINAL_REJECTION", permanent);
        } catch (PaidMonolithCreditClient.RetryableAuthorityException failure) {
            queue(operationId, "COMMIT", command.requestHash(), request, failure);
            return new SettlementResult(null, true);
        }
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SettlementResult release(UUID operationId, ReleaseCommand command) {
        var request = new CloudCreditAuthorityService.ReleaseRequest(
                command.reason(), command.requestHash());
        try {
            var response = authority.release(operationId, request);
            markSettled(operationId, response.state(), response);
            return new SettlementResult(response, false);
        } catch (PaidMonolithCreditClient.PermanentAuthorityException permanent) {
            terminal(operationId, "RELEASE", command.requestHash(), request, permanent);
            throw new ResponseStatusException(HttpStatusCode.valueOf(permanent.statusCode()),
                    "BILLING_AUTHORITY_TERMINAL_REJECTION", permanent);
        } catch (PaidMonolithCreditClient.RetryableAuthorityException failure) {
            queue(operationId, "RELEASE", command.requestHash(), request, failure);
            return new SettlementResult(null, true);
        }
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SettlementResult outcomeUnknown(UUID operationId, OutcomeUnknownCommand command) {
        String requestHash = command.requestHash() == null || command.requestHash().isBlank()
                ? requestHash(operationId) : command.requestHash();
        var request = new CloudCreditAuthorityService.OutcomeUnknownRequest(
                command.reason(), requestHash, command.provider(), command.model());
        try {
            var response = authority.outcomeUnknown(operationId, request);
            markSettled(operationId, response.state(), response);
            return new SettlementResult(response, false);
        } catch (PaidMonolithCreditClient.PermanentAuthorityException permanent) {
            terminal(operationId, "OUTCOME_UNKNOWN", requestHash, request, permanent);
            throw new ResponseStatusException(HttpStatusCode.valueOf(permanent.statusCode()),
                    "BILLING_AUTHORITY_TERMINAL_REJECTION", permanent);
        } catch (PaidMonolithCreditClient.RetryableAuthorityException failure) {
            queue(operationId, "OUTCOME_UNKNOWN", requestHash, request, failure);
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

    private void terminal(UUID operationId, String action, String requestHash,
                          Object payload, RuntimeException failure) {
        jdbc.update("""
                INSERT INTO auth.cloud_settlement_outbox
                (id, operation_id, action, request_hash, payload, status, next_attempt_at,
                 last_error, terminal_at)
                VALUES (?,?,?,?,CAST(? AS jsonb),'DEAD',now(),?,now())
                ON CONFLICT (operation_id, action, request_hash)
                DO UPDATE SET status='DEAD', last_error=EXCLUDED.last_error,
                    terminal_at=now()
                """, UUID.randomUUID(), operationId, action, requestHash, write(payload),
                bounded(failure.getMessage()));
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state='SETTLEMENT_FAILED', updated_at=now()
                WHERE operation_id=? AND state IN ('RESERVED','EXPIRED')
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

    private static int nonNegative(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_USAGE");
        }
        return value;
    }

    private static String bounded(String message) {
        String value = message == null ? "transport failure" : message;
        return value.substring(0, Math.min(2000, value.length()));
    }

    private record ReserveHash(UUID operationId, Context context, long entitlementSequence,
                               String sourceType, BigDecimal estimatedCredits,
                               BigDecimal maximumCredits, String provider, String model,
                               Integer estimatedPromptTokens,
                               Integer maximumCompletionTokens) {}

    public record LlmReserveCommand(UUID operationId, String feature, String sourceType,
                                    String provider, String model, int estimatedPromptTokens,
                                    int maximumCompletionTokens) {}
    public record LlmCommitCommand(String provider, String model, String providerRequestId,
                                   int promptTokens, int completionTokens, String requestHash,
                                   Integer cacheCreationTokens, Integer cacheReadTokens,
                                   Integer cachedTokens, Integer reasoningTokens) {
        public LlmCommitCommand(String provider, String model, String providerRequestId,
                                int promptTokens, int completionTokens, String requestHash) {
            this(provider, model, providerRequestId, promptTokens, completionTokens, requestHash,
                    null, null, null, null);
        }
    }
    public record Context(UUID principalId, UUID billingSubjectId,
                          UUID organizationId, UUID installId) {}
    public record ReserveCommand(UUID operationId, String feature, String sourceType,
                                 BigDecimal estimatedCredits, BigDecimal maximumCredits,
                                 String provider, String model, Integer estimatedPromptTokens,
                                 Integer maximumCompletionTokens) {
        public ReserveCommand(UUID operationId, String feature, String sourceType,
                              BigDecimal estimatedCredits, BigDecimal maximumCredits,
                              String provider, String model) {
            this(operationId, feature, sourceType, estimatedCredits, maximumCredits,
                    provider, model, null, null);
        }
    }
    public record CommitCommand(BigDecimal actualCredits, String provider, String model,
                                String providerRequestId, Long promptTokens,
                                Long completionTokens, String requestHash,
                                Integer cacheCreationTokens, Integer cacheReadTokens,
                                Integer cachedTokens, Integer reasoningTokens) {
        public CommitCommand(BigDecimal actualCredits, String provider, String model,
                             String providerRequestId, Long promptTokens,
                             Long completionTokens, String requestHash) {
            this(actualCredits, provider, model, providerRequestId, promptTokens,
                    completionTokens, requestHash, null, null, null, null);
        }
    }
    public record ReleaseCommand(String reason, String requestHash) {}
    public record OutcomeUnknownCommand(String reason, String requestHash,
                                        String provider, String model) {}
    public record ReserveResult(CloudCreditAuthorityService.ReserveResponse authority,
                                String requestHash, long entitlementSequence) {}
    public record SettlementResult(CloudCreditAuthorityService.SettlementResponse authority,
                                   boolean queued) {}
}
