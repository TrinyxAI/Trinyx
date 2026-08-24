package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudIdentityBindingServiceTest {

    private final TrinyxAssertionService assertions = mock(TrinyxAssertionService.class);
    private final FakeJdbc jdbc = new FakeJdbc();
    private final CloudIdentityBindingService service = new CloudIdentityBindingService(
            jdbc, assertions, "https://app.trinyx.fr", "trinyx-cloud");

    @Test
    void signedHigherRevisionRevokesWithoutDeletingTheBinding() {
        jdbc.current = row(1, "ACTIVE", "active-jws", UUID.randomUUID());
        ObjectNode tombstone = assertion(2, "REVOKED", UUID.randomUUID());
        when(assertions.verifyIdentity("tombstone", "https://app.trinyx.fr", "trinyx-cloud"))
                .thenReturn(tombstone);

        var result = service.applyRevocation("tombstone");

        assertThat(result.status()).isEqualTo("REVOKED");
        assertThat(result.bindingRevision()).isEqualTo(2);
        assertThat(jdbc.lastUpdateSql).contains("status='REVOKED'");
        assertThat(jdbc.lastUpdateSql).doesNotContain("DELETE");
    }

    @Test
    void staleActiveAssertionCannotReactivateARevokedBinding() {
        jdbc.current = row(3, "REVOKED", "revoked-jws", UUID.randomUUID());
        ObjectNode active = assertion(2, "ACTIVE", UUID.randomUUID());
        when(assertions.verifyIdentity("stale", "https://app.trinyx.fr", "trinyx-cloud"))
                .thenReturn(active);

        assertThatThrownBy(() -> service.bind("stale", SUBJECT, 42L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("STALE_BINDING_REVISION");
        assertThat(jdbc.updates).isZero();
    }

    @Test
    void explicitlyRevokedBindingCanRebindPrincipalToANewKeycloakUser() {
        jdbc.current = row(1, "REVOKED", "revoked-jws", UUID.randomUUID());
        ObjectNode active = assertion(2, "ACTIVE", UUID.randomUUID());
        active.put("keycloakSubject", "replacement-subject");
        when(assertions.verifyIdentity("rebind", "https://app.trinyx.fr", "trinyx-cloud"))
                .thenReturn(active);

        var rebound = service.bind("rebind", "replacement-subject", 43L);

        assertThat(rebound.userId()).isEqualTo(43L);
        assertThat(rebound.providerId()).isEqualTo("replacement-subject");
        assertThat(rebound.principalId()).isEqualTo(PRINCIPAL);
        assertThat(rebound.billingSubjectId()).isEqualTo(PAYER);
        assertThat(rebound.organizationRole()).isEqualTo("OWNER");
        assertThat(jdbc.updates).isGreaterThanOrEqualTo(3);
    }

    @Test
    void sameTombstoneJtiIsIdempotentButDifferentPayloadIsReplay() {
        UUID jti = UUID.randomUUID();
        jdbc.replay = row(4, "REVOKED", "same", jti);
        ObjectNode tombstone = assertion(4, "REVOKED", jti);
        when(assertions.verifyIdentity(anyString(), anyString(), anyString())).thenReturn(tombstone);

        assertThat(service.applyRevocation("same").status()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.applyRevocation("different"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("IDENTITY_JTI_REPLAY");
    }

    @Test
    void memberBindingFailsClosedUntilSignedOwnerWorkspaceExists() {
        jdbc.ownerCount = 0;
        ObjectNode member = assertion(1, "ACTIVE", UUID.randomUUID())
                .put("keycloakSubject", "member-before-owner")
                .put("organizationRole", "MEMBER");
        when(assertions.verifyIdentity(anyString(), anyString(), anyString())).thenReturn(member);

        assertThatThrownBy(() -> service.bind("member-jws", "member-before-owner", 43L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ORGANIZATION_OWNER_BINDING_REQUIRED");
        assertThat(jdbc.membershipWrites).isZero();
    }

    @Test
    void multipleMembersKeepDistinctPrincipalsAndShareOnePayerWorkspace() {
        UUID principalB = UUID.randomUUID();
        UUID principalC = UUID.randomUUID();
        ObjectNode memberB = assertion(1, "ACTIVE", UUID.randomUUID())
                .put("principalId", principalB.toString())
                .put("keycloakSubject", "member-b")
                .put("organizationRole", "MEMBER");
        ObjectNode memberC = assertion(1, "ACTIVE", UUID.randomUUID())
                .put("principalId", principalC.toString())
                .put("keycloakSubject", "member-c")
                .put("organizationRole", "MEMBER");
        when(assertions.verifyIdentity(anyString(), anyString(), anyString()))
                .thenReturn(memberB, memberC);

        var boundB = service.bind("member-b-jws", "member-b", 43L);
        var boundC = service.bind("member-c-jws", "member-c", 44L);

        assertThat(boundB.principalId()).isNotEqualTo(boundC.principalId());
        assertThat(boundB.billingSubjectId()).isEqualTo(PAYER);
        assertThat(boundC.billingSubjectId()).isEqualTo(PAYER);
        assertThat(boundB.organizationId()).isEqualTo(ORG);
        assertThat(boundC.organizationId()).isEqualTo(ORG);
        assertThat(jdbc.membershipWrites).isEqualTo(2);
    }

    @Test
    void organizationMemberMayUseOwnerInstallOnlyForExactActiveSignedScope() {
        jdbc.installAccessCount = 1;

        assertThat(service.userMayUseActiveInstall(43L, INSTALL)).isTrue();
        assertThat(jdbc.lastQueryForObjectSql)
                .contains("JOIN auth.ce_link link")
                .contains("link.status='ACTIVE'")
                .contains("owner_binding.status='ACTIVE'")
                .contains("owner_binding.organization_id=actor_binding.organization_id")
                .contains("owner_binding.billing_subject_id=actor_binding.billing_subject_id");
        assertThat(jdbc.lastQueryForObjectArgs).containsExactly(
                "https://app.trinyx.fr", 43L, INSTALL);
    }

    @Test
    void mismatchedRevokedOrUnknownMemberScopeFailsClosed() {
        jdbc.installAccessCount = 0;

        assertThat(service.userMayUseActiveInstall(44L, INSTALL)).isFalse();
    }

    private ObjectNode assertion(long revision, String status, UUID jti) {
        Instant now = Instant.now();
        ObjectNode claims = new ObjectMapper().createObjectNode();
        claims.put("schemaVersion", 2);
        claims.put("iss", "https://app.trinyx.fr");
        claims.put("aud", "trinyx-cloud");
        claims.put("jti", jti.toString());
        claims.put("iat", now.minusSeconds(1).getEpochSecond());
        claims.put("nbf", now.minusSeconds(1).getEpochSecond());
        claims.put("exp", now.plusSeconds(300).getEpochSecond());
        claims.put("bindingRevision", revision);
        claims.put("status", status);
        claims.put("principalId", PRINCIPAL.toString());
        claims.put("billingSubjectId", PAYER.toString());
        claims.put("keycloakSubject", SUBJECT);
        claims.put("organizationId", ORG.toString());
        claims.put("organizationRole", "OWNER");
        claims.put("installId", INSTALL.toString());
        return claims;
    }

    private static FakeRow row(long revision, String status, String assertion, UUID jti) {
        return new FakeRow(UUID.randomUUID(), 42L, SUBJECT, PRINCIPAL, PAYER,
                ORG, INSTALL, revision, assertion, status, jti);
    }

    private static final String SUBJECT = "keycloak-subject";
    private static final UUID PRINCIPAL = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PAYER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ORG = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID INSTALL = UUID.fromString("40000000-0000-0000-0000-000000000004");

    private record FakeRow(UUID id, long cloudUserId, String keycloakSubject,
                           UUID principalId, UUID billingSubjectId, UUID organizationId,
                           UUID installId, long revision, String assertion, String status,
                           UUID jti) {}

    private static final class FakeJdbc extends JdbcTemplate {
        FakeRow current;
        FakeRow replay;
        int updates;
        int membershipWrites;
        int ownerCount = 1;
        int installAccessCount;
        String lastUpdateSql = "";
        String lastQueryForObjectSql = "";
        Object[] lastQueryForObjectArgs = new Object[0];

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            FakeRow source = sql.contains("assertion_jti") ? replay : current;
            if (source == null) return List.of();
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(source.id());
                when(rs.getLong("cloud_user_id")).thenReturn(source.cloudUserId());
                when(rs.getString("keycloak_subject")).thenReturn(source.keycloakSubject());
                when(rs.getObject("principal_id", UUID.class)).thenReturn(source.principalId());
                when(rs.getObject("billing_subject_id", UUID.class)).thenReturn(source.billingSubjectId());
                when(rs.getObject("organization_id", UUID.class)).thenReturn(source.organizationId());
                when(rs.getString("organization_role")).thenReturn("OWNER");
                when(rs.getObject("install_id", UUID.class)).thenReturn(source.installId());
                when(rs.getLong("binding_revision")).thenReturn(source.revision());
                when(rs.getString("assertion_jws")).thenReturn(source.assertion());
                when(rs.getString("status")).thenReturn(source.status());
                return List.of(mapper.mapRow(rs, 0));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            lastQueryForObjectSql = sql;
            lastQueryForObjectArgs = args;
            int value = sql.contains("JOIN auth.ce_link link") ? installAccessCount : ownerCount;
            return requiredType.cast(value);
        }

        @Override
        public int update(String sql, Object... args) {
            updates++;
            if (sql.contains("INSERT INTO auth.organization_member")) membershipWrites++;
            lastUpdateSql = sql;
            return 1;
        }
    }
}
