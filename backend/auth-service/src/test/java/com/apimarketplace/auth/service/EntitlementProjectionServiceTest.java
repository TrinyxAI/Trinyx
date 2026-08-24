package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntitlementProjectionServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final TrinyxAssertionService assertions = mock(TrinyxAssertionService.class);
    private final FakeJdbc jdbc = new FakeJdbc();
    private final EntitlementProjectionService service = new EntitlementProjectionService(
            jdbc, assertions, "https://app.trinyx.fr", "trinyx-cloud");

    @Test
    void higherSequenceAppliesAndIdenticalRetryIsIdempotentForAllMembersOfSamePayer() {
        ObjectNode projection = projection(2, "ACTIVE");
        when(assertions.verifyEntitlement("signed", "https://app.trinyx.fr", "trinyx-cloud"))
                .thenReturn(projection);
        jdbc.currentProjectionId = UUID.fromString(projection.path("projectionId").asText());
        jdbc.currentSequence = 1;
        jdbc.currentHash = "old";

        assertThat(service.apply("signed").result()).isEqualTo("APPLIED");
        assertThat(jdbc.updates).isEqualTo(1);

        jdbc.currentSequence = 2;
        jdbc.currentHash = CanonicalJson.sha256(projection);
        assertThat(service.apply("signed").result()).isEqualTo("IDEMPOTENT");
        assertThat(jdbc.updates).isEqualTo(1);
        assertThat(projection.has("principalId")).isFalse();
        assertThat(projection.has("keycloakSubject")).isFalse();
    }

    @Test
    void staleAndEquivocatingSequencesFailClosed() {
        ObjectNode projection = projection(2, "ACTIVE");
        when(assertions.verifyEntitlement(anyString(), anyString(), anyString())).thenReturn(projection);
        jdbc.currentProjectionId = UUID.fromString(projection.path("projectionId").asText());
        jdbc.currentSequence = 3;
        jdbc.currentHash = "different";

        assertThatThrownBy(() -> service.apply("stale"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("STALE_PROJECTION");

        jdbc.currentSequence = 2;
        assertThatThrownBy(() -> service.apply("equivocation"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("EQUIVOCATION_DETECTED");
        assertThat(jdbc.updates).isZero();
    }

    @Test
    void revokedAndExpiredProjectionDenyPaidOperations() {
        jdbc.decisionSequence = 9;
        jdbc.decisionState = "REVOKED";
        jdbc.decisionExpiry = Instant.now().plusSeconds(60);
        jdbc.decisionPayload = projection(9, "REVOKED").toString();

        var revoked = service.authorize(INSTALL, ORG, PAYER, "cloudLlmRelay", true);
        assertThat(revoked.allowed()).isFalse();
        assertThat(revoked.reason()).isEqualTo("ENTITLEMENT_REVOKED");

        jdbc.decisionState = "ACTIVE";
        jdbc.decisionExpiry = Instant.now().minusSeconds(1);
        var expired = service.authorize(INSTALL, ORG, PAYER, "cloudLlmRelay", true);
        assertThat(expired.allowed()).isFalse();
        assertThat(expired.reason()).isEqualTo("ENTITLEMENT_EXPIRED");
    }

    @Test
    void activeCeEntitlementReadsActorFreeProjectionAndPreservesPayerPlanMetadata() {
        ObjectNode payload = projection(8, "ACTIVE");
        payload.put("planCode", "PRO");
        payload.put("creditTierIndex", 2);
        payload.put("cadence", "yearly");
        jdbc.ceProjectionPayload = payload.toString();
        jdbc.ceProjectionSequence = 8;
        jdbc.ceProjectionExpiry = Instant.now().plusSeconds(600);

        var result = service.activeCeEntitlement(42L, INSTALL);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().planCode()).isEqualTo("PRO");
        assertThat(result.orElseThrow().creditTierIndex()).isEqualTo(2);
        assertThat(result.orElseThrow().cadence()).isEqualTo("yearly");
        assertThat(result.orElseThrow().sequence()).isEqualTo(8);
    }

    @Test
    void actorIdentityOrSpendableBalanceIsRejectedFromProjectionSchema() {
        ObjectNode projection = projection(1, "ACTIVE");
        projection.put("principalId", UUID.randomUUID().toString());
        when(assertions.verifyEntitlement(anyString(), anyString(), anyString())).thenReturn(projection);
        assertThatThrownBy(() -> service.apply("signed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }

    private ObjectNode projection(long sequence, String state) {
        Instant now = Instant.now();
        ObjectNode claims = json.createObjectNode();
        claims.put("schemaVersion", 2);
        claims.put("iss", "https://app.trinyx.fr");
        claims.put("aud", "trinyx-cloud");
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("projectionId", PROJECTION.toString());
        claims.put("eventId", UUID.randomUUID().toString());
        claims.put("sequence", sequence);
        claims.put("installId", INSTALL.toString());
        claims.put("organizationId", ORG.toString());
        claims.put("billingSubjectId", PAYER.toString());
        claims.put("accessState", state);
        claims.putObject("features")
                .put("cloudLlmRelay", true)
                .put("cloudWebSearchRelay", true)
                .put("catalogBundle", true)
                .put("skillBundle", true);
        claims.putObject("limits").put("maxAgents", 5);
        claims.put("iat", now.minusSeconds(1).getEpochSecond());
        claims.put("nbf", now.minusSeconds(1).getEpochSecond());
        claims.put("exp", now.plusSeconds(900).getEpochSecond());
        return claims;
    }

    private static final UUID PROJECTION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID INSTALL = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ORG = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID PAYER = UUID.fromString("40000000-0000-0000-0000-000000000004");

    private static final class FakeJdbc extends JdbcTemplate {
        UUID currentProjectionId;
        long currentSequence;
        String currentHash;
        long decisionSequence;
        String decisionState;
        Instant decisionExpiry;
        String decisionPayload;
        long ceProjectionSequence;
        Instant ceProjectionExpiry;
        String ceProjectionPayload;
        int updates;

        @Override
        public void query(String sql, RowCallbackHandler handler, Object... args) {
            // advisory transaction lock
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            try {
                if (sql.contains("SELECT projection_id")) {
                    if (currentProjectionId == null) return List.of();
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("projection_id", UUID.class)).thenReturn(currentProjectionId);
                    when(rs.getLong("sequence")).thenReturn(currentSequence);
                    when(rs.getString("state_hash")).thenReturn(currentHash);
                    return List.of(mapper.mapRow(rs, 0));
                }
                if (sql.contains("SELECT sequence, access_state")) {
                    if (decisionState == null) return List.of();
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("sequence")).thenReturn(decisionSequence);
                    when(rs.getString("access_state")).thenReturn(decisionState);
                    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(decisionExpiry));
                    when(rs.getString("canonical_payload")).thenReturn(decisionPayload);
                    return List.of(mapper.mapRow(rs, 0));
                }
                if (sql.contains("FROM auth.cloud_identity_binding")) {
                    if (ceProjectionPayload == null) return List.of();
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("sequence")).thenReturn(ceProjectionSequence);
                    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(ceProjectionExpiry));
                    when(rs.getString("canonical_payload")).thenReturn(ceProjectionPayload);
                    return List.of(mapper.mapRow(rs, 0));
                }
                return List.of();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            updates++;
            return 1;
        }
    }
}
