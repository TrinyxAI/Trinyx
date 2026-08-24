package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Cloud-side signed, non-spendable entitlement projection store. */
@Service
public class EntitlementProjectionService {

    private static final Set<String> STATES = Set.of("ACTIVE", "GRACE", "DENIED", "REVOKED");
    private static final Set<String> BOOLEAN_FEATURES = Set.of(
            "cloudLlmRelay", "cloudWebSearchRelay", "catalogBundle", "skillBundle");
    private static final Set<String> INTEGER_LIMITS = Set.of(
            "maxAgents", "maxDatasources", "maxInterfaces", "maxMembers",
            "maxStorageBytes", "maxConcurrentOperations");

    private final JdbcTemplate jdbc;
    private final TrinyxAssertionService assertions;
    private final String issuer;
    private final String audience;

    public EntitlementProjectionService(
            JdbcTemplate jdbc, TrinyxAssertionService assertions,
            @Value("${trinyx.assertions.entitlement.issuer:https://app.trinyx.fr}") String issuer,
            @Value("${trinyx.assertions.entitlement.audience:trinyx-cloud}") String audience) {
        this.jdbc = jdbc;
        this.assertions = assertions;
        this.issuer = issuer;
        this.audience = audience;
    }

    @Transactional
    public ApplyResult apply(String compactJws) {
        JsonNode claims = assertions.verifyEntitlement(compactJws, issuer, audience);
        validateSchema(claims);
        UUID projectionId = uuid(claims, "projectionId");
        UUID installId = uuid(claims, "installId");
        UUID organizationId = uuid(claims, "organizationId");
        UUID billingSubjectId = uuid(claims, "billingSubjectId");
        UUID eventId = uuid(claims, "eventId");
        long sequence = claims.path("sequence").asLong();
        String accessState = required(claims, "accessState");
        String hash = CanonicalJson.sha256(claims);
        String scope = issuer + "|" + installId + "|" + organizationId + "|" + billingSubjectId;

        // Serializes the empty-row case across Cloud instances as well as normal updates.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> { }, scope);
        Current current = currentForUpdate(installId, organizationId, billingSubjectId);
        if (current != null) {
            if (!current.projectionId().equals(projectionId)) {
                throw conflict("PROJECTION_ID_CHANGED");
            }
            if (sequence < current.sequence()) throw conflict("STALE_PROJECTION");
            if (sequence == current.sequence()) {
                if (hash.equals(current.hash())) {
                    return new ApplyResult("IDEMPOTENT", sequence, accessState,
                            Instant.ofEpochSecond(claims.path("exp").asLong()));
                }
                throw conflict("EQUIVOCATION_DETECTED");
            }
            jdbc.update("""
                    UPDATE auth.entitlement_projection SET sequence=?, access_state=?,
                      state_hash=?, event_id=?, signed_jws=?, canonical_payload=CAST(? AS jsonb),
                      issued_at=?, not_before=?, expires_at=?, applied_at=now()
                    WHERE projection_id=?
                    """, sequence, accessState, hash, eventId, compactJws, claims.toString(),
                    timestamp(claims, "iat"), timestamp(claims, "nbf"), timestamp(claims, "exp"),
                    projectionId);
        } else {
            jdbc.update("""
                    INSERT INTO auth.entitlement_projection
                    (projection_id, sequence, issuer, audience, install_id, organization_id,
                     billing_subject_id, access_state, state_hash, event_id, signed_jws,
                     canonical_payload, issued_at, not_before, expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?)
                    """, projectionId, sequence, issuer, audience, installId, organizationId,
                    billingSubjectId, accessState, hash, eventId, compactJws, claims.toString(),
                    timestamp(claims, "iat"), timestamp(claims, "nbf"), timestamp(claims, "exp"));
        }
        return new ApplyResult("APPLIED", sequence, accessState,
                Instant.ofEpochSecond(claims.path("exp").asLong()));
    }

