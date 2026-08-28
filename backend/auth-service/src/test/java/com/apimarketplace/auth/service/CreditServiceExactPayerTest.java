package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CreditServiceExactPayerTest {
    @Test
    void authoritativeOrganizationPayerIsDebitedAndExecutorIsOnlyAttributed() {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
        CreditService service = new CreditService(subscriptions, ledger, mock(ModelPricingService.class), false);
        Subscription ownerWallet = wallet(false, "25");
        when(subscriptions.findActiveByUserIdForUpdate(84L)).thenReturn(Optional.of(ownerWallet));
        when(ledger.existsBySourceId("cloud-reservation:test")).thenReturn(false);
        AtomicReference<CreditLedgerEntry> saved = new AtomicReference<>();
        when(ledger.save(any(CreditLedgerEntry.class))).thenAnswer(call -> { saved.set(call.getArgument(0)); return saved.get(); });

        var result = service.tryReserveMarkupForExactPayer(42L, 84L, "cloud-reservation:test",
                "vendor", "model", new BigDecimal("5"), null, 10, "CLOUD", "test", false);

        assertThat(result.success()).isTrue();
        assertThat(ownerWallet.getTotalBalance()).isEqualByComparingTo("20");
        assertThat(saved.get().getUserId()).isEqualTo(84L);
        assertThat(saved.get().getExecutorUserId()).isEqualTo(42L);
        verify(subscriptions, never()).findActiveByUserIdForUpdate(42L);
    }

    @Test
    void delinquentOrganizationCannotBeBypassedWithMemberWallet() {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
        CreditService service = new CreditService(subscriptions, ledger, mock(ModelPricingService.class), false);
        when(subscriptions.findActiveByUserIdForUpdate(84L)).thenReturn(Optional.of(wallet(true, "25")));

        var result = service.tryReserveMarkupForExactPayer(42L, 84L, "cloud-reservation:test",
                "vendor", "model", BigDecimal.ONE, null, 10, "CLOUD", "test", false);

        assertThat(result.success()).isFalse();
        assertThat(result.delinquent()).isTrue();
        verify(subscriptions, never()).findActiveByUserIdForUpdate(42L);
        verify(ledger, never()).save(any());
    }

    @Test
    void legacyGenericReservationKeepsSelfPayFallbackOnResolverFailure() {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
        CreditService service = new CreditService(subscriptions, ledger,
                mock(ModelPricingService.class), false);
        PlanResolutionService resolver = mock(PlanResolutionService.class);
        when(resolver.resolvePayerUserId(42L))
                .thenThrow(new IllegalStateException("membership unavailable"));
        service.setPlanResolutionService(resolver);
        Subscription executorWallet = wallet(false, "10");
        when(subscriptions.findActiveByUserIdForUpdate(42L))
                .thenReturn(Optional.of(executorWallet));
        when(ledger.existsBySourceId("legacy-reservation:test")).thenReturn(false);
        AtomicReference<CreditLedgerEntry> saved = new AtomicReference<>();
        when(ledger.save(any(CreditLedgerEntry.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return saved.get();
        });

        var result = service.tryReserveMarkup(42L, "legacy-reservation:test",
                "vendor", "model", BigDecimal.ONE, null, 10, "LEGACY", "test", false);

        assertThat(result.success()).isTrue();
        assertThat(executorWallet.getTotalBalance()).isEqualByComparingTo("9");
        assertThat(saved.get().getUserId()).isEqualTo(42L);
        assertThat(saved.get().getExecutorUserId()).isEqualTo(42L);
    }

    @Test
    void exactPayerNoOpBranchesReturnThePayerBalance() {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
        Subscription payerWallet = wallet(false, "17");
        when(subscriptions.findActiveByUserId(84L)).thenReturn(Optional.of(payerWallet));

        CreditService enabled = new CreditService(subscriptions, ledger,
                mock(ModelPricingService.class), false);
        var zeroProjection = enabled.tryReserveMarkupForExactPayer(
                42L, 84L, "cloud-reservation:zero", "vendor", "model",
                BigDecimal.ZERO, null, 10, "CLOUD", "zero", false);

        CreditService disabled = new CreditService(subscriptions, ledger,
                mock(ModelPricingService.class), false, false, false);
        var markupDisabled = disabled.tryReserveMarkupForExactPayer(
                42L, 84L, "cloud-reservation:disabled", "vendor", "model",
                BigDecimal.ONE, null, 10, "CLOUD", "disabled", false);

        assertThat(zeroProjection.remainingBalance()).isEqualByComparingTo("17");
        assertThat(markupDisabled.remainingBalance()).isEqualByComparingTo("17");
        verify(subscriptions, never()).findActiveByUserId(42L);
        verifyNoInteractions(ledger);
    }

    @Test
    void reservationPayerMismatchFailsClosed() {
        CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
        CreditService service = new CreditService(mock(SubscriptionRepository.class), ledger,
                mock(ModelPricingService.class), false);
        CreditLedgerEntry held = new CreditLedgerEntry();
        held.setUserId(42L);
        when(ledger.findFirstBySourceId("cloud-reservation:test")).thenReturn(Optional.of(held));

        assertThatThrownBy(() -> service.requireReservationPayer("cloud-reservation:test", 84L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("payer mismatch");
    }

    private static Subscription wallet(boolean delinquent, String balance) {
        Subscription wallet = new Subscription();
        wallet.setId(1L);
        wallet.setPlan(new Plan("PRO", "Pro", "paid"));
        wallet.setRemainingCredits(new BigDecimal(balance));
        wallet.setPaygRemainingCredits(BigDecimal.ZERO);
        wallet.setDelinquent(delinquent);
        return wallet;
    }
}
