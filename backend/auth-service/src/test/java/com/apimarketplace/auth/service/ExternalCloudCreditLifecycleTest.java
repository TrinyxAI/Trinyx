package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Cross-component contract tests for the historical LiveContext reservation
 * ledger and the external Trinyx authority lifecycle. CreditService is real;
 * only persistence ports are mocked.
 */
@ExtendWith(MockitoExtension.class)
class ExternalCloudCreditLifecycleTest {

    private static final long USER_ID = 42L;
    private static final String PROVIDER = "vendor";
    private static final String MODEL = "tool";

    @Mock
    private SubscriptionRepository subscriptions;
    @Mock
    private CreditLedgerRepository ledger;
    @Mock
    private ModelPricingService pricing;

    private final Map<String, CreditLedgerEntry> rows = new HashMap<>();
    private CreditService credits;
    private Subscription subscription;

    @BeforeEach
    void setUp() {
        credits = new CreditService(subscriptions, ledger, pricing, false);
        lenient().when(subscriptions.findActiveByUserIdForUpdate(anyLong()))
                .thenAnswer(ignored -> Optional.ofNullable(subscription));
        lenient().when(subscriptions.findActiveByUserId(anyLong()))
                .thenAnswer(ignored -> Optional.ofNullable(subscription));
        lenient().when(ledger.existsBySourceId(any()))
                .thenAnswer(invocation -> rows.containsKey(invocation.getArgument(0)));
        lenient().when(ledger.findFirstBySourceIdForUpdate(any()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        lenient().when(ledger.findFirstBySourceId(any()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        lenient().when(ledger.save(any(CreditLedgerEntry.class))).thenAnswer(invocation -> {
            CreditLedgerEntry row = invocation.getArgument(0);
            if (row.getSourceId() != null) {
                rows.put(row.getSourceId(), row);
            }
            return row;
        });
    }

    @Test
    void cloudUnknownHoldSurvivesGenericSweeperAndCommitsExactlyOnce() {
        subscription = wallet("FREE", "100", "10");
        String sourceId = "cloud-reservation:unknown-before-expiry";

        assertThat(reserve(sourceId, "10").success()).isTrue();
        CreditLedgerEntry hold = rows.get(sourceId);
        hold.setExpiresAt(LocalDateTime.now().minusMinutes(10));
        when(ledger.findExpiredReserves(any(LocalDateTime.class), any(Pageable.class)))
                // Defensive contract: even if a future repository regression
                // returned this Cloud-owned row, the generic sweeper must refuse it.
                .thenReturn(List.of(hold));

        new PlatformMarkupReserveSweeper(ledger, credits, true).sweepExpiredReservations();

        assertThat(hold.getSourceType()).isEqualTo("PLATFORM_MARKUP_RESERVE");
        assertThat(subscription.getRemainingCredits()).isEqualByComparingTo("100");
        assertThat(subscription.getPaygRemainingCredits()).isZero();

        assertThat(credits.settleExternalReservation(
                sourceId, new BigDecimal("5"), PROVIDER, MODEL, false))
                .isEqualTo(CreditService.CommitOutcome.COMMITTED);
        assertThat(credits.settleExternalReservation(
                sourceId, new BigDecimal("5"), PROVIDER, MODEL, false))
                .isEqualTo(CreditService.CommitOutcome.ALREADY_COMMITTED);
        assertThat(subscription.getPaygRemainingCredits()).isEqualByComparingTo("5");
    }

    @Test
    void cloudUnknownExpiredHoldSurvivesGenericSweeperAndAcceptsLateProof() {
        subscription = wallet("PRO", "20", "0");
        String sourceId = "cloud-reservation:unknown-expired";

        assertThat(reserve(sourceId, "10").success()).isTrue();
        CreditLedgerEntry hold = rows.get(sourceId);
        hold.setExpiresAt(LocalDateTime.now().minusHours(25));
        when(ledger.findExpiredReserves(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(hold));

        new PlatformMarkupReserveSweeper(ledger, credits, true).sweepExpiredReservations();

        assertThat(hold.getSourceType()).isEqualTo("PLATFORM_MARKUP_RESERVE");
        assertThat(credits.settleExternalReservation(
                sourceId, new BigDecimal("12"), PROVIDER, MODEL, true))
                .isEqualTo(CreditService.CommitOutcome.COMMITTED);
        assertThat(credits.settleExternalReservation(
                sourceId, new BigDecimal("12"), PROVIDER, MODEL, true))
                .isEqualTo(CreditService.CommitOutcome.ALREADY_COMMITTED);
        assertThat(subscription.getTotalBalance()).isEqualByComparingTo("8");
    }

    @Test
    void genericSweeperStillReleasesOrdinaryExpiredReservation() {
        subscription = wallet("PRO", "10", "0");
        String sourceId = "platform-markup:ordinary";

        assertThat(reserve(sourceId, "10").success()).isTrue();
        CreditLedgerEntry hold = rows.get(sourceId);
        hold.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(ledger.findExpiredReserves(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(hold));

        new PlatformMarkupReserveSweeper(ledger, credits, true).sweepExpiredReservations();

        assertThat(hold.getSourceType()).isEqualTo("PLATFORM_MARKUP_RELEASED_TIMEOUT");
        assertThat(subscription.getTotalBalance()).isEqualByComparingTo("10");
    }

    @Test
    void freeLateSettlementMarksPaygDebtDelinquentEvenWhenMonthlyKeepsTotalPositive() {
        subscription = wallet("FREE", "100", "0");
        String sourceId = "cloud-reservation:free-late";
        rows.put(sourceId, released(sourceId));

        CreditService.CommitOutcome outcome = credits.settleExternalReservation(
                sourceId, new BigDecimal("5"), PROVIDER, MODEL, true);

        assertThat(outcome).isEqualTo(CreditService.CommitOutcome.COMMITTED_PARTIAL);
        assertThat(subscription.getRemainingCredits()).isEqualByComparingTo("100");
        assertThat(subscription.getPaygRemainingCredits()).isEqualByComparingTo("-5");
        assertThat(subscription.getTotalBalance()).isEqualByComparingTo("95");
        assertThat(subscription.getDelinquent()).isTrue();
    }

    @Test
    void freeReservationOverrunMarksPaygDebtDelinquent() {
        subscription = wallet("FREE", "100", "10");
        String sourceId = "cloud-reservation:free-overrun";

        assertThat(reserve(sourceId, "10").success()).isTrue();
        CreditService.CommitOutcome outcome = credits.commitReservation(
                sourceId, new BigDecimal("20"), PROVIDER, MODEL);

        assertThat(outcome).isEqualTo(CreditService.CommitOutcome.COMMITTED_PARTIAL);
        assertThat(subscription.getRemainingCredits()).isEqualByComparingTo("100");
        assertThat(subscription.getPaygRemainingCredits()).isEqualByComparingTo("-10");
        assertThat(subscription.getTotalBalance()).isEqualByComparingTo("90");
        assertThat(subscription.getDelinquent()).isTrue();
    }

    @Test
    void paidPlanSettlementKeepsExistingTwoBucketRules() {
        subscription = wallet("PRO", "100", "0");
        String sourceId = "cloud-reservation:paid-late";
        rows.put(sourceId, released(sourceId));

        CreditService.CommitOutcome outcome = credits.settleExternalReservation(
                sourceId, new BigDecimal("5"), PROVIDER, MODEL, true);

        assertThat(outcome).isEqualTo(CreditService.CommitOutcome.COMMITTED);
        assertThat(subscription.getRemainingCredits()).isEqualByComparingTo("95");
        assertThat(subscription.getPaygRemainingCredits()).isZero();
        assertThat(subscription.getDelinquent()).isFalse();
    }

    private CreditService.CreditConsumeResult reserve(String sourceId, String amount) {
        return credits.tryReserveMarkup(USER_ID, sourceId, PROVIDER, MODEL,
                new BigDecimal(amount), null, 10, "CLOUD", sourceId, false);
    }

    private static Subscription wallet(String planCode, String monthly, String payg) {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setPlan(new Plan(planCode, planCode, planCode));
        sub.setRemainingCredits(new BigDecimal(monthly));
        sub.setPaygRemainingCredits(new BigDecimal(payg));
        sub.setDelinquent(false);
        return sub;
    }

    private static CreditLedgerEntry released(String sourceId) {
        CreditLedgerEntry row = new CreditLedgerEntry();
        row.setUserId(USER_ID);
        row.setSourceId(sourceId);
        row.setSourceType("PLATFORM_MARKUP_RELEASED_TIMEOUT");
        row.setAmount(BigDecimal.ZERO);
        row.setPaygPortion(BigDecimal.ZERO);
        row.setExpiresAt(null);
        return row;
    }
}
