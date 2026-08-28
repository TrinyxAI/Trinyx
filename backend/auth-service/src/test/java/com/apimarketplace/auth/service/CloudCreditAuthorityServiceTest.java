package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.common.security.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudCreditAuthorityServiceTest {

    private final CreditService credits = mock(CreditService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
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
    void reserveRetryAfterTerminalSettlementIsRejectedWithoutAnotherHold() {
        UUID operationId = UUID.randomUUID();
        String hash = "9".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, "settlement", "COMMITTED",
                "{}", Instant.now().plusSeconds(3600), "LLM");

        assertThatThrownBy(() -> service.reserve(reserve(operationId, hash)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("OPERATION_ALREADY_COMMITTED");
        verifyNoInteractions(credits);
        assertThat(jdbc.updates).isZero();
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
                json.writeValueAsString(original), Instant.now().plusSeconds(3600), "LLM");

        assertThat(service.commit(operationId, command)).isEqualTo(original);
        verifyNoInteractions(credits);
        assertThat(jdbc.updates).isZero();
    }

    @Test
    void commitAfterReleaseAndReleaseAfterCommitAreTerminalConflicts() {
        UUID operationId = UUID.randomUUID();
        String hash = "d".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, null, "RELEASED", "{}",
                Instant.now().plusSeconds(3600), "LLM", "p", "m");
        var commit = new CloudCreditAuthorityService.CommitRequest(
                BigDecimal.ONE, "p", "m", null, null, null, hash);
        assertThatThrownBy(() -> service.commit(operationId, commit))
                .hasMessageContaining("COMMIT_AFTER_RELEASE");

        jdbc.row = new ExistingRow(operationId, hash, null, "COMMITTED", "{}",
                Instant.now().plusSeconds(3600), "LLM");
        var release = new CloudCreditAuthorityService.ReleaseRequest("cancel", hash);
        assertThatThrownBy(() -> service.release(operationId, release))
                .hasMessageContaining("RELEASE_AFTER_COMMIT");
        verifyNoInteractions(credits);
    }

    @Test
    void reserveThenProviderCommitConsumesAuthoritativeWalletExactlyOnce() {
        UUID operationId = UUID.randomUUID();
        String hash = "e".repeat(64);
        when(credits.tryReserveMarkupForExactPayer(eq(42L), eq(84L), eq("cloud-reservation:" + operationId),
                eq("openai"), eq("gpt"), eq(BigDecimal.TEN), isNull(), eq(10),
                eq("CLOUD"), eq(operationId.toString()), eq(false)))
                .thenReturn(CreditService.CreditConsumeResult.success(BigDecimal.TEN,
                        new BigDecimal("90")));
        when(credits.settleExternalReservation(eq("cloud-reservation:" + operationId),
                eq(new BigDecimal("3.25")), eq("openai"), eq("gpt"), eq(false)))
                .thenReturn(CreditService.CommitOutcome.COMMITTED);
        when(credits.getBalance(84L)).thenReturn(new BigDecimal("96.75"));

        var held = service.reserve(reserve(operationId, hash));
        service.dispatching(operationId,
                new CloudCreditAuthorityService.DispatchingRequest(
                        hash, "openai", "gpt"));
        var command = new CloudCreditAuthorityService.CommitRequest(
                new BigDecimal("3.25"), "openai", "gpt", "provider-request", 10L, 2L, hash);
        var committed = service.commit(operationId, command);
        var retried = service.commit(operationId, command);

        assertThat(held.state()).isEqualTo("RESERVED");
        assertThat(committed.state()).isEqualTo("COMMITTED");
        assertThat(retried).isEqualTo(committed);
        verify(credits, times(1)).tryReserveMarkupForExactPayer(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean());
        verify(credits, times(1)).settleExternalReservation(anyString(), any(), anyString(),
                anyString(), anyBoolean());
    }

    @Test
    void normalCommitBeforeAuthoritativeDispatchIsRejected() {
        UUID operationId = UUID.randomUUID();
        String hash = "0".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, null, "RESERVED", "{}",
                Instant.now().plusSeconds(3600), "PLATFORM_MARKUP", "vendor", "tool");

        assertThatThrownBy(() -> service.commit(operationId,
                new CloudCreditAuthorityService.CommitRequest(
                        BigDecimal.ONE, "vendor", "tool", "provider-request",
                        null, null, hash)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("COMMIT_BEFORE_DISPATCH");
        verify(credits, never()).settleExternalReservation(
                anyString(), any(), anyString(), anyString(), anyBoolean());
        assertThat(jdbc.row.state()).isEqualTo("RESERVED");
    }

    @Test
    void providerFailureReleaseIsIdempotentAndNeverConsumes() throws Exception {
        UUID operationId = UUID.randomUUID();
        String hash = "f".repeat(64);
        jdbc.row = ExistingRow.reserved(operationId, hash, json.writeValueAsString(
                new CloudCreditAuthorityService.ReserveResponse(operationId, operationId,
                        "RESERVED", Instant.now().plusSeconds(300), BigDecimal.TEN, false)));
        when(credits.releaseReservation("cloud-reservation:" + operationId,
                "cloud-release:provider_failure"))
                .thenReturn(CreditService.ReleaseOutcome.RELEASED);
        when(credits.getBalance(84L)).thenReturn(BigDecimal.TEN);
        var command = new CloudCreditAuthorityService.ReleaseRequest("provider failure", hash);

        var released = service.release(operationId, command);
        var retried = service.release(operationId, command);

        assertThat(released.state()).isEqualTo("RELEASED");
        assertThat(retried).isEqualTo(released);
        verify(credits, times(1)).releaseReservation(anyString(), anyString());
        verify(credits, never()).settleExternalReservation(anyString(), any(), anyString(),
                anyString(), anyBoolean());
    }

    @Test
    void ambiguousProviderOutcomeRetainsHoldAndIsIdempotent() {
        UUID operationId = UUID.randomUUID();
        String hash = "0".repeat(64);
        jdbc.row = ExistingRow.reserved(operationId, hash, "{}");
        when(credits.getBalance(84L)).thenReturn(BigDecimal.TEN);
        service.dispatching(operationId,
                new CloudCreditAuthorityService.DispatchingRequest(
                        hash, "openai", "gpt"));
        var request = new CloudCreditAuthorityService.OutcomeUnknownRequest(
                "provider timeout after dispatch", hash, "openai", "gpt");

        var first = service.outcomeUnknown(operationId, request);
        var retry = service.outcomeUnknown(operationId, request);

        assertThat(first.state()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(first.outcome()).isEqualTo("RECONCILIATION_REQUIRED_HOLD_RETAINED");
        assertThat(retry).isEqualTo(first);
        verify(credits, never()).releaseReservation(anyString(), anyString());
        verify(credits, never()).settleExternalReservation(
                anyString(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void unknownOutcomeRemainsNonTerminalUntilExplicitCommit() {
        UUID operationId = UUID.randomUUID();
        String hash = "a".repeat(64);
        jdbc.row = ExistingRow.reserved(operationId, hash, "{}");
        when(credits.getBalance(84L)).thenReturn(BigDecimal.TEN);
        service.dispatching(operationId,
                new CloudCreditAuthorityService.DispatchingRequest(
                        hash, "openai", "gpt"));
        service.outcomeUnknown(operationId,
                new CloudCreditAuthorityService.OutcomeUnknownRequest(
                        "timeout", hash, "openai", "gpt"));
        when(credits.settleExternalReservation(
                "cloud-reservation:" + operationId, BigDecimal.ONE,
                "openai", "gpt", false))
                .thenReturn(CreditService.CommitOutcome.COMMITTED);

        var committed = service.commit(operationId,
                new CloudCreditAuthorityService.CommitRequest(
                        BigDecimal.ONE, "openai", "gpt", "provider-request",
                        1L, 1L, hash));

        assertThat(committed.state()).isEqualTo("COMMITTED");
        verify(credits).settleExternalReservation(
                "cloud-reservation:" + operationId, BigDecimal.ONE,
                "openai", "gpt", false);
    }

    @Test
    void unknownOutcomeRemainsNonTerminalUntilExplicitRelease() {
        UUID operationId = UUID.randomUUID();
        String hash = "b".repeat(64);
        jdbc.row = ExistingRow.reserved(operationId, hash, "{}");
        when(credits.getBalance(84L)).thenReturn(BigDecimal.TEN);
        service.dispatching(operationId,
                new CloudCreditAuthorityService.DispatchingRequest(
                        hash, "openai", "gpt"));
        service.outcomeUnknown(operationId,
                new CloudCreditAuthorityService.OutcomeUnknownRequest(
                        "timeout", hash, "openai", "gpt"));
        when(credits.releaseReservation(
                "cloud-reservation:" + operationId, "cloud-release:no-provider-charge"))
                .thenReturn(CreditService.ReleaseOutcome.RELEASED);

        var released = service.release(operationId,
                new CloudCreditAuthorityService.ReleaseRequest(
                        "no-provider-charge", hash));

        assertThat(released.state()).isEqualTo("RELEASED");
        verify(credits).releaseReservation(
                "cloud-reservation:" + operationId,
                "cloud-release:no-provider-charge");
    }

    @Test
    void expiredUndispatchedReservationCannotCommit() {
        UUID operationId = UUID.randomUUID();
        String hash = "1".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, null, "EXPIRED", "{}",
                Instant.now().plusSeconds(3600), "LLM");

        assertThatThrownBy(() -> service.commit(operationId,
                new CloudCreditAuthorityService.CommitRequest(
                        BigDecimal.ONE, "openai", "gpt", "late-provider",
                        1L, 1L, hash)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("COMMIT_AFTER_UNDISPATCHED_EXPIRY");
        verify(credits, never()).settleExternalReservation(
                anyString(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void reservedCannotBecomeOutcomeUnknownBeforeDispatch() {
        UUID operationId = UUID.randomUUID();
        String hash = "c".repeat(64);
        jdbc.row = ExistingRow.reserved(operationId, hash, "{}");

        assertThatThrownBy(() -> service.outcomeUnknown(operationId,
                new CloudCreditAuthorityService.OutcomeUnknownRequest(
                        "provider outcome cannot be ambiguous before dispatch",
                        hash, "openai", "gpt")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("OUTCOME_UNKNOWN_BEFORE_DISPATCH");
        assertThat(jdbc.row.state()).isEqualTo("RESERVED");
    }

    @Test
    void expiredCannotBecomeOutcomeUnknown() {
        UUID operationId = UUID.randomUUID();
        String hash = "d".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, null, "EXPIRED", "{}",
                Instant.now().plusSeconds(3600), "LLM", "openai", "gpt");

        assertThatThrownBy(() -> service.outcomeUnknown(operationId,
                new CloudCreditAuthorityService.OutcomeUnknownRequest(
                        "late ambiguity without dispatch", hash, "openai", "gpt")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("OUTCOME_UNKNOWN_AFTER_UNDISPATCHED_EXPIRY");
        assertThat(jdbc.row.state()).isEqualTo("EXPIRED");
    }

    @Test
    void reservedWithoutProviderDispatchExpiresThroughCloudAuthorityExactlyOnce() {
        UUID operationId = UUID.randomUUID();
        String hash = "8".repeat(64);
        jdbc.row = ExistingRow.reserved(operationId, hash, "{}");
        when(credits.releaseReservation(
                "cloud-reservation:" + operationId, "auto-release-timeout:cloud"))
                .thenReturn(CreditService.ReleaseOutcome.RELEASED);

        assertThat(service.expireDueReservations()).isEqualTo(1);
        assertThat(service.expireDueReservations()).isZero();

        assertThat(jdbc.row.state()).isEqualTo("EXPIRED");
        verify(credits, times(1)).releaseReservation(
                "cloud-reservation:" + operationId, "auto-release-timeout:cloud");
    }

    @Test
    void organizationMemberUsesPayerOwnedInstallInsteadOfActorOwnedInstall() {
        UUID operationId = UUID.randomUUID();
        String hash = "7".repeat(64);
        when(credits.tryReserveMarkupForExactPayer(eq(42L), eq(84L), eq("cloud-reservation:" + operationId),
                eq("openai"), eq("gpt"), eq(BigDecimal.TEN), isNull(), eq(10),
                eq("CLOUD"), eq(operationId.toString()), eq(false)))
                .thenReturn(CreditService.CreditConsumeResult.success(
                        BigDecimal.TEN, new BigDecimal("90")));

        service.reserve(reserve(operationId, hash));

        assertThat(jdbc.countSqls).anySatisfy(sql -> {
            assertThat(sql).contains("link.tenant_id=owner_row.id");
            assertThat(sql).contains("owner_row.billing_subject_id=?");
            assertThat(sql).contains("link.organization_id=organization_row.id::text");
        });
    }

    @Test
    void webSearchUsesPaidAuthorityFixedPriceForHoldAndSettlement() {
        UUID operationId = UUID.randomUUID();
        String hash = "8".repeat(64);
        when(credits.getWebSearchCreditsPerSearch()).thenReturn(new BigDecimal("4.5"));
        when(credits.tryReserveMarkupForExactPayer(eq(42L), eq(84L), eq("cloud-reservation:" + operationId),
                eq("websearch"), eq("default"), eq(new BigDecimal("4.5")), isNull(), eq(10),
                eq("CLOUD"), eq(operationId.toString()), eq(false)))
                .thenReturn(CreditService.CreditConsumeResult.success(
                        new BigDecimal("4.5"), new BigDecimal("95.5")));
        when(credits.settleExternalReservation(eq("cloud-reservation:" + operationId),
                eq(new BigDecimal("4.5")), eq("websearch"), eq("default"), eq(false)))
                .thenReturn(CreditService.CommitOutcome.COMMITTED);
        when(credits.getBalance(84L)).thenReturn(new BigDecimal("95.5"));

        var request = new CloudCreditAuthorityService.ReserveRequest(
                operationId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 7, "WEB_SEARCH", BigDecimal.ONE, BigDecimal.ONE,
                "websearch", "default", hash);
        service.reserve(request);
        service.dispatching(operationId,
                new CloudCreditAuthorityService.DispatchingRequest(
                        hash, "websearch", "default"));
        var result = service.commit(operationId,
                new CloudCreditAuthorityService.CommitRequest(
                        BigDecimal.ONE, "websearch", "default", "search-request",
                        0L, 0L, hash));

        assertThat(result.actualCredits()).isEqualByComparingTo("4.5");
        verify(credits).settleExternalReservation(
                "cloud-reservation:" + operationId, new BigDecimal("4.5"),
                "websearch", "default", false);
    }

    @Test
    void llmReservationIgnoresCloudAmountAndUsesPaidAuthorityPricing() {
        UUID operationId = UUID.randomUUID();
        String hash = "5".repeat(64);
        when(credits.calculateExternalLlmCredits(
                "openai", "gpt", 100, 50, null, null, null, null))
                .thenReturn(new BigDecimal("10"));
        when(credits.tryReserveMarkupForExactPayer(eq(42L), eq(84L), eq("cloud-reservation:" + operationId),
                eq("openai"), eq("gpt"), eq(new BigDecimal("12.500000")), isNull(), eq(10),
                eq("CLOUD"), eq(operationId.toString()), eq(false)))
                .thenReturn(CreditService.CreditConsumeResult.success(
                        new BigDecimal("12.500000"), new BigDecimal("87.5")));

        var result = service.reserve(new CloudCreditAuthorityService.ReserveRequest(
                operationId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 7, "CE_LLM_RELAY",
                new BigDecimal("0.000001"), new BigDecimal("0.000001"),
                "openai", "gpt", hash, 100, 50));

        assertThat(result.state()).isEqualTo("RESERVED");
        verify(credits).calculateExternalLlmCredits(
                "openai", "gpt", 100, 50, null, null, null, null);
        verify(credits).tryReserveMarkupForExactPayer(eq(42L), eq(84L), eq("cloud-reservation:" + operationId),
                eq("openai"), eq("gpt"), eq(new BigDecimal("12.500000")), isNull(), eq(10),
                eq("CLOUD"), eq(operationId.toString()), eq(false));
    }

    @Test
    void llmSettlementUsesPaidAuthorityPricingAndCompleteProviderUsage() {
        UUID operationId = UUID.randomUUID();
        String hash = "2".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, null, "DISPATCHING", "{}",
                Instant.now().plusSeconds(3600), "CE_LLM_RELAY", "openai", "gpt");
        when(credits.calculateExternalLlmCredits(
                "openai", "gpt", 10, 2, 3, 4, 5, 6))
                .thenReturn(new BigDecimal("7.50"));
        when(credits.settleExternalReservation(
                "cloud-reservation:" + operationId, new BigDecimal("7.50"),
                "openai", "gpt", false))
                .thenReturn(CreditService.CommitOutcome.COMMITTED);
        when(credits.getBalance(84L)).thenReturn(new BigDecimal("92.50"));

        var command = new CloudCreditAuthorityService.CommitRequest(
                new BigDecimal("999"), "openai", "gpt", "provider-request",
                10L, 2L, hash, 3, 4, 5, 6);
        var result = service.commit(operationId, command);

        assertThat(result.actualCredits()).isEqualByComparingTo("7.50");
        verify(credits).calculateExternalLlmCredits(
                "openai", "gpt", 10, 2, 3, 4, 5, 6);
        verify(credits).settleExternalReservation(
                "cloud-reservation:" + operationId, new BigDecimal("7.50"),
                "openai", "gpt", false);
    }

    @Test
    void browserAgentCannotReservePlaceholderAndSettleForZero() {
        UUID operationId = UUID.randomUUID();
        String hash = "6".repeat(64);
        when(credits.calculateExternalLlmCredits(
                "openai", "gpt", 400, 200, null, null, null, null))
                .thenReturn(new BigDecimal("8"));
        when(credits.tryReserveMarkupForExactPayer(eq(42L), eq(84L), eq("cloud-reservation:" + operationId),
                eq("openai"), eq("gpt"), eq(new BigDecimal("10.000000")), isNull(), eq(10),
                eq("CLOUD"), eq(operationId.toString()), eq(false)))
                .thenReturn(CreditService.CreditConsumeResult.success(
                        new BigDecimal("10.000000"), new BigDecimal("90")));
        when(credits.calculateExternalLlmCredits(
                "openai", "gpt", 120, 80, null, null, null, null))
                .thenReturn(new BigDecimal("3.75"));
        when(credits.settleExternalReservation(
                "cloud-reservation:" + operationId, new BigDecimal("3.75"),
                "openai", "gpt", false))
                .thenReturn(CreditService.CommitOutcome.COMMITTED);
        when(credits.getBalance(84L)).thenReturn(new BigDecimal("96.25"));

        service.reserve(new CloudCreditAuthorityService.ReserveRequest(
                operationId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 7, "BROWSER_AGENT_EXECUTION",
                new BigDecimal("0.000001"), new BigDecimal("0.000001"),
                "openai", "gpt", hash, 400, 200));
        service.dispatching(operationId,
                new CloudCreditAuthorityService.DispatchingRequest(
                        hash, "openai", "gpt"));
        var result = service.commit(operationId,
                new CloudCreditAuthorityService.CommitRequest(
                        BigDecimal.ZERO, "openai", "gpt", "browser-session",
                        120L, 80L, hash));

        assertThat(result.actualCredits()).isEqualByComparingTo("3.75");
        verify(credits).tryReserveMarkupForExactPayer(eq(42L), eq(84L), eq("cloud-reservation:" + operationId),
                eq("openai"), eq("gpt"), eq(new BigDecimal("10.000000")), isNull(), eq(10),
                eq("CLOUD"), eq(operationId.toString()), eq(false));
        verify(credits).settleExternalReservation(
                "cloud-reservation:" + operationId, new BigDecimal("3.75"),
                "openai", "gpt", false);
    }

    @Test
    void unknownLifecycleComposesCloudStateGenericSweeperAndRealWallet() {
        assertRealWalletUnknownLifecycle(false);
    }

    @Test
    void unknownExpiredLifecycleComposesCloudStateGenericSweeperAndRealWallet() {
        assertRealWalletUnknownLifecycle(true);
    }

    @Test
    void authoritativeDispatchRetainsHoldPastReservationExpiryAndIsIdempotent()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        String hash = "8".repeat(64);
        jdbc.row = ExistingRow.reserved(operationId, hash, "{}");
        when(credits.getBalance(84L)).thenReturn(BigDecimal.TEN);
        var request = new CloudCreditAuthorityService.DispatchingRequest(
                hash, "openai", "gpt");

        var first = service.dispatching(operationId, request);
        var retry = service.dispatching(operationId, request);

        assertThat(first.state()).isEqualTo("DISPATCHING");
        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.row.state()).isEqualTo("DISPATCHING");
        assertThat(service.expireDueReservations()).isZero();
        verify(credits, never()).releaseReservation(anyString(), anyString());
    }

    @Test
    void unknownRetryReturnsEscalatedStateInsteadOfStaleStoredPayload()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        String hash = "9".repeat(64);
        var request = new CloudCreditAuthorityService.OutcomeUnknownRequest(
                "provider timeout", hash, "vendor", "tool");
        var stored = new CloudCreditAuthorityService.SettlementResponse(
                operationId, "OUTCOME_UNKNOWN", BigDecimal.ZERO, BigDecimal.TEN,
                false, "RECONCILIATION_REQUIRED_HOLD_RETAINED");
        jdbc.row = new ExistingRow(operationId, hash,
                CanonicalJson.sha256(json.valueToTree(request)),
                "OUTCOME_UNKNOWN", json.writeValueAsString(stored),
                Instant.now().plusSeconds(60), "PLATFORM_MARKUP", "vendor", "tool");

        assertThat(service.escalateStaleUnknownOutcomes()).isEqualTo(1);
        var retry = service.outcomeUnknown(operationId, request);

        assertThat(retry.state()).isEqualTo("OUTCOME_UNKNOWN_EXPIRED");
        assertThat(retry.outcome()).isEqualTo("RECONCILIATION_REQUIRED_HOLD_RETAINED");
    }

    @Test
    void staleUnknownIsEscalatedWithoutReleasingTheWalletHold() {
        int escalated = service.escalateStaleUnknownOutcomes();

        assertThat(escalated).isEqualTo(1);
        assertThat(jdbc.lastUpdateSql)
                .contains("OUTCOME_UNKNOWN_EXPIRED")
                .contains("WHERE state='OUTCOME_UNKNOWN'");
        verify(credits, never()).releaseReservation(anyString(), anyString());
    }

    @Test
    void escalatedUnknownKeepsAFullWindowForExplicitCommit() {
        UUID operationId = UUID.randomUUID();
        String hash = "7".repeat(64);
        Instant beforeEscalation = Instant.now();
        jdbc.row = new ExistingRow(operationId, hash, "unknown-settlement-hash",
                "OUTCOME_UNKNOWN", "{}", beforeEscalation.plusSeconds(60),
                "PLATFORM_MARKUP", "vendor", "tool");
        when(credits.settleExternalReservation(
                "cloud-reservation:" + operationId, BigDecimal.ONE,
                "vendor", "tool", true))
                .thenReturn(CreditService.CommitOutcome.COMMITTED);
        when(credits.getBalance(84L)).thenReturn(BigDecimal.TEN);

        assertThat(service.escalateStaleUnknownOutcomes()).isEqualTo(1);
        assertThat(jdbc.row.state()).isEqualTo("OUTCOME_UNKNOWN_EXPIRED");
        assertThat(jdbc.row.lateSettlementUntil())
                .isAfter(beforeEscalation.plus(Duration.ofHours(23)));

        var committed = service.commit(operationId,
                new CloudCreditAuthorityService.CommitRequest(
                        BigDecimal.ONE, "vendor", "tool", "provider-proof",
                        null, null, hash));

        assertThat(committed.state()).isEqualTo("COMMITTED");
        verify(credits).settleExternalReservation(
                "cloud-reservation:" + operationId, BigDecimal.ONE,
                "vendor", "tool", true);
    }

    @Test
    void settlementCannotSubstituteTheReservedProviderOrModel() {
        UUID operationId = UUID.randomUUID();
        String hash = "3".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, null, "DISPATCHING", "{}",
                Instant.now().plusSeconds(3600), "CE_LLM_RELAY", "openai", "gpt");

        assertThatThrownBy(() -> service.commit(operationId,
                new CloudCreditAuthorityService.CommitRequest(
                        BigDecimal.ZERO, "anthropic", "claude", null,
                        1L, 1L, hash)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INVALID_COMMIT");
        verify(credits, never()).settleExternalReservation(
                anyString(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void negativeNonLlmSettlementIsRejected() {
        UUID operationId = UUID.randomUUID();
        String hash = "4".repeat(64);
        jdbc.row = new ExistingRow(operationId, hash, null, "RESERVED", "{}",
                Instant.now().plusSeconds(3600), "PLATFORM_MARKUP", "vendor", "tool");

        assertThatThrownBy(() -> service.commit(operationId,
                new CloudCreditAuthorityService.CommitRequest(
                        new BigDecimal("-1"), "vendor", "tool", null,
                        null, null, hash)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INVALID_ACTUAL_CREDITS");
        verify(credits, never()).settleExternalReservation(
                anyString(), any(), anyString(), anyString(), anyBoolean());
    }

    private void assertRealWalletUnknownLifecycle(boolean escalate) {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
        ModelPricingService pricing = mock(ModelPricingService.class);
        CreditService realCredits = new CreditService(subscriptions, ledger, pricing, false);

        Subscription wallet = new Subscription();
        wallet.setId(1L);
        wallet.setPlan(new Plan("PRO", "Pro", "paid"));
        wallet.setRemainingCredits(new BigDecimal("20"));
        wallet.setPaygRemainingCredits(BigDecimal.ZERO);
        wallet.setDelinquent(false);
        when(subscriptions.findActiveByUserIdForUpdate(42L)).thenReturn(Optional.of(wallet));
        when(subscriptions.findActiveByUserId(42L)).thenReturn(Optional.of(wallet));

        UUID operationId = UUID.randomUUID();
        String sourceId = "cloud-reservation:" + operationId;
        String hash = (escalate ? "6" : "5").repeat(64);
        CreditLedgerEntry[] held = new CreditLedgerEntry[1];
        when(ledger.existsBySourceId(sourceId)).thenReturn(false);
        when(ledger.save(any(CreditLedgerEntry.class))).thenAnswer(invocation -> {
            CreditLedgerEntry saved = invocation.getArgument(0);
            held[0] = saved;
            return saved;
        });
        when(ledger.findFirstBySourceIdForUpdate(sourceId))
                .thenAnswer(ignored -> Optional.ofNullable(held[0]));
        when(ledger.findFirstBySourceId(sourceId))
                .thenAnswer(ignored -> Optional.ofNullable(held[0]));

        assertThat(realCredits.tryReserveMarkup(42L, sourceId, "vendor", "tool",
                BigDecimal.TEN, null, 10, "CLOUD", operationId.toString(), false).success())
                .isTrue();
        held[0].setExpiresAt(LocalDateTime.now().minusMinutes(10));

        jdbc.row = new ExistingRow(operationId, hash, null, "RESERVED", "{}",
                Instant.now().plusSeconds(3600), "PLATFORM_MARKUP", "vendor", "tool");
        CloudCreditAuthorityService integrated =
                new CloudCreditAuthorityService(jdbc, realCredits, json);
        integrated.dispatching(operationId,
                new CloudCreditAuthorityService.DispatchingRequest(
                        hash, "vendor", "tool"));
        integrated.outcomeUnknown(operationId,
                new CloudCreditAuthorityService.OutcomeUnknownRequest(
                        "provider timeout after dispatch", hash, "vendor", "tool"));

        when(ledger.findExpiredReserves(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(held[0]));
        new PlatformMarkupReserveSweeper(ledger, realCredits, true)
                .sweepExpiredReservations();

        assertThat(held[0].getSourceType()).isEqualTo("PLATFORM_MARKUP_RESERVE");
        assertThat(wallet.getTotalBalance()).isEqualByComparingTo("10");

        if (escalate) {
            assertThat(integrated.escalateStaleUnknownOutcomes()).isEqualTo(1);
            assertThat(jdbc.row.state()).isEqualTo("OUTCOME_UNKNOWN_EXPIRED");
        } else {
            assertThat(jdbc.row.state()).isEqualTo("OUTCOME_UNKNOWN");
        }

        var command = new CloudCreditAuthorityService.CommitRequest(
                new BigDecimal("12"), "vendor", "tool", "provider-proof",
                null, null, hash);
        var committed = integrated.commit(operationId, command);
        var retried = integrated.commit(operationId, command);

        assertThat(committed.state()).isEqualTo("COMMITTED");
        assertThat(retried).isEqualTo(committed);
        assertThat(wallet.getTotalBalance()).isEqualByComparingTo("8");
        assertThat(held[0].getSourceType()).isEqualTo("PLATFORM_MARKUP");
    }

    private static CloudCreditAuthorityService.ReserveRequest reserve(UUID id, String hash) {
        return new CloudCreditAuthorityService.ReserveRequest(id, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7,
                "LLM", BigDecimal.ONE, BigDecimal.TEN, "openai", "gpt", hash);
    }

    private record ExistingRow(UUID operationId, String requestHash, String settlementHash,
                               String state, String response, Instant lateSettlementUntil,
                               String sourceType, String provider, String model) {
        ExistingRow(UUID operationId, String requestHash, String settlementHash,
                    String state, String response, Instant lateSettlementUntil,
                    String sourceType) {
            this(operationId, requestHash, settlementHash, state, response,
                    lateSettlementUntil, sourceType, "openai", "gpt");
        }

        static ExistingRow reserved(UUID id, String hash, String response) {
            return new ExistingRow(id, hash, null, "RESERVED", response,
                    Instant.now().plusSeconds(3600), "LLM", "openai", "gpt");
        }
    }

    private static final class FakeJdbc extends JdbcTemplate {
        ExistingRow row;
        int updates;
        String lastUpdateSql;
        final List<String> countSqls = new ArrayList<>();

        @Override
        public void query(String sql, RowCallbackHandler handler, Object... args) {
            // advisory lock
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper) {
            return query(sql, mapper, new Object[0]);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            try {
                if (sql.contains("SELECT actor.id")) {
                    ResultSet actor = mock(ResultSet.class);
                    when(actor.getLong(1)).thenReturn(42L);
                    return List.of(mapper.mapRow(actor, 0));
                }
                if (sql.contains("SELECT owner_row.id")) {
                    ResultSet payer = mock(ResultSet.class);
                    when(payer.getLong(1)).thenReturn(84L);
                    return List.of(mapper.mapRow(payer, 0));
                }
                if (row == null || !sql.contains("auth.cloud_credit_operation")) return List.of();
                if (sql.contains("WHERE state='RESERVED' AND expires_at <= now()")) {
                    if (!"RESERVED".equals(row.state())) return List.of();
                    ResultSet due = mock(ResultSet.class);
                    when(due.getObject(1, UUID.class)).thenReturn(row.operationId());
                    return List.of(mapper.mapRow(due, 0));
                }
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("operation_id", UUID.class)).thenReturn(row.operationId());
                when(rs.getString("request_hash")).thenReturn(row.requestHash());
                when(rs.getString("settlement_hash")).thenReturn(row.settlementHash());
                when(rs.getString("state")).thenReturn(row.state());
                when(rs.getString("response_payload")).thenReturn(row.response());
                when(rs.getTimestamp("late_settlement_until"))
                        .thenReturn(Timestamp.from(row.lateSettlementUntil()));
                when(rs.getLong("executor_user_id")).thenReturn(42L);
                when(rs.getLong("payer_user_id")).thenReturn(84L);
                when(rs.getObject("organization_id", UUID.class)).thenReturn(UUID.randomUUID());
                when(rs.getString("source_type")).thenReturn(row.sourceType());
                when(rs.getString("provider")).thenReturn(row.provider());
                when(rs.getString("model")).thenReturn(row.model());
                return List.of(mapper.mapRow(rs, 0));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            countSqls.add(sql);
            return requiredType.cast(1);
        }

        @Override
        public int update(String sql, Object... args) {
            updates++;
            lastUpdateSql = sql;
            if (sql.contains("INSERT INTO auth.cloud_credit_operation")) {
                row = new ExistingRow((UUID) args[0], (String) args[2], null, "RESERVED",
                        (String) args[14], ((Timestamp) args[16]).toInstant(), (String) args[9],
                        (String) args[12], (String) args[13]);
            } else if (sql.contains("SET state='DISPATCHING'") && row != null) {
                row = new ExistingRow(row.operationId(), row.requestHash(),
                        row.settlementHash(), "DISPATCHING", (String) args[0],
                        row.lateSettlementUntil(), row.sourceType(), row.provider(), row.model());
            } else if (sql.contains("SET state='EXPIRED'") && row != null) {
                row = new ExistingRow(row.operationId(), row.requestHash(),
                        row.settlementHash(), "EXPIRED", row.response(),
                        row.lateSettlementUntil(), row.sourceType(), row.provider(), row.model());
            } else if (sql.contains("SET state='OUTCOME_UNKNOWN_EXPIRED'") && row != null) {
                Instant extendedUntil = Instant.now().plusSeconds(((Number) args[0]).longValue());
                Instant currentUntil = row.lateSettlementUntil();
                row = new ExistingRow(row.operationId(), row.requestHash(),
                        row.settlementHash(), "OUTCOME_UNKNOWN_EXPIRED", row.response(),
                        currentUntil == null || currentUntil.isBefore(extendedUntil)
                                ? extendedUntil : currentUntil,
                        row.sourceType(), row.provider(), row.model());
            } else if (sql.contains("SET late_settlement_until=GREATEST") && row != null) {
                Instant extendedUntil = Instant.now().plusSeconds(((Number) args[0]).longValue());
                Instant currentUntil = row.lateSettlementUntil();
                row = new ExistingRow(row.operationId(), row.requestHash(),
                        row.settlementHash(), row.state(), row.response(),
                        currentUntil == null || currentUntil.isBefore(extendedUntil)
                                ? extendedUntil : currentUntil,
                        row.sourceType(), row.provider(), row.model());
            } else if (sql.contains("SET state=?, actual_credits")) {
                row = new ExistingRow(row.operationId(), row.requestHash(), (String) args[11],
                        (String) args[0], (String) args[12], row.lateSettlementUntil(),
                        row.sourceType(), (String) args[2], (String) args[3]);
            } else if (sql.contains("SET state=?, settlement_hash")) {
                row = new ExistingRow(row.operationId(), row.requestHash(), (String) args[1],
                        (String) args[0], (String) args[2], row.lateSettlementUntil(),
                        row.sourceType(), row.provider(), row.model());
            } else if (sql.contains("SET state='RELEASED'")) {
                row = new ExistingRow(row.operationId(), row.requestHash(), (String) args[0],
                        "RELEASED", (String) args[1], row.lateSettlementUntil(),
                        row.sourceType(), row.provider(), row.model());
            }
            return 1;
        }
    }
}
