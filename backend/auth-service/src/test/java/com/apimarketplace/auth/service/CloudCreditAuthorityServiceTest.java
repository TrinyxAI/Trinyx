package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudCreditAuthorityServiceTest {

    private final CreditService credits = mock(CreditService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final FakeJdbc jdbc = new FakeJdbc();
    private final CloudCreditAuthorityService service =
            new CloudCreditAuthorityService(jdbc, credits, json);

    @Test
    void duplicateReserveReturnsOriginalResponseWithoutASecondWalletHold() throws Exception {
        UUID operationId = UUID.randomUUID();
        String hash = "a".repeat(64);
        var original = new CloudCreditAuthorityService.ReserveResponse(
                operationId, operationId, "RESERVED", Instant.now().plusSeconds(300),
                new BigDecimal("42.5"), false);
        jdbc.row = ExistingRow.reserved(operationId, hash, json.writeValueAsString(original));

        var result = service.reserve(reserve(operationId, hash));

        assertThat(result).isEqualTo(original);
        verifyNoInteractions(credits);
        assertThat(jdbc.updates).isZero();
    }

    @Test
    void duplicateOperationWithDifferentHashIsRejected() {
        UUID operationId = UUID.randomUUID();
        jdbc.row = ExistingRow.reserved(operationId, "a".repeat(64), "{}");

        assertThatThrownBy(() -> service.reserve(reserve(operationId, "b".repeat(64))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("OPERATION_ID_CONFLICT");
        verifyNoInteractions(credits);
    }

    @Test
    void duplicateIdenticalCommitReturnsStoredResultExactlyOnce() throws Exception {
        UUID operationId = UUID.randomUUID();
        String requestHash = "c".repeat(64);
        var command = new CloudCreditAuthorityService.CommitRequest(
                new BigDecimal("3.25"), "openai", "gpt", "provider-request", 10L, 2L, requestHash);
        var original = new CloudCreditAuthorityService.SettlementResponse(
                operationId, "COMMITTED", new BigDecimal("3.25"),
                new BigDecimal("20"), false, "COMMITTED");
        jdbc.row = new ExistingRow(operationId, requestHash,
                CanonicalJson.sha256(json.valueToTree(command)), "COMMITTED",
                json.writeValueAsString(original), Instant.now().plusSeconds(3600));

        assertThat(service.commit(operationId, command)).isEqualTo(original);
        verifyNoInteractions(credits);
        assertThat(jdbc.updates).isZero();
    }

    @Test
    void commitAfterReleaseAndReleaseAfterCommitAreTerminalConflicts() {
        UUID operationId = UUID.randomUUID();
        String hash = "d".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, null, "RELEASED", "{}",
                Instant.now().plusSeconds(3600));
        var commit = new CloudCreditAuthorityService.CommitRequest(
                BigDecimal.ONE, "p", "m", null, null, null, hash);
        assertThatThrownBy(() -> service.commit(operationId, commit))
                .hasMessageContaining("COMMIT_AFTER_RELEASE");

        jdbc.row = new ExistingRow(operationId, hash, null, "COMMITTED", "{}",
                Instant.now().plusSeconds(3600));
        var release = new CloudCreditAuthorityService.ReleaseRequest("cancel", hash);
        assertThatThrownBy(() -> service.release(operationId, release))
                .hasMessageContaining("RELEASE_AFTER_COMMIT");
        verifyNoInteractions(credits);
    }

    private static CloudCreditAuthorityService.ReserveRequest reserve(UUID id, String hash) {
        return new CloudCreditAuthorityService.ReserveRequest(id, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7,
                "LLM", BigDecimal.ONE, BigDecimal.TEN, "openai", "gpt", hash);
    }

    private record ExistingRow(UUID operationId, String requestHash, String settlementHash,
                               String state, String response, Instant lateSettlementUntil) {
        static ExistingRow reserved(UUID id, String hash, String response) {
            return new ExistingRow(id, hash, null, "RESERVED", response,
                    Instant.now().plusSeconds(3600));
        }
    }

    private static final class FakeJdbc extends JdbcTemplate {
        ExistingRow row;
        int updates;

        @Override
        public void query(String sql, RowCallbackHandler handler, Object... args) {
            // advisory lock
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            if (row == null || !sql.contains("auth.cloud_credit_operation")) return List.of();
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("operation_id", UUID.class)).thenReturn(row.operationId());
                when(rs.getString("request_hash")).thenReturn(row.requestHash());
                when(rs.getString("settlement_hash")).thenReturn(row.settlementHash());
                when(rs.getString("state")).thenReturn(row.state());
                when(rs.getString("response_payload")).thenReturn(row.response());
                when(rs.getTimestamp("late_settlement_until"))
                        .thenReturn(Timestamp.from(row.lateSettlementUntil()));
                when(rs.getLong("executor_user_id")).thenReturn(42L);
                when(rs.getObject("organization_id", UUID.class)).thenReturn(UUID.randomUUID());
                return List.of(mapper.mapRow(rs, 0));
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