    @Transactional(readOnly = true)
    public Decision authorize(UUID installId, UUID organizationId, UUID billingSubjectId,
                              String feature, boolean paidOperation) {
        var rows = jdbc.query("""
                SELECT sequence, access_state, expires_at, canonical_payload
                FROM auth.entitlement_projection
                WHERE issuer=? AND install_id=? AND organization_id=? AND billing_subject_id=?
                """, (rs, row) -> new DecisionRow(rs.getLong("sequence"),
                rs.getString("access_state"), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("canonical_payload")), issuer, installId, organizationId, billingSubjectId);
        if (rows.size() != 1) return Decision.denied("ENTITLEMENT_MISSING");
        DecisionRow row = rows.getFirst();
        if (!Instant.now().isBefore(row.expiresAt())) return Decision.denied("ENTITLEMENT_EXPIRED");
        if (!Set.of("ACTIVE", "GRACE").contains(row.state())) {
            return Decision.denied("ENTITLEMENT_" + row.state());
        }
        try {
            JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.payload());
            if (feature != null && BOOLEAN_FEATURES.contains(feature)
                    && !payload.path("features").path(feature).asBoolean(false)) {
                return Decision.denied("FEATURE_NOT_ENTITLED");
            }
        } catch (Exception e) {
            return Decision.denied("ENTITLEMENT_INVALID");
        }
        return new Decision(true, "AUTHORIZED", row.sequence(), row.expiresAt());
    }

    /**
     * Resolve the actor-free, signed entitlement projection attached to a Cloud-linked
     * installation. This is the external-authority replacement for reading a local Cloud
     * subscription. Expired, denied and revoked projections remain stored for audit but are
     * never returned as effective CE entitlements.
     */
    @Transactional(readOnly = true)
    public Optional<ProjectedCeEntitlement> activeCeEntitlement(long cloudUserId, UUID installId) {
        var rows = jdbc.query("""
                SELECT p.sequence, p.expires_at, p.canonical_payload
                FROM auth.cloud_identity_binding b
                JOIN auth.entitlement_projection p
                  ON p.issuer=b.issuer
                 AND p.install_id=b.install_id
                 AND p.organization_id=b.organization_id
                 AND p.billing_subject_id=b.billing_subject_id
                WHERE b.issuer=? AND b.cloud_user_id=? AND b.install_id=?
                  AND b.status='ACTIVE'
                  AND p.access_state IN ('ACTIVE','GRACE')
                  AND p.expires_at > now()
                """, (rs, row) -> new ProjectionPayloadRow(
                rs.getLong("sequence"), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("canonical_payload")), issuer, cloudUserId, installId);
        if (rows.size() != 1) return Optional.empty();
        try {
            ProjectionPayloadRow row = rows.getFirst();
            JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.payload());
            String planCode = payload.path("planCode").asText();
            if (planCode.isBlank()) return Optional.empty();
            String cadence = payload.path("cadence").asText(null);
            if (cadence != null && cadence.isBlank()) cadence = null;
            return Optional.of(new ProjectedCeEntitlement(
                    planCode, payload.path("creditTierIndex").asInt(0), cadence,
                    row.sequence(), row.expiresAt()));
        } catch (Exception invalidProjection) {
            return Optional.empty();
        }
    }

    private Current currentForUpdate(UUID installId, UUID organizationId, UUID billingSubjectId) {
        var rows = jdbc.query("""
                SELECT projection_id, sequence, state_hash FROM auth.entitlement_projection
                WHERE issuer=? AND install_id=? AND organization_id=? AND billing_subject_id=?
                FOR UPDATE
                """, (rs, row) -> new Current(rs.getObject("projection_id", UUID.class),
                rs.getLong("sequence"), rs.getString("state_hash")),
                issuer, installId, organizationId, billingSubjectId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateSchema(JsonNode claims) {
        long sequence = claims.path("sequence").asLong();
        if (sequence <= 0 || !STATES.contains(required(claims, "accessState"))) {
            throw new IllegalArgumentException("Invalid projection sequence or state");
        }
        if (claims.has("principalId") || claims.has("keycloakSubject")
                || claims.has("remainingCredits") || claims.has("creditBalance")) {
            throw new IllegalArgumentException("Actor identity or spendable balance is forbidden in entitlements");
        }
        JsonNode features = claims.path("features");
        if (!features.isObject()) throw new IllegalArgumentException("features must be an object");
        features.fields().forEachRemaining(entry -> {
            if (BOOLEAN_FEATURES.contains(entry.getKey()) && !entry.getValue().isBoolean()) {
                throw new IllegalArgumentException("Feature " + entry.getKey() + " must be boolean");
            }
        });
        JsonNode limits = claims.path("limits");
        if (!limits.isObject()) throw new IllegalArgumentException("limits must be an object");
        limits.fields().forEachRemaining(entry -> {
            if (INTEGER_LIMITS.contains(entry.getKey())
                    && (!entry.getValue().canConvertToLong() || entry.getValue().asLong() < 0)) {
                throw new IllegalArgumentException("Limit " + entry.getKey() + " must be non-negative integer");
            }
        });
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private static String required(JsonNode claims, String field) {
        String value = claims.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static UUID uuid(JsonNode claims, String field) {
        return UUID.fromString(required(claims, field));
    }
    private static Timestamp timestamp(JsonNode claims, String field) {
        return Timestamp.from(Instant.ofEpochSecond(claims.path(field).asLong()));
    }

    private record Current(UUID projectionId, long sequence, String hash) {}
    private record DecisionRow(long sequence, String state, Instant expiresAt, String payload) {}
    private record ProjectionPayloadRow(long sequence, Instant expiresAt, String payload) {}
    public record ProjectedCeEntitlement(String planCode, int creditTierIndex, String cadence,
                                         long sequence, Instant expiresAt) {}
    public record ApplyResult(String result, long sequence, String accessState, Instant expiresAt) {}
    public record Decision(boolean allowed, String reason, long sequence, Instant expiresAt) {
        static Decision denied(String reason) { return new Decision(false, reason, 0, null); }
    }
}
