package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ExternalCreditProxyServiceTest {

    private final EntitlementProjectionService entitlements =
            mock(EntitlementProjectionService.class);
    private final PaidMonolithCreditClient authority =
            mock(PaidMonolithCreditClient.class);
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final ExternalCreditProxyService service = new ExternalCreditProxyService(
            entitlements, authority, jdbc,
            new ObjectMapper().findAndRegisterModules(), mock(ModelPricingService.class));

    @Test
    void permanentAuthorityRejectionPersistsDeadLetterAndPropagatesOriginalStatus() throws Exception {
        UUID operationId = UUID.randomUUID();
        var command = new ExternalCreditProxyService.CommitCommand(
                BigDecimal.ONE, "openai", "gpt", "provider-request", 10L, 2L,
                "a".repeat(64));
        doThrow(new PaidMonolithCreditClient.PermanentAuthorityException(
                409, "REQUEST_HASH_MISMATCH", null))
                .when(authority).commit(eq(operationId), any());

        assertThatThrownBy(() -> service.commit(operationId, command))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));

        assertThat(jdbc.sql).anyMatch(sql -> sql.contains("'DEAD'"));
        assertThat(jdbc.sql).anyMatch(sql -> sql.contains("SETTLEMENT_FAILED"));

        Transactional contract = ExternalCreditProxyService.class
                .getMethod("commit", UUID.class,
                        ExternalCreditProxyService.CommitCommand.class)
                .getAnnotation(Transactional.class);
        assertThat(contract.noRollbackFor()).contains(ResponseStatusException.class);
    }

    @Test
    void retryableAuthorityFailureCreatesPendingOutboxWithoutTerminalState() {
        UUID operationId = UUID.randomUUID();
        var command = new ExternalCreditProxyService.ReleaseCommand(
                "provider-failure", "b".repeat(64));
        doThrow(new PaidMonolithCreditClient.RetryableAuthorityException("timeout", null))
                .when(authority).release(eq(operationId), any());

        ExternalCreditProxyService.SettlementResult result =
                service.release(operationId, command);

        assertThat(result.queued()).isTrue();
        assertThat(jdbc.sql).anyMatch(sql -> sql.contains("'PENDING'"));
        assertThat(jdbc.sql).noneMatch(sql -> sql.contains("'DEAD'"));
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final List<String> sql = new ArrayList<>();

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            return 1;
        }
    }
}
