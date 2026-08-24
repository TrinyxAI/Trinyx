package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Materializes the short-lived signed paid-monolith binding into revocable Cloud identity state. */
@Service
public class CloudIdentityBindingService {

    private final JdbcTemplate jdbc;
    private final TrinyxAssertionService assertions;
    private final String issuer;
    private final String audience;

    public CloudIdentityBindingService(
            JdbcTemplate jdbc,
            TrinyxAssertionService assertions,
            @Value("${trinyx.assertions.identity.issuer:https://app.trinyx.fr}") String issuer,
            @Value("${trinyx.assertions.identity.audience:trinyx-cloud}") String audience) {
        this.jdbc = jdbc;
        this.assertions = assertions;
        this.issuer = issuer;
        this.audience = audience;
    }

    @Transactional
    public BindingContext bind(String compactJws, String expectedKeycloakSubject, long cloudUserId) {
        JsonNode claims = assertions.verifyIdentity(compactJws, issuer, audience);
        UUID installId = uuid(claims, "installId");
        UUID organizationId = uuid(claims, "organizationId");
        String organizationRole = organizationRole(claims);
        UUID principalId = uuid(claims, "principalId");
        UUID billingSubjectId = uuid(claims, "billingSubjectId");
        UUID jti = uuid(claims, "jti");
        String keycloakSubject = required(claims, "keycloakSubject");
        long revision = claims.path("bindingRevision").asLong();
        String assertionStatus = claims.path("status").asText("ACTIVE");
        if (!"ACTIVE".equals(assertionStatus)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACTIVE_IDENTITY_BINDING_REQUIRED");
        }
        if (revision <= 0 || !keycloakSubject.equals(expectedKeycloakSubject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "IDENTITY_BINDING_MISMATCH");
        }

        BindingRow byJti = findByJti(jti);
        if (byJti != null) {
            if (byJti.assertionJws().equals(compactJws) && byJti.cloudUserId() == cloudUserId) {
                return byJti.context();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDENTITY_JTI_REPLAY");
        }

        BindingRow current = findLatestForUpdate(installId, organizationId, principalId);
        if (current != null) {
            if (revision < current.revision()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_BINDING_REVISION");
            }
            if (revision == current.revision()) {
                if (current.assertionJws().equals(compactJws) && "ACTIVE".equals(current.status())) {
                    return current.context();
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "BINDING_EQUIVOCATION");
            }
            if (!current.keycloakSubject().equals(keycloakSubject) && !"REVOKED".equals(current.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "EXPLICIT_REBIND_REQUIRED");
            }
            if ("ACTIVE".equals(current.status())) {
                jdbc.update("UPDATE auth.cloud_identity_binding SET status='REVOKED', revoked_at=now(), updated_at=now() WHERE id=?",
                        current.id());
            }
        }

        // Cross-system identity is authoritative only after signature + scope validation.
        // Explicit revoke/rebind may move the stable actor identity to a new Keycloak user.
        // Release it from the revoked Cloud-local compatibility row first; active bindings
        // never take this path, so two live subjects cannot share a principal.
        if (current != null && "REVOKED".equals(current.status())
                && current.cloudUserId() != cloudUserId) {
            jdbc.update("""
                    UPDATE auth.users SET principal_id=gen_random_uuid(), updated_at=now()
                    WHERE id=? AND principal_id=?
                    """, current.cloudUserId(), principalId);
        }
        jdbc.update("UPDATE auth.users SET principal_id=?, billing_subject_id=?, updated_at=now() WHERE id=?",
                principalId, billingSubjectId, cloudUserId);
        materializeOrganizationMembership(
                cloudUserId, organizationId, organizationRole, billingSubjectId);
        try {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO auth.cloud_identity_binding
                    (id, issuer, audience, install_id, organization_id, organization_role,
                     principal_id, billing_subject_id, keycloak_subject, cloud_user_id, binding_revision,
                     assertion_jti, assertion_jws, status, issued_at, not_before, expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)
                    """, id, issuer, audience, installId, organizationId, organizationRole, principalId,
                    billingSubjectId, keycloakSubject, cloudUserId, revision, jti, compactJws,
                    timestamp(claims, "iat"), timestamp(claims, "nbf"), timestamp(claims, "exp"));
            return new BindingContext(cloudUserId, keycloakSubject, principalId, billingSubjectId,
                    organizationId, organizationRole, installId, revision, "ACTIVE");
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDENTITY_BINDING_CONFLICT", conflict);
        }
    }

    @Transactional(readOnly = true)
    public BindingContext context(String keycloakSubject) {
        var rows = jdbc.query("""
                SELECT id, cloud_user_id, keycloak_subject, principal_id, billing_subject_id,
                       organization_id, organization_role, install_id, binding_revision, assertion_jws, status
                FROM auth.cloud_identity_binding
                WHERE issuer=? AND keycloak_subject=? AND status='ACTIVE'
                """, (rs, row) -> new BindingRow(
                rs.getObject("id", UUID.class), rs.getLong("cloud_user_id"),
                rs.getString("keycloak_subject"), rs.getObject("principal_id", UUID.class),
                rs.getObject("billing_subject_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("organization_role"),
                rs.getObject("install_id", UUID.class), rs.getLong("binding_revision"),
                rs.getString("assertion_jws"), rs.getString("status")), issuer, keycloakSubject);
        if (rows.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "IDENTITY_NOT_BOUND");
        }
        return rows.getFirst().context();
    }

    /**
     * Authorizes an actor to use an active linked installation through the signed identity scope.
     *
     * <p>The actor does not need to own the historical {@code ce_link} row. Organization members
     * may share the owner's installation and billing subject, but only when both the actor binding
     * and the link owner's binding are ACTIVE and agree on issuer, install, organization and payer.
     * This prevents cross-user, cross-organization and cross-payer scope confusion.
     */
    @Transactional(readOnly = true)
    public boolean userMayUseActiveInstall(long cloudUserId, UUID installId) {
        Integer matches = jdbc.queryForObject("""
                SELECT count(*)
                FROM auth.cloud_identity_binding actor_binding
                JOIN auth.ce_link link
                  ON link.install_id=actor_binding.install_id
                 AND link.status='ACTIVE'
                JOIN auth.cloud_identity_binding owner_binding
                  ON owner_binding.issuer=actor_binding.issuer
                 AND owner_binding.install_id=actor_binding.install_id
                 AND owner_binding.organization_id=actor_binding.organization_id
                 AND owner_binding.billing_subject_id=actor_binding.billing_subject_id
                 AND owner_binding.cloud_user_id=link.user_id
                 AND owner_binding.status='ACTIVE'
                WHERE actor_binding.issuer=?
                  AND actor_binding.cloud_user_id=?
                  AND actor_binding.install_id=?
                  AND actor_binding.status='ACTIVE'
                """, Integer.class, issuer, cloudUserId, installId);
        return matches != null && matches > 0;
    }

    @Transactional
    public BindingContext applyRevocation(String compactJws) {
        JsonNode claims = assertions.verifyIdentity(compactJws, issuer, audience);
        if (!"REVOKED".equals(claims.path("status").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IDENTITY_TOMBSTONE_REQUIRED");
        }
        UUID installId = uuid(claims, "installId");
        UUID organizationId = uuid(claims, "organizationId");
        UUID principalId = uuid(claims, "principalId");
        UUID billingSubjectId = uuid(claims, "billingSubjectId");
        UUID jti = uuid(claims, "jti");
        String keycloakSubject = required(claims, "keycloakSubject");
        long revision = claims.path("bindingRevision").asLong();
        if (revision <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_BINDING_REVISION");

        BindingRow replay = findByJti(jti);
        if (replay != null) {
            if (replay.assertionJws().equals(compactJws) && "REVOKED".equals(replay.status())) {
                return replay.context();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDENTITY_JTI_REPLAY");
        }

        BindingRow current = findLatestForUpdate(installId, organizationId, principalId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "IDENTITY_BINDING_NOT_FOUND");
        }
        if (!current.keycloakSubject().equals(keycloakSubject)
                || !current.billingSubjectId().equals(billingSubjectId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDENTITY_TOMBSTONE_SCOPE_MISMATCH");
        }
        if (revision < current.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_BINDING_REVISION");
        }
        if (revision == current.revision()) {
            if (current.assertionJws().equals(compactJws) && "REVOKED".equals(current.status())) {
                return current.context();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BINDING_EQUIVOCATION");
        }
        jdbc.update("""
                UPDATE auth.cloud_identity_binding
                SET status='REVOKED', binding_revision=?, assertion_jti=?, assertion_jws=?,
                    issued_at=?, not_before=?, expires_at=?, revoked_at=now(), updated_at=now()
                WHERE id=?
                """, revision, jti, compactJws, timestamp(claims, "iat"),
                timestamp(claims, "nbf"), timestamp(claims, "exp"), current.id());
        return new BindingContext(current.cloudUserId(), keycloakSubject, principalId,
                billingSubjectId, organizationId, current.organizationRole(), installId,
                revision, "REVOKED");
    }

    @Transactional
    public void revoke(UUID installId, UUID organizationId, UUID principalId, long revision) {
        BindingRow current = findLatestForUpdate(installId, organizationId, principalId);
        if (current == null || revision <= current.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_OR_MISSING_BINDING");
        }
        jdbc.update("""
                UPDATE auth.cloud_identity_binding
                SET status='REVOKED', binding_revision=?, revoked_at=now(), updated_at=now()
                WHERE id=?
                """, revision, current.id());
    }

    /**
     * Materialize the paid-monolith organization scope in the native Cloud tables so legacy
     * downstream services and their foreign keys see the same trusted workspace as the HMAC
     * context. A member cannot create an ownerless workspace: the signed OWNER binding must be
     * linked first, after which every member is verified against that owner's billing subject.
     */
    private void materializeOrganizationMembership(long cloudUserId, UUID organizationId,
                                                   String organizationRole,
                                                   UUID billingSubjectId) {
        if ("OWNER".equals(organizationRole)) {
            jdbc.update("""
                    INSERT INTO auth.organization
                      (id, name, slug, is_personal, owner_id, created_at, updated_at)
                    VALUES (?, ?, ?, false, ?, now(), now())
                    ON CONFLICT (id) DO NOTHING
                    """, organizationId, "Linked Trinyx workspace",
                    "trinyx-linked-" + organizationId, cloudUserId);
        }
        Integer validOwner = jdbc.queryForObject("""
                SELECT count(*) FROM auth.organization organization_row
                JOIN auth.users owner_row ON owner_row.id=organization_row.owner_id
                WHERE organization_row.id=? AND owner_row.billing_subject_id=?
                  AND organization_row.deleted_at IS NULL
                """, Integer.class, organizationId, billingSubjectId);
        if (validOwner == null || validOwner != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ORGANIZATION_OWNER_BINDING_REQUIRED");
        }
        jdbc.update("""
                INSERT INTO auth.organization_member
                  (organization_id, user_id, role, is_default, joined_at)
                VALUES (?, ?, ?, false, now())
                ON CONFLICT (organization_id, user_id)
                DO UPDATE SET role=EXCLUDED.role
                """, organizationId, cloudUserId,
                organizationRole.toLowerCase(java.util.Locale.ROOT));
    }

    private BindingRow findByJti(UUID jti) {
        var rows = jdbc.query("""
                SELECT id, cloud_user_id, keycloak_subject, principal_id, billing_subject_id,
                       organization_id, organization_role, install_id, binding_revision, assertion_jws, status
                FROM auth.cloud_identity_binding WHERE assertion_jti=?
                """, (rs, row) -> new BindingRow(
                rs.getObject("id", UUID.class), rs.getLong("cloud_user_id"),
                rs.getString("keycloak_subject"), rs.getObject("principal_id", UUID.class),
                rs.getObject("billing_subject_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("organization_role"),
                rs.getObject("install_id", UUID.class), rs.getLong("binding_revision"),
                rs.getString("assertion_jws"), rs.getString("status")), jti);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private BindingRow findLatestForUpdate(UUID installId, UUID organizationId, UUID principalId) {
        String sql = """
                SELECT id, cloud_user_id, keycloak_subject, principal_id, billing_subject_id,
                       organization_id, organization_role, install_id, binding_revision, assertion_jws, status
                FROM auth.cloud_identity_binding
                WHERE issuer=? AND install_id=? AND principal_id=?
                """ + (organizationId == null ? "" : " AND organization_id=?")
                + " ORDER BY binding_revision DESC LIMIT 1 FOR UPDATE";
        Object[] arguments = organizationId == null
                ? new Object[]{issuer, installId, principalId}
                : new Object[]{issuer, installId, principalId, organizationId};
        var rows = jdbc.query(sql, (rs, row) -> new BindingRow(
                rs.getObject("id", UUID.class), rs.getLong("cloud_user_id"),
                rs.getString("keycloak_subject"), rs.getObject("principal_id", UUID.class),
                rs.getObject("billing_subject_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("organization_role"),
                rs.getObject("install_id", UUID.class), rs.getLong("binding_revision"),
                rs.getString("assertion_jws"), rs.getString("status")), arguments);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String organizationRole(JsonNode claims) {
        String role = required(claims, "organizationRole").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("OWNER", "ADMIN", "MEMBER", "VIEWER").contains(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "IDENTITY_ROLE_INVALID");
        }
        return role;
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

    private record BindingRow(UUID id, long cloudUserId, String keycloakSubject,
                              UUID principalId, UUID billingSubjectId, UUID organizationId,
                              String organizationRole, UUID installId, long revision,
                              String assertionJws, String status) {
        BindingContext context() {
            return new BindingContext(cloudUserId, keycloakSubject, principalId, billingSubjectId,
                    organizationId, organizationRole, installId, revision, status);
        }
    }

    public record BindingContext(long userId, String providerId, UUID principalId,
                                 UUID billingSubjectId, UUID organizationId,
                                 String organizationRole, UUID installId,
                                 long bindingRevision, String status) {}
}
