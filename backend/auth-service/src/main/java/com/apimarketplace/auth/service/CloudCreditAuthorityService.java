package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.CanonicalJson;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Idempotent external wallet protocol layered on the existing authoritative CreditService ledger.
 * No balance is copied to Cloud.
 */
@Service
public class CloudCreditAuthorityService {

    private static final Duration RESERVATION_TTL = Duration.ofMinutes(10);
    private static final Duration LATE_SETTLEMENT = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final CreditService credits;
    private final ObjectMapper json;

    public CloudCreditAuthorityService(JdbcTemplate jdbc, CreditService credits, ObjectMapper json) {
        this.jdbc = jdbc;
        this.credits = credits;
        this.json = json;
    }

    @Transactional
    public ReserveResponse reserve(ReserveRequest request) {
        validateReserve(request);
        lock(request.operationId());
        Existing existing = existing(request.operationId());
        if (existing != null) {
            requireSame(existing.requestHash(), request.requestHash(), "OPERATION_ID_CONFLICT");
            if (!"RESERVED".equals(existing.state())) {
                throw conflict("OPERATION_ALREADY_" + existing.state());
            }
            return reserveResponse(existing);
        }

        long executorUserId = validateAuthorityContext(request);
        BigDecimal authoritativeMaximum = authoritativeMaximum(request);
        var result = reserveForOrganization(executorUserId, request, authoritativeMaximum);
        if (!result.success()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    result.delinquent() ? "WALLET_DELINQUENT" : "INSUFFICIENT_CREDITS");
        }

