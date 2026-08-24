package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CloudSettlementOutboxDispatcherTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final FakeJdbc jdbc = new FakeJdbc();
    private final PaidMonolithCreditClient authority = mock(PaidMonolithCreditClient.class);
    private final CloudSettlementOutboxDispatcher dispatcher =
            new CloudSettlementOutboxDispatcher(jdbc, authority, json);

    @Test
    void permanentFourHundredConflictMovesToDeadLetterWithoutRetry() throws Exception {
        jdbc.pending = pendingCommit();
        doThrow(new PaidMonolithCreditClient.PermanentAuthorityException(
                409, "COMMIT_AFTER_RELEASE", null))
                .when(authority).commit(eq(jdbc.pending.operationId()), any());

        dispatcher.dispatch();

        assertThat(jdbc.sql).anyMatch(sql -> sql.contains("status='DEAD'"));
        assertThat(jdbc.sql).anyMatch(sql -> sql.contains("SETTLEMENT_FAILED"));
        assertThat(jdbc.sql).noneMatch(sql -> sql.contains("status='FAILED'"));
    }

    @Test
    void timeoutOrFiveHundredSchedulesBoundedRetry() throws Exception {
        jdbc.pending = pendingCommit();
        doThrow(new PaidMonolithCreditClient.RetryableAuthorityException("timeout", null))
                .when(authority).commit(eq(jdbc.pending.operationId()), any());

        dispatcher.dispatch();

        assertThat(jdbc.sql).anyMatch(sql -> sql.contains("status='FAILED'"));
        assertThat(jdbc.sql).noneMatch(sql -> sql.contains("status='DEAD'"));
    }

    private PendingData pendingCommit() throws Exception {
        UUID operationId = UUID.randomUUID();
        var request = new CloudCreditAuthorityService.CommitRequest(
                BigDecimal.ONE, "openai", "gpt", "provider-request", 10L, 2L, "a".repeat(64));
        return new PendingData(UUID.randomUUID(), operationId, "COMMIT",
                json.writeValueAsString(request), 0);
    }

    private record PendingData(UUID id, UUID operationId, String action,
                               String payload, int attempts) {}

    private static final class FakeJdbc extends JdbcTemplate {
        PendingData pending;
        final List<String> sql = new ArrayList<>();

        @Override
        public <T> List<T> query(String statement, RowMapper<T> mapper, Object... args) {
            if (pending == null) return List.of();
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(pending.id());
                when(rs.getObject("operation_id", UUID.class)).thenReturn(pending.operationId());
                when(rs.getString("action")).thenReturn(pending.action());
                when(rs.getString("payload")).thenReturn(pending.payload());
                when(rs.getInt("attempt_count")).thenReturn(pending.attempts());
                return List.of(mapper.mapRow(rs, 0));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            return 1;
        }
    }
}
