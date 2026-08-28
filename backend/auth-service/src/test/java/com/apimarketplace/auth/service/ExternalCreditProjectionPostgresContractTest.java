package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the production V453.1 DDL and the real Cloud projection writer against PostgreSQL.
 * A permissive JdbcTemplate fake cannot detect NOT NULL, FK, CHECK or state-transition drift.
 */
@EnabledIf("dockerAvailable")
class ExternalCreditProjectionPostgresContractTest {

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void cloudProjectionUsesCrossEnvironmentIdentityAndKeepsFailureStateMonotone(
            @TempDir Path migrations) throws Exception {
        Files.writeString(migrations.resolve("V1__auth_prerequisites.sql"), """
                CREATE SCHEMA IF NOT EXISTS auth;
                CREATE EXTENSION IF NOT EXISTS pgcrypto;
                CREATE TABLE auth.users (id BIGINT PRIMARY KEY);
                CREATE TABLE auth.subscription (
                    id BIGINT PRIMARY KEY,
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
                    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now()
                );
                """);
        Files.copy(productionMigration(), migrations.resolve(
                "V453_1__external_billing_authority.sql"));

        try (PostgreSQLContainer<?> postgres =
                     new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                            postgres.getPassword())
                    .locations("filesystem:" + migrations.toAbsolutePath())
                    .schemas("auth")
                    .defaultSchema("auth")
                    .load()
                    .migrate();

            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            ExternalCreditProxyStateWriter writer =
                    new ExternalCreditProxyStateWriter(jdbc, new ObjectMapper());

            UUID operationId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            UUID billingSubjectId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plusSeconds(900);
            var context = new ExternalCreditProxyService.Context(
                    UUID.randomUUID(), billingSubjectId, UUID.randomUUID(), UUID.randomUUID(),
                    "orchestrator-service");
            var command = new ExternalCreditProxyService.ReserveCommand(
                    operationId, "browser", "BROWSER_AGENT_EXECUTION",
                    BigDecimal.ONE, BigDecimal.TEN, "openai", "gpt");
            var response = new CloudCreditAuthorityService.ReserveResponse(
                    operationId, reservationId, "RESERVED", expiresAt,
                    new BigDecimal("99"), false);

            writer.reserved(context, command, 1L, "a".repeat(64), response);

            Map<String, Object> persisted = jdbc.queryForMap("""
                    SELECT billing_subject_id, authority_payer_user_id, origin_service_id, state
                      FROM auth.cloud_credit_operation
                     WHERE operation_id=?
                    """, operationId);
            assertThat(persisted.get("billing_subject_id")).isEqualTo(billingSubjectId);
            assertThat(persisted.get("authority_payer_user_id")).isNull();
            assertThat(persisted.get("origin_service_id"))
                    .isEqualTo("orchestrator-service");
            assertThat(persisted.get("state")).isEqualTo("RESERVED");

            jdbc.update("""
                    UPDATE auth.cloud_credit_operation
                       SET state='SETTLEMENT_FAILED'
                     WHERE operation_id=?
                    """, operationId);
            writer.settled(operationId, "OUTCOME_UNKNOWN", "a".repeat(64),
                    "OUTCOME_UNKNOWN", Map.of("state", "OUTCOME_UNKNOWN"));
            assertThat(state(jdbc, operationId)).isEqualTo("SETTLEMENT_FAILED");

            writer.settled(operationId, "COMMIT", "a".repeat(64),
                    "COMMITTED", Map.of("state", "COMMITTED"));
            assertThat(state(jdbc, operationId)).isEqualTo("COMMITTED");
        }
    }

    private static String state(JdbcTemplate jdbc, UUID operationId) {
        return jdbc.queryForObject("""
                SELECT state FROM auth.cloud_credit_operation WHERE operation_id=?
                """, String.class, operationId);
    }

    private static Path productionMigration() {
        String relative = "migration-service/src/main/resources/db/migration/"
                + "V453_1__external_billing_authority.sql";
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new IllegalStateException(
                "Could not locate production migration " + relative + " from " + here);
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable unavailable) {
            return false;
        }
    }
}
