package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.default_schema=auth"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CreditLedgerRepositoryLockIntegrationTest {

    private static final String SESSION_ID = "cs_concurrent_refund";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("livecontext")
                    .withUsername("test")
                    .withPassword("test")
                    .withInitScript("db/test/credit-ledger-lock.sql");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CreditLedgerRepository ledgerRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void pessimisticTopupLockSerializesConcurrentClawbackTransactions() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            CreditLedgerEntry topup = new CreditLedgerEntry();
            topup.setUserId(42L);
            topup.setExecutorUserId(42L);
            topup.setAmount(new BigDecimal("8000"));
            topup.setBalanceAfter(new BigDecimal("8000"));
            topup.setSourceType("PAYG_TOPUP");
            topup.setSourceId(SESSION_ID);
            ledgerRepository.saveAndFlush(topup);
        });

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                ledgerRepository.findFirstBySourceIdForUpdate(SESSION_ID).orElseThrow();
                firstLocked.countDown();
                await(releaseFirst);
            }));
            if (!firstLocked.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("First transaction did not acquire the PAYG row lock");
            }

            var second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                secondStarted.countDown();
                ledgerRepository.findFirstBySourceIdForUpdate(SESSION_ID).orElseThrow();
            }));
            if (!secondStarted.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Second transaction did not start");
            }

            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while holding the PAYG row lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding the PAYG row lock", e);
        }
    }
}
