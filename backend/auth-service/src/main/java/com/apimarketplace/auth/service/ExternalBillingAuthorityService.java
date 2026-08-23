package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.security.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Paid-monolith issuer for identity bindings and non-spendable Cloud entitlement projections. */
@Service
public class ExternalBillingAuthorityService {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final PlanResolutionService planResolution;
    private final TrinyxAssertionService assertions;
    private final ObjectMapper json;
    private final Duration pastDueGrace;

    public ExternalBillingAuthorityService(
            JdbcTemplate jdbc, UserRepository users, SubscriptionRepository subscriptions,
            PlanRepository plans, PlanResolutionService planResolution,
            TrinyxAssertionService assertions, ObjectMapper json,
            @Value("${billing.external.past-due-grace-hours:72}") long pastDueGraceHours) {
        this.jdbc = jdbc;
        this.users = users;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.planResolution = planResolution;
        this.assertions = assertions;
        this.json = json;
        this.pastDueGrace = Duration.ofHours(Math.max(0, pastDueGraceHours));
    }

    @Transactional
    public AuthorityBundle issue(long actorUserId, UUID installId, UUID organizationId,
                                 String keycloakSubject) {
        AuthorityScope scope = validateScope(actorUserId, installId, organizationId);
        String identityBinding = issueIdentity(scope.actor(), scope.payer(), installId,
                organizationId, keycloakSubject);
        Projection projection = issueProjection(scope.actor(), scope.payer(), installId,
                organizationId, "UPSERT");
        return new AuthorityBundle(identityBinding, projection.assertion(), projection.sequence(),
                projection.expiresAt());
    }

    @Transactional
    public Projection refresh(long actorUserId, UUID installId, UUID organizationId) {
        AuthorityScope scope = validateScope(actorUserId, installId, organizationId);
        return issueProjection(scope.actor(), scope.payer(), installId, organizationId, "REFRESH");
    }

