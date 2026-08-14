package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.FreeSubscriptionProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The duplicate-subscription race, on a real database with real threads.
 *
 * <p>This is the only way to test it. The defect was a check-then-act with no lock and no
 * constraint between the two halves, reached from {@code resolveUser} - which runs on every
 * gateway request, so on a first login several concurrent requests each read "no subscription"
 * and each inserted one. Production carries three such pairs (subs 18/19, 32/33, 38/39), created
 * 7-15 ms apart, one funded and one a permanent zero-balance ghost. A mocked test cannot express
 * "two transactions raced": the interleaving IS the bug.
 *
 * <p>Note the fix is a DATABASE lock on the user's billing-customer row, not a JVM lock, because
 * prod runs two auth replicas and the racers can be on different pods.
 */
@SpringBootTest
@DisplayName("FREE subscription provisioning is race-free (real Postgres, real threads)")
class FreeSubscriptionProvisioningRaceTest extends AuthPostgresIntegrationTest {


    @Autowired private FreeSubscriptionProvisioner provisioner;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private CreditLedgerRepository ledgerRepository;

    private static final int RACERS = 8;

    @BeforeEach
    void reset() {
        ledgerRepository.deleteAll();
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();

        Plan free = new Plan();
        free.setCode("FREE");
        free.setName("Free");
        free.setIncludedLlmTokens(1000L);
        planRepository.save(free);
    }

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setUsername(email);
        return userRepository.save(u);
    }

    /**
     * Seeds the user AND its billing customer, which is the state production was actually in:
     * both duplicate subscriptions of every affected pair share one billing_customer row.
     *
     * <p>This matters, and getting it wrong hides the bug. If the racers also have to create the
     * billing customer, the unique index on {@code billing_customer.user_id} rejects all but one
     * of them, their transactions are marked rollback-only, and exactly one subscription appears
     * even with no lock at all. The test would pass against the broken code. The race that bit
     * production happens when the billing customer already exists and every racer sails past it.
     */
    private User newUserWithBillingCustomer(String email) {
        User u = newUser(email);
        billingCustomerRepository.save(new com.apimarketplace.auth.domain.BillingCustomer(u, "internal"));
        return u;
    }

    @Test
    @DisplayName("eight simultaneous first-login resolutions produce exactly ONE subscription")
    void concurrentProvisioningCreatesExactlyOne() throws Exception {
        User user = newUserWithBillingCustomer("race@test.local");

        // All racers released at the same instant, which is what a cold gateway cache does on a
        // first login: the app's boot requests all resolve the same user at once.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(RACERS);
        AtomicInteger created = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            for (int i = 0; i < RACERS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        provisioner.provisionIfMissing(user).ifPresent(id -> created.incrementAndGet());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("all racers finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<Subscription> all = subscriptionRepository.findAll();
        assertThat(all)
                .as("pre-fix this produced one row per racer that won the check-then-act window")
                .hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo("active");
        assertThat(all.get(0).getProvider()).isEqualTo("internal");
        // Exactly one racer may claim the creation - the others must report "nothing to do",
        // otherwise a caller could act twice on a single provisioning (e.g. grant credits twice).
        assertThat(created.get()).isEqualTo(1);
        // And a single billing customer, since it is the row the mutex is taken on.
        assertThat(billingCustomerRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a later resolution of an already-provisioned user is a no-op")
    void secondCallIsANoOp() {
        User user = newUser("noop@test.local");

        assertThat(provisioner.provisionIfMissing(user)).isPresent();
        assertThat(provisioner.provisionIfMissing(user)).isEmpty();

        assertThat(subscriptionRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("two different users provisioning at once do not block or collide")
    void differentUsersAreIndependent() throws Exception {
        User a = newUserWithBillingCustomer("indep-a@test.local");
        User b = newUserWithBillingCustomer("indep-b@test.local");

        // The mutex is per billing customer, so it must not serialise unrelated users into one
        // another - a global lock would pass the race test above and quietly throttle every login.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (User u : List.of(a, b)) {
                pool.submit(() -> {
                    try {
                        start.await();
                        provisioner.provisionIfMissing(u);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(subscriptionRepository.findAll()).hasSize(2);
    }
}
