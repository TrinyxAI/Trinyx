package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Provisioning of the bootstrap FREE subscription.
 *
 * <p>The defect this pins: the previous implementation read "is there an active subscription?"
 * and inserted one if not, with no lock and no constraint in between, from a code path that runs
 * on every gateway request. Concurrent first-login requests each created one. Production carries
 * three users with two active FREE subscriptions created 7-15 ms apart, and only one row of each
 * pair was ever funded.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FreeSubscriptionProvisioner Tests")
class FreeSubscriptionProvisionerTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PlanStorageQuotaSyncer quotaSyncer;

    @InjectMocks private FreeSubscriptionProvisioner provisioner;

    private static final Long USER_ID = 77L;

    private User user;
    private BillingCustomer billingCustomer;
    private Plan freePlan;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(USER_ID);
        billingCustomer = new BillingCustomer(user, "internal");
        billingCustomer.setId(9L);
        freePlan = new Plan();
        freePlan.setId(1L);
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        freePlan.setIncludedLlmTokens(1000L);
    }

    private void givenLockedBillingCustomer() {
        when(billingCustomerRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(billingCustomer));
    }

    @Test
    @DisplayName("creates the FREE subscription when the user has none")
    void createsWhenMissing() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            s.setId(500L);
            return s;
        });

        assertThat(provisioner.provisionIfMissing(user)).contains(500L);
        verify(quotaSyncer).syncAfterCommit(eq(USER_ID), eq(freePlan));
    }

    @Test
    @DisplayName("takes the per-user lock BEFORE deciding whether one is missing")
    void locksBeforeChecking() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> withId(inv.getArgument(0), 503L));

        provisioner.provisionIfMissing(user);

        // Checking first and locking after would leave the same race that created the
        // duplicates: two requests both read "none" before either inserts.
        InOrder order = inOrder(billingCustomerRepository, subscriptionRepository);
        order.verify(billingCustomerRepository).findByUserIdForUpdate(USER_ID);
        order.verify(subscriptionRepository).findActiveByUserId(USER_ID);
        order.verify(subscriptionRepository).save(any(Subscription.class));
    }

    @Test
    @DisplayName("creates nothing when an active subscription already exists")
    void noopWhenAlreadyProvisioned() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(new Subscription()));

        assertThat(provisioner.provisionIfMissing(user)).isEmpty();
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    @DisplayName("a unique-constraint rejection is a clean no-op, not a duplicate and not an error")
    void uniqueConstraintRejectionIsANoOp() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenThrow(new DataIntegrityViolationException("idx_subscription_one_active_per_customer"));

        // The database backstop firing means someone else won. The user is fine; we must not
        // retry, must not throw into resolveUser, and must not report a creation.
        assertThat(provisioner.provisionIfMissing(user)).isEmpty();
        verify(quotaSyncer, never()).syncAfterCommit(anyLong(), any(Plan.class));
    }

    @Test
    @DisplayName("creates nothing when the FREE plan is missing from the catalog")
    void noopWhenFreePlanMissing() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.empty());

        assertThat(provisioner.provisionIfMissing(user)).isEmpty();
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    @DisplayName("creates the billing customer when the user has none, then provisions")
    void createsBillingCustomerWhenAbsent() {
        when(billingCustomerRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        when(billingCustomerRepository.save(any(BillingCustomer.class))).thenReturn(billingCustomer);
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> withId(inv.getArgument(0), 501L));

        assertThat(provisioner.provisionIfMissing(user)).contains(501L);
    }

    @Test
    @DisplayName("losing the billing-customer insert race re-reads the winner's row instead of failing")
    void losingTheBillingCustomerRaceRereads() {
        when(billingCustomerRepository.findByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.empty())          // nothing yet
                .thenReturn(Optional.of(billingCustomer)); // the winner's row, after our insert lost
        when(billingCustomerRepository.save(any(BillingCustomer.class)))
                .thenThrow(new DataIntegrityViolationException("idx_bc_user"));
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> withId(inv.getArgument(0), 502L));

        assertThat(provisioner.provisionIfMissing(user)).contains(502L);
    }

    /** A persisted subscription always comes back with an id; the fixtures must say so. */
    private static Subscription withId(Subscription sub, Long id) {
        sub.setId(id);
        return sub;
    }

    @Test
    @DisplayName("a user with no id is refused without touching the database")
    void refusesUnsavedUser() {
        assertThat(provisioner.provisionIfMissing(new User())).isEmpty();
        assertThat(provisioner.provisionIfMissing(null)).isEmpty();
        verifyNoInteractions(billingCustomerRepository, subscriptionRepository, planRepository);
    }

}