    private String issueIdentity(User actor, User payer, UUID installId, UUID organizationId,
                                 String keycloakSubject) {
        if (keycloakSubject == null || keycloakSubject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "KEYCLOAK_SUBJECT_REQUIRED");
        }
        String scope = installId + "|" + organizationId + "|" + actor.getPrincipalId();
        lock(scope);
        var current = jdbc.query("""
                SELECT binding_revision, keycloak_subject FROM auth.identity_binding_authority_state
                WHERE install_id=? AND organization_id=? AND principal_id=? FOR UPDATE
                """, (rs, row) -> new Object[]{rs.getLong(1), rs.getString(2)},
                installId, organizationId, actor.getPrincipalId());
        if (!current.isEmpty() && !keycloakSubject.equals(current.getFirst()[1])) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EXPLICIT_REBIND_REQUIRED");
        }
        long revision = current.isEmpty() ? 1L : ((Long) current.getFirst()[0]) + 1L;
        Instant now = Instant.now();
        Instant expires = now.plus(Duration.ofMinutes(5));
        UUID jti = UUID.randomUUID();
        ObjectNode claims = json.createObjectNode();
        claims.put("schemaVersion", 2);
        claims.put("iss", "https://app.trinyx.fr");
        claims.put("aud", "trinyx-cloud");
        claims.put("jti", jti.toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.minusSeconds(5).getEpochSecond());
        claims.put("exp", expires.getEpochSecond());
        claims.put("bindingRevision", revision);
        claims.put("principalId", actor.getPrincipalId().toString());
        claims.put("billingSubjectId", payer.getBillingSubjectId().toString());
        claims.put("keycloakSubject", keycloakSubject);
        claims.put("organizationId", organizationId.toString());
        claims.put("installId", installId.toString());
        String assertion = assertions.signIdentity(claims);
        if (current.isEmpty()) {
            jdbc.update("""
                    INSERT INTO auth.identity_binding_authority_state
                    (id, install_id, organization_id, principal_id, billing_subject_id,
                     keycloak_subject, binding_revision, assertion_jti, assertion_jws,
                     status, expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?)
                    """, UUID.randomUUID(), installId, organizationId, actor.getPrincipalId(),
                    payer.getBillingSubjectId(), keycloakSubject, revision, jti, assertion,
                    Timestamp.from(expires));
        } else {
            jdbc.update("""
                    UPDATE auth.identity_binding_authority_state
                    SET billing_subject_id=?, binding_revision=?, assertion_jti=?,
                        assertion_jws=?, status='ACTIVE', expires_at=?, updated_at=now()
                    WHERE install_id=? AND organization_id=? AND principal_id=?
                    """, payer.getBillingSubjectId(), revision, jti, assertion,
                    Timestamp.from(expires), installId, organizationId, actor.getPrincipalId());
        }
        return assertion;
    }

    private Projection issueProjection(User actor, User payer, UUID installId, UUID organizationId,
                                       String eventType) {
        String scope = "https://app.trinyx.fr|" + installId + "|" + organizationId
                + "|" + payer.getBillingSubjectId();
        lock(scope);
        var current = jdbc.query("""
                SELECT projection_id, sequence FROM auth.entitlement_authority_state
                WHERE issuer='https://app.trinyx.fr' AND install_id=? AND organization_id=?
                  AND billing_subject_id=? FOR UPDATE
                """, (rs, row) -> new Object[]{rs.getObject(1, UUID.class), rs.getLong(2)},
                installId, organizationId, payer.getBillingSubjectId());
        UUID projectionId = current.isEmpty() ? UUID.randomUUID() : (UUID) current.getFirst()[0];
        long sequence = current.isEmpty() ? 1L : ((Long) current.getFirst()[1]) + 1L;

        long payerUserId = jdbc.queryForObject(
                "SELECT id FROM auth.users WHERE billing_subject_id=? ORDER BY id LIMIT 1",
                Long.class, payer.getBillingSubjectId());
        Subscription subscription = subscriptions.findByBillingCustomer_User_Id(payerUserId).orElse(null);
        PlanResolutionService.ActiveOrgEntitlement governing =
                planResolution.resolveActiveOrgEntitlement(actor.getId());
        String planCode = governing.planCode() == null ? "FREE" : governing.planCode();
        Plan plan = plans.findByCode(planCode).orElse(null);
        String accessState = accessState(subscription);

        Instant now = Instant.now();
        Instant expires = now.plus(Duration.ofMinutes(15));
        UUID eventId = UUID.randomUUID();
        ObjectNode claims = json.createObjectNode();
        claims.put("schemaVersion", 2);
        claims.put("iss", "https://app.trinyx.fr");
        claims.put("aud", "trinyx-cloud");
        claims.put("jti", eventId.toString());
        claims.put("projectionId", projectionId.toString());
        claims.put("eventId", eventId.toString());
        claims.put("sequence", sequence);
        claims.put("installId", installId.toString());
        claims.put("organizationId", organizationId.toString());
        claims.put("billingSubjectId", payer.getBillingSubjectId().toString());
        claims.put("accessState", accessState);
        claims.put("planCode", planCode);
        claims.put("creditTierIndex", governing.creditTierIndex());
        claims.put("cadence", governing.cadence() == null ? "" : governing.cadence());
        writeSubscription(claims, subscription);
        writeCapabilities(claims, plan, accessState);
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.minusSeconds(5).getEpochSecond());
        claims.put("exp", expires.getEpochSecond());

        String assertion = assertions.signEntitlement(claims);
        String payload = claims.toString();
        if (current.isEmpty()) {
            jdbc.update("""
                    INSERT INTO auth.entitlement_authority_state
                    (projection_id, issuer, install_id, organization_id, billing_subject_id,
                     sequence, access_state, canonical_payload, signed_jws, expires_at)
                    VALUES (?,'https://app.trinyx.fr',?,?,?,?,?,CAST(? AS jsonb),?,?)
                    """, projectionId, installId, organizationId, payer.getBillingSubjectId(),
                    sequence, accessState, payload, assertion, Timestamp.from(expires));
        } else {
            jdbc.update("""
                    UPDATE auth.entitlement_authority_state
                    SET sequence=?, access_state=?, canonical_payload=CAST(? AS jsonb),
                        signed_jws=?, expires_at=?, updated_at=now()
                    WHERE projection_id=?
                    """, sequence, accessState, payload, assertion, Timestamp.from(expires), projectionId);
        }
        jdbc.update("""
                INSERT INTO auth.entitlement_outbox
                (event_id, aggregate_key, sequence, event_type, signed_jws)
                VALUES (?,?,?,?,?)
                """, eventId, scope, sequence, eventType, assertion);
        return new Projection(assertion, sequence, expires, accessState,
                CanonicalJson.sha256(claims));
    }

    private String accessState(Subscription subscription) {
        if (subscription == null) return "DENIED";
        if (Boolean.TRUE.equals(subscription.getDelinquent())) return "DENIED";
        if ("active".equals(subscription.getStatus()) || "trialing".equals(subscription.getStatus())) {
            return "ACTIVE";
        }
        if ("past_due".equals(subscription.getStatus())) {
            Instant graceEnd = subscription.getUpdatedAt().toInstant(ZoneOffset.UTC).plus(pastDueGrace);
            return Instant.now().isBefore(graceEnd) ? "GRACE" : "DENIED";
        }
        return "DENIED";
    }

    private void writeSubscription(ObjectNode claims, Subscription sub) {
        claims.put("subscriptionStatus", sub == null ? "none" : sub.getStatus());
        claims.put("cancelAtPeriodEnd", sub != null && Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()));
        if (sub != null) {
            claims.put("currentPeriodStart", sub.getCurrentPeriodStart().toInstant(ZoneOffset.UTC).toString());
            claims.put("currentPeriodEnd", sub.getCurrentPeriodEnd().toInstant(ZoneOffset.UTC).toString());
            claims.put("delinquent", Boolean.TRUE.equals(sub.getDelinquent()));
        } else {
            claims.putNull("currentPeriodStart");
            claims.putNull("currentPeriodEnd");
            claims.put("delinquent", false);
        }
    }

    private void writeCapabilities(ObjectNode claims, Plan plan, String accessState) {
        boolean paid = plan != null && !"FREE".equals(plan.getCode())
                && ("ACTIVE".equals(accessState) || "GRACE".equals(accessState));
        ObjectNode features = claims.putObject("features");
        features.put("cloudLlmRelay", paid);
        features.put("cloudWebSearchRelay", paid);
        features.put("catalogBundle", paid);
        features.put("skillBundle", paid);
        ObjectNode limits = claims.putObject("limits");
        if (plan != null) {
            putLimit(limits, "maxAgents", plan.getMaxAgents());
            putLimit(limits, "maxDatasources", plan.getMaxDatasources());
            putLimit(limits, "maxInterfaces", plan.getMaxInterfaces());
            putLimit(limits, "maxMembers", plan.getMaxMembers());
            putLimit(limits, "maxStorageBytes", plan.getIncludedStorageBytes());
        }
    }

    private AuthorityScope validateScope(long userId, UUID installId, UUID organizationId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        Integer membership = jdbc.queryForObject(
                "SELECT count(*) FROM auth.organization_member WHERE user_id=? AND organization_id=?",
                Integer.class, userId, organizationId);
        Integer installation = jdbc.queryForObject(
                "SELECT count(*) FROM publication.ce_cloud_links WHERE tenant_id=? AND install_id=?",
                Integer.class, userId, installId);
        if (membership == null || membership != 1 || installation == null || installation != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AUTHORITY_SCOPE_INVALID");
        }
        Long ownerId = jdbc.queryForObject(
                "SELECT owner_id FROM auth.organization WHERE id=?", Long.class, organizationId);
        User payer = users.findById(ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYER_NOT_FOUND"));
        return new AuthorityScope(user, payer);
    }

    private void lock(String scope) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> { }, scope);
    }

    private static void putLimit(ObjectNode limits, String name, Number value) {
        if (value != null) limits.put(name, value.longValue());
    }

    private record AuthorityScope(User actor, User payer) {}

    public record AuthorityBundle(String identityBinding, String entitlementProjection,
                                  long entitlementSequence, Instant entitlementExpiresAt) {}
    public record Projection(String assertion, long sequence, Instant expiresAt,
                             String accessState, String stateHash) {}
}
