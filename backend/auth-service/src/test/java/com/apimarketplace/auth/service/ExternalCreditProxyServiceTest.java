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
            new ObjectMapper().findAndRegisterModules());

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

    @Test
    void llmCommitForwardsCompleteUsageForPaidAuthorityRepricing() {
        UUID operationId = UUID.randomUUID();
        String hash = "c".repeat(64);
        when(authority.commit(eq(operationId), any()))
                .thenReturn(new CloudCreditAuthorityService.SettlementResponse(
                        operationId, "COMMITTED", new BigDecimal("7.50"),
                        new BigDecimal("92.50"), false, "COMMITTED"));

        service.commitLlm(operationId, new ExternalCreditProxyService.LlmCommitCommand(
                "openai", "gpt", "provider-request", 10, 2, hash,
                3, 4, 5, 6));

        var request = org.mockito.ArgumentCaptor.forClass(
                CloudCreditAuthorityService.CommitRequest.class);
        verify(authority).commit(eq(operationId), request.capture());
        assertThat(request.getValue().actualCredits()).isEqualByComparingTo("0");
        assertThat(request.getValue().cacheCreationTokens()).isEqualTo(3);
        assertThat(request.getValue().cacheReadTokens()).isEqualTo(4);
        assertThat(request.getValue().cachedTokens()).isEqualTo(5);
        assertThat(request.getValue().reasoningTokens()).isEqualTo(6);
    }

    @Test
    void negativeProviderUsageIsAClientErrorAndNeverReachesAuthority() {
        assertThatThrownBy(() -> service.commitLlm(UUID.randomUUID(),
                new ExternalCreditProxyService.LlmCommitCommand(
                        "openai", "gpt", null, 1, 1, "d".repeat(64),
                        -1, null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(authority);
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
