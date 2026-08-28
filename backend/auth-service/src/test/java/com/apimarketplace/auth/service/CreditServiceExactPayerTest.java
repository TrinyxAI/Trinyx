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
    void resolverFailureNeverFallsBackToExecutor() {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        CreditService service = new CreditService(subscriptions, mock(CreditLedgerRepository.class),
                mock(ModelPricingService.class), false);
        PlanResolutionService resolver = mock(PlanResolutionService.class);
        when(resolver.resolvePayerUserId(42L)).thenThrow(new IllegalStateException("membership unavailable"));
        service.setPlanResolutionService(resolver);

        assertThatThrownBy(() -> service.tryReserveMarkup(42L, "cloud-reservation:test",
                "vendor", "model", BigDecimal.ONE, null, 10, "CLOUD", "test", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed closed");
        verifyNoInteractions(subscriptions);
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