        Instant now = Instant.now();
        ReserveResponse response = new ReserveResponse(request.operationId(), request.operationId(),
                "RESERVED", now.plus(RESERVATION_TTL), result.remainingCredits(), false);
        jdbc.update("""
                INSERT INTO auth.cloud_credit_operation
                (operation_id, reservation_id, request_hash, principal_id, billing_subject_id,
                 organization_id, install_id, entitlement_sequence, source_type,
                 estimated_credits, maximum_credits, provider, model, state,
                 response_payload, expires_at, late_settlement_until)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',CAST(? AS jsonb),?,?)
                """, request.operationId(), request.operationId(), request.requestHash(),
                request.principalId(), request.billingSubjectId(), request.organizationId(),
                request.installId(), request.entitlementSequence(), request.sourceType(),
                request.estimatedCredits().min(authoritativeMaximum), authoritativeMaximum,
                request.provider(), request.model(),
                write(response), Timestamp.from(response.expiresAt()),
                Timestamp.from(response.expiresAt().plus(LATE_SETTLEMENT)));
        return response;
    }

    @Transactional
    public SettlementResponse commit(UUID operationId, CommitRequest request) {
        lock(operationId);
        Existing operation = required(operationId);
        requireSame(operation.requestHash(), request.requestHash(), "REQUEST_HASH_MISMATCH");
        validateCommit(operation, request);
        String settlementHash = CanonicalJson.sha256(json.valueToTree(request));

        if (operation.state().startsWith("COMMITTED")) {
            requireSame(operation.settlementHash(), settlementHash, "COMMIT_PAYLOAD_CONFLICT");
            return readSettlement(operation.responsePayload());
        }
        if ("RELEASED".equals(operation.state())) {
            throw conflict("COMMIT_AFTER_RELEASE");
        }
        boolean late = "EXPIRED".equals(operation.state())
                || "OUTCOME_UNKNOWN_EXPIRED".equals(operation.state());
        if (late && (operation.lateSettlementUntil() == null
                || !Instant.now().isBefore(operation.lateSettlementUntil()))) {
            throw conflict("LATE_SETTLEMENT_WINDOW_CLOSED");
        }

        BigDecimal authoritativeActual = authoritativeActual(operation, request);
        CreditService.CommitOutcome outcome = credits.settleExternalReservation(
                sourceId(operationId), authoritativeActual, request.provider(),
                request.model(), late);
        if (outcome == CreditService.CommitOutcome.RESERVATION_EXPIRED) {
            throw conflict("RESERVATION_NOT_SETTLEABLE");
        }
        boolean delinquent = outcome == CreditService.CommitOutcome.COMMITTED_PARTIAL
                || outcome == CreditService.CommitOutcome.COMMITTED_FLOORED;
        String state = delinquent ? "COMMITTED_DELINQUENT" : "COMMITTED";
        BigDecimal balance = balanceForOrganization(operation.executorUserId(), operation.organizationId());
        SettlementResponse response = new SettlementResponse(operationId, state,
                authoritativeActual, balance, delinquent, outcome.name());
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state=?, actual_credits=?, provider=?, model=?, provider_request_id=?,
                    prompt_tokens=?, completion_tokens=?, cache_creation_tokens=?,
                    cache_read_tokens=?, cached_tokens=?, reasoning_tokens=?,
                    settlement_hash=?, response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE operation_id=?
                """, state, authoritativeActual, request.provider(), request.model(),
                request.providerRequestId(), request.promptTokens(), request.completionTokens(),
                request.cacheCreationTokens(), request.cacheReadTokens(),
                request.cachedTokens(), request.reasoningTokens(),
                settlementHash, write(response), operationId);
        return response;
    }

    @Transactional
    public SettlementResponse release(UUID operationId, ReleaseRequest request) {
        lock(operationId);
        Existing operation = required(operationId);
        requireSame(operation.requestHash(), request.requestHash(), "REQUEST_HASH_MISMATCH");
        String settlementHash = CanonicalJson.sha256(json.valueToTree(request));

        if ("RELEASED".equals(operation.state())) {
            requireSame(operation.settlementHash(), settlementHash, "RELEASE_PAYLOAD_CONFLICT");
            return readSettlement(operation.responsePayload());
        }
        if (operation.state().startsWith("COMMITTED")) throw conflict("RELEASE_AFTER_COMMIT");
        CreditService.ReleaseOutcome outcome = credits.releaseReservation(
                sourceId(operationId), "cloud-release:" + safeReason(request.reason()));
        SettlementResponse response = new SettlementResponse(operationId, "RELEASED",
                BigDecimal.ZERO, balanceForOrganization(operation.executorUserId(),
                        operation.organizationId()), false, outcome.name());
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state='RELEASED', settlement_hash=?, response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE operation_id=?
                """, settlementHash, write(response), operationId);
        return response;
    }

    @Transactional
    public SettlementResponse outcomeUnknown(UUID operationId, OutcomeUnknownRequest request) {
        lock(operationId);
        Existing operation = required(operationId);
        requireSame(operation.requestHash(), request.requestHash(), "REQUEST_HASH_MISMATCH");
        if (!java.util.Objects.equals(operation.provider(), request.provider())
                || !java.util.Objects.equals(operation.model(), request.model())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_OUTCOME");
        }
        String settlementHash = CanonicalJson.sha256(json.valueToTree(request));
        if (operation.state().startsWith("OUTCOME_UNKNOWN")) {
            requireSame(operation.settlementHash(), settlementHash,
                    "OUTCOME_UNKNOWN_PAYLOAD_CONFLICT");
            return readSettlement(operation.responsePayload());
        }
        if (operation.state().startsWith("COMMITTED")) {
            throw conflict("OUTCOME_UNKNOWN_AFTER_COMMIT");
        }
        if ("RELEASED".equals(operation.state())) {
            throw conflict("OUTCOME_UNKNOWN_AFTER_RELEASE");
        }

        boolean holdAlreadyExpired = "EXPIRED".equals(operation.state());
        String state = holdAlreadyExpired
                ? "OUTCOME_UNKNOWN_EXPIRED" : "OUTCOME_UNKNOWN";
        SettlementResponse response = new SettlementResponse(operationId, state,
                BigDecimal.ZERO,
                balanceForOrganization(operation.executorUserId(), operation.organizationId()),
                holdAlreadyExpired,
                holdAlreadyExpired
                        ? "RECONCILIATION_REQUIRED_HOLD_EXPIRED"
                        : "RECONCILIATION_REQUIRED_HOLD_RETAINED");
        jdbc.update("""
                UPDATE auth.cloud_credit_operation
                SET state=?, settlement_hash=?, response_payload=CAST(? AS jsonb), updated_at=now()
                WHERE operation_id=?
                """, state, settlementHash, write(response), operationId);
        return response;
    }

    @Transactional
    public int expireDueReservations() {
        var ids = jdbc.query("""
                SELECT operation_id FROM auth.cloud_credit_operation
                WHERE state='RESERVED' AND expires_at <= now() FOR UPDATE SKIP LOCKED
                LIMIT 100
                """, (rs, row) -> rs.getObject(1, UUID.class));
        for (UUID id : ids) {
            credits.releaseReservation(sourceId(id), "auto-release-timeout:cloud");
            jdbc.update("UPDATE auth.cloud_credit_operation SET state='EXPIRED', updated_at=now() WHERE operation_id=?",
                    id);
        }
        return ids.size();
    }

    private long validateAuthorityContext(ReserveRequest request) {
        var users = jdbc.query("""
                SELECT actor.id
                FROM auth.users actor
                JOIN auth.organization_member member ON member.user_id=actor.id
                WHERE actor.principal_id=? AND member.organization_id=?
                """, (rs, row) -> rs.getLong(1), request.principalId(), request.organizationId());
        if (users.size() != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACTOR_BINDING_INVALID");
        long userId = users.getFirst();

        Integer payer = jdbc.queryForObject("""
                SELECT count(*) FROM auth.organization organization_row
                JOIN auth.users owner_row ON owner_row.id=organization_row.owner_id
                WHERE organization_row.id=? AND owner_row.billing_subject_id=?
                """, Integer.class, request.organizationId(), request.billingSubjectId());
        if (payer == null || payer != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PAYER_BINDING_INVALID");
        }

        Integer linked = jdbc.queryForObject("""
                SELECT count(*)
                FROM publication.ce_cloud_links link
                JOIN auth.organization organization_row
                  ON organization_row.id=? AND link.organization_id=organization_row.id::text
                JOIN auth.users owner_row ON owner_row.id=organization_row.owner_id
                WHERE link.install_id=?
                  AND link.tenant_id=owner_row.id
                  AND owner_row.billing_subject_id=?
                """, Integer.class, request.organizationId(), request.installId(),
                request.billingSubjectId());
        if (linked == null || linked != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INSTALL_BINDING_INVALID");
        }

        Integer entitled = jdbc.queryForObject("""
                SELECT count(*) FROM auth.entitlement_authority_state
                WHERE issuer='https://app.trinyx.fr' AND install_id=? AND organization_id=?
                  AND billing_subject_id=? AND sequence=?
                  AND access_state IN ('ACTIVE','GRACE') AND expires_at > now()
                """, Integer.class, request.installId(), request.organizationId(),
                request.billingSubjectId(), request.entitlementSequence());
        if (entitled == null || entitled != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ENTITLEMENT_SEQUENCE_INVALID");
        }
        return userId;
    }

    private void validateReserve(ReserveRequest request) {
        if (request == null || request.operationId() == null || request.principalId() == null
                || request.billingSubjectId() == null || request.organizationId() == null
                || request.installId() == null || request.entitlementSequence() <= 0
                || request.requestHash() == null || !request.requestHash().matches("[0-9a-f]{64}")
                || request.maximumCredits() == null || request.maximumCredits().signum() <= 0
                || request.estimatedCredits() == null || request.estimatedCredits().signum() < 0
                || request.estimatedCredits().compareTo(request.maximumCredits()) > 0
                || (isLlmSource(request.sourceType())
                    && (request.provider() == null || request.provider().isBlank()
                        || request.model() == null || request.model().isBlank()
                        || request.estimatedPromptTokens() == null
                        || request.estimatedPromptTokens() < 0
                        || request.maximumCompletionTokens() == null
                        || request.maximumCompletionTokens() < 0))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION");
        }
    }

    private void lock(UUID operationId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> { }, operationId.toString());
    }

    private Existing required(UUID id) {
        Existing row = existing(id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND");
        return row;
    }

    private Existing existing(UUID id) {
        var rows = jdbc.query("""
                SELECT o.operation_id, o.request_hash, o.settlement_hash, o.state,
                       o.response_payload::text, o.late_settlement_until, o.organization_id,
                       o.source_type, o.provider, o.model, u.id AS executor_user_id
                FROM auth.cloud_credit_operation o
                JOIN auth.users u ON u.principal_id=o.principal_id
                WHERE o.operation_id=? FOR UPDATE
                """, (rs, row) -> new Existing(rs.getObject("operation_id", UUID.class),
                rs.getString("request_hash"), rs.getString("settlement_hash"), rs.getString("state"),
                rs.getString("response_payload"),
                rs.getTimestamp("late_settlement_until") == null ? null
                        : rs.getTimestamp("late_settlement_until").toInstant(),
                rs.getLong("executor_user_id"), rs.getObject("organization_id", UUID.class),
                rs.getString("source_type"), rs.getString("provider"), rs.getString("model")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private CreditService.CreditConsumeResult reserveForOrganization(
            long executorUserId, ReserveRequest request, BigDecimal maximumCredits) {
        AtomicReference<CreditService.CreditConsumeResult> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(request.organizationId().toString(), () -> result.set(
                credits.tryReserveMarkup(executorUserId, sourceId(request.operationId()),
                        request.provider(), request.model(), maximumCredits, null,
                        10, "CLOUD", request.operationId().toString(), false)));
        return result.get();
    }

    private BigDecimal authoritativeMaximum(ReserveRequest request) {
        if ("WEB_SEARCH".equalsIgnoreCase(request.sourceType())) {
            return credits.getWebSearchCreditsPerSearch();
        }
        if (isLlmSource(request.sourceType())) {
            try {
                BigDecimal pricedCeiling = credits.calculateExternalLlmCredits(
                        request.provider(), request.model(),
                        request.estimatedPromptTokens(), request.maximumCompletionTokens(),
                        null, null, null, null);
                BigDecimal hold = pricedCeiling.multiply(new BigDecimal("1.25"))
                        .setScale(6, RoundingMode.UP);
                return hold.signum() > 0 ? hold : new BigDecimal("0.000001");
            } catch (IllegalArgumentException invalidBudget) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "INVALID_PROVIDER_BUDGET", invalidBudget);
            }
        }
        return request.maximumCredits();
    }

    private BigDecimal authoritativeActual(Existing operation, CommitRequest request) {
        if ("WEB_SEARCH".equalsIgnoreCase(operation.sourceType())) {
            return credits.getWebSearchCreditsPerSearch();
        }
        if (isLlmSource(operation.sourceType())) {
            try {
                return credits.calculateExternalLlmCredits(
                        operation.provider(), operation.model(),
                        toInt(request.promptTokens(), "promptTokens"),
                        toInt(request.completionTokens(), "completionTokens"),
                        request.cacheCreationTokens(), request.cacheReadTokens(),
                        request.cachedTokens(), request.reasoningTokens());
            } catch (IllegalArgumentException invalidUsage) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "INVALID_PROVIDER_USAGE", invalidUsage);
            }
        }
        return request.actualCredits();
    }

    private void validateCommit(Existing operation, CommitRequest request) {
        if (request == null || request.requestHash() == null
                || !request.requestHash().matches("[0-9a-f]{64}")
                || !java.util.Objects.equals(operation.provider(), request.provider())
                || !java.util.Objects.equals(operation.model(), request.model())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_COMMIT");
        }
        if (!isLlmSource(operation.sourceType())
                && !"WEB_SEARCH".equalsIgnoreCase(operation.sourceType())
                && (request.actualCredits() == null || request.actualCredits().signum() < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ACTUAL_CREDITS");
        }
        nonNegative(request.cacheCreationTokens(), "cacheCreationTokens");
        nonNegative(request.cacheReadTokens(), "cacheReadTokens");
        nonNegative(request.cachedTokens(), "cachedTokens");
        nonNegative(request.reasoningTokens(), "reasoningTokens");
    }

    private static boolean isLlmSource(String sourceType) {
        // Both relay paths carry provider/model plus raw token counters. Amounts
        // supplied by Cloud are placeholders only: the paid authority owns pricing.
        return "CE_LLM_RELAY".equalsIgnoreCase(sourceType)
                || "BROWSER_AGENT_EXECUTION".equalsIgnoreCase(sourceType);
    }

    private static Integer toInt(Long value, String field) {
        if (value == null || value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.intValue();
    }

    private static void nonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is invalid");
        }
    }

    private BigDecimal balanceForOrganization(long executorUserId, UUID organizationId) {
        AtomicReference<BigDecimal> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(organizationId.toString(),
                () -> result.set(credits.getBalance(executorUserId)));
        return result.get();
    }

    private ReserveResponse reserveResponse(Existing existing) {
        try { return json.readValue(existing.responsePayload(), ReserveResponse.class); }
        catch (Exception e) { throw new IllegalStateException("Stored reservation response is invalid", e); }
    }

    private SettlementResponse readSettlement(String value) {
        try { return json.readValue(value, SettlementResponse.class); }
        catch (Exception e) { throw new IllegalStateException("Stored settlement response is invalid", e); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize wallet response", e); }
    }

    private static void requireSame(String existing, String incoming, String code) {
        if (existing == null || !existing.equals(incoming)) throw conflict(code);
    }
    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
    private static String sourceId(UUID id) { return "cloud-reservation:" + id; }
    private static String safeReason(String reason) {
        if (reason == null) return "client";
        return reason.replaceAll("[^A-Za-z0-9._:-]", "_").substring(0, Math.min(reason.length(), 64));
    }

    private record Existing(UUID operationId, String requestHash, String settlementHash,
                            String state, String responsePayload, Instant lateSettlementUntil,
                            long executorUserId, UUID organizationId, String sourceType,
                            String provider, String model) {}

    public record ReserveRequest(UUID operationId, UUID principalId, UUID billingSubjectId,
                                 UUID organizationId, UUID installId, long entitlementSequence,
                                 String sourceType, BigDecimal estimatedCredits,
                                 BigDecimal maximumCredits, String provider, String model,
                                 String requestHash, Integer estimatedPromptTokens,
                                 Integer maximumCompletionTokens) {
        public ReserveRequest(UUID operationId, UUID principalId, UUID billingSubjectId,
                              UUID organizationId, UUID installId, long entitlementSequence,
                              String sourceType, BigDecimal estimatedCredits,
                              BigDecimal maximumCredits, String provider, String model,
                              String requestHash) {
            this(operationId, principalId, billingSubjectId, organizationId, installId,
                    entitlementSequence, sourceType, estimatedCredits, maximumCredits,
                    provider, model, requestHash, null, null);
        }
    }
    public record CommitRequest(BigDecimal actualCredits, String provider, String model,
                                String providerRequestId, Long promptTokens, Long completionTokens,
                                String requestHash, Integer cacheCreationTokens,
                                Integer cacheReadTokens, Integer cachedTokens,
                                Integer reasoningTokens) {
        public CommitRequest(BigDecimal actualCredits, String provider, String model,
                             String providerRequestId, Long promptTokens, Long completionTokens,
                             String requestHash) {
            this(actualCredits, provider, model, providerRequestId, promptTokens,
                    completionTokens, requestHash, null, null, null, null);
        }
    }
    public record ReleaseRequest(String reason, String requestHash) {}
    public record OutcomeUnknownRequest(String reason, String requestHash,
                                        String provider, String model) {}
    public record ReserveResponse(UUID operationId, UUID reservationId, String state,
                                  Instant expiresAt, BigDecimal authoritativeBalance,
                                  boolean delinquent) {}
    public record SettlementResponse(UUID operationId, String state, BigDecimal actualCredits,
                                     BigDecimal authoritativeBalance, boolean delinquent,
                                     String outcome) {}
}
