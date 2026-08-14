package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The bootstrap credit grant, {@code attributeCreditsIfEligible}.
 *
 * <p>It exists to hand a newly verified FREE user their 1,000 plan-included credits, and it runs
 * on EVERY {@code resolveUser}, i.e. every gateway request, relying on sourceId idempotency to be
 * a no-op afterwards. That idempotency is per KEY, which is where it went wrong: an admin comp
 * tier keeps {@code provider='internal'}, so a provider-only guard let it through, and the moment
 * the plan turned STARTER/PRO/TEAM the previously unused {@code pack_sub_N_init} key became
 * eligible and granted the 5K base pack a second time. Four production accounts (subs 14, 15, 20,
 * 21) were over-granted 5,000 credits each this way, seconds after their comp grant.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserResolutionService credit attribution")
class UserResolutionServiceCreditAttributionTest {

    @Mock private UserRepository userRepository;
    @Mock private CreditService creditService;
    @Mock private UsernameValidator usernameValidator;
    @Mock private AgeValidator ageValidator;
    @Mock private OnboardingService onboardingService;
    @Mock private OrganizationService organizationService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private FreeSubscriptionProvisioner freeSubscriptionProvisioner;

    private UserResolutionService service;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        service = new UserResolutionService(
                userRepository, creditService, usernameValidator, ageValidator,
                onboardingService, organizationService, subscriptionRepository,
                billingCustomerRepository, planRepository, creditAttributionService,
                new PlanStorageQuotaSyncer(null, null), freeSubscriptionProvisioner);
    }

    private User verifiedUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmailVerified(true);
        return u;
    }

    private void givenActiveSubscription(String planCode, String provider) {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setCode(planCode);
        plan.setName(planCode);
        Subscription sub = new Subscription();
        sub.setId(7L);
        sub.setPlan(plan);
        sub.setProvider(provider);
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
    }

    @Test
    @DisplayName("grants the FREE plan's bootstrap credits to a verified user")
    void grantsForFreeInternal() {
        givenActiveSubscription("FREE", "internal");

        service.attributeCreditsIfEligible(verifiedUser());

        verify(creditAttributionService).attributeOnSubscription(eq(USER_ID), any(Subscription.class), eq(0));
    }

    @Test
    @DisplayName("does NOT re-grant on an admin comp tier - assignPlan already paid it")
    void doesNotGrantOnCompTier() {
        // provider stays 'internal' on a comp tier, which is exactly why the provider-only
        // guard was not enough. Reaching attributeOnSubscription here releases pack_sub_N_init
        // for a duplicate 5K grant on top of the one assignPlan made.
        givenActiveSubscription("TEAM", "internal");

        service.attributeCreditsIfEligible(verifiedUser());

        verify(creditAttributionService, never())
                .attributeOnSubscription(anyLong(), any(Subscription.class), anyInt());
    }

    @Test
    @DisplayName("does NOT grant on a comp STARTER either")
    void doesNotGrantOnCompStarter() {
        givenActiveSubscription("STARTER", "internal");

        service.attributeCreditsIfEligible(verifiedUser());

        verify(creditAttributionService, never())
                .attributeOnSubscription(anyLong(), any(Subscription.class), anyInt());
    }

    @Test
    @DisplayName("does NOT grant on a paid Stripe subscription - the webhook owns that")
    void doesNotGrantOnStripe() {
        givenActiveSubscription("PRO", "stripe");

        service.attributeCreditsIfEligible(verifiedUser());

        verify(creditAttributionService, never())
                .attributeOnSubscription(anyLong(), any(Subscription.class), anyInt());
    }

    @Test
    @DisplayName("does nothing until the email is verified")
    void doesNothingWhenEmailUnverified() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmailVerified(false);

        service.attributeCreditsIfEligible(u);

        verifyNoInteractions(subscriptionRepository, creditAttributionService);
    }

    @Test
    @DisplayName("does nothing when the user has no active subscription")
    void doesNothingWithoutSubscription() {
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

        service.attributeCreditsIfEligible(verifiedUser());

        verify(creditAttributionService, never())
                .attributeOnSubscription(anyLong(), any(Subscription.class), anyInt());
    }

    @Test
    @DisplayName("does nothing when the subscription carries no plan")
    void doesNothingWithoutPlan() {
        Subscription sub = new Subscription();
        sub.setId(7L);
        sub.setProvider("internal");
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));

        service.attributeCreditsIfEligible(verifiedUser());

        verify(creditAttributionService, never())
                .attributeOnSubscription(anyLong(), any(Subscription.class), anyInt());
    }
}
