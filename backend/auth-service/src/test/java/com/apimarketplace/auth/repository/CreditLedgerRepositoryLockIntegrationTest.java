package com.apimarketplace.auth.repository;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void pessimisticTopupLockSerializesConcurrentClawbackTransactions() throws Exception {
        insertTopup();

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> {
                try (Connection connection = newConnection()) {
                    connection.setAutoCommit(false);
                    lockTopupRow(connection);
                    firstLocked.countDown();
                    await(releaseFirst);
                    connection.commit();
                }
                return null;
            });
            if (!firstLocked.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("First transaction did not acquire the PAYG row lock");
            }

            var second = executor.submit(() -> {
                try (Connection connection = newConnection()) {
                    connection.setAutoCommit(false);
                    secondStarted.countDown();
                    lockTopupRow(connection);
                    connection.commit();
                }
                return null;
            });
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

    private void insertTopup() throws Exception {
        try (Connection connection = newConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO auth.credit_ledger
                         (user_id, executor_user_id, amount, balance_after, source_type, source_id, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                     """)) {
            statement.setLong(1, 42L);
            statement.setLong(2, 42L);
            statement.setBigDecimal(3, new java.math.BigDecimal("8000"));
            statement.setBigDecimal(4, new java.math.BigDecimal("8000"));
            statement.setString(5, "PAYG_TOPUP");
            statement.setString(6, SESSION_ID);
            statement.executeUpdate();
        }
    }

    private Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private void lockTopupRow(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM auth.credit_ledger WHERE source_id = ? FOR UPDATE")) {
            statement.setString(1, SESSION_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("PAYG top-up row not found");
                }
            }
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
