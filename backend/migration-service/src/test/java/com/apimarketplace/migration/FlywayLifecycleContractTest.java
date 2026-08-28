package com.apimarketplace.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the complete packaged migration inventory through the same Flyway lifecycle used by a
 * deployment. Text inspection cannot prove ordering, checksums, PostgreSQL compatibility or
 * restart idempotency; this contract intentionally executes every migration on real PostgreSQL.
 */
class FlywayLifecycleContractTest {

    private static final int EXPECTED_VERSIONED_MIGRATIONS = 440; // 439 SQL + V151 Java
    private static final String EXPECTED_CURRENT_VERSION = "453.3";

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void cleanMigrateValidateInfoAndSecondMigrateAreDeterministic() {
        FlywayTestSupport.assumeDockerAvailable();
        DockerImageName postgresWithVector = DockerImageName
                .parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(postgresWithVector)) {
            postgres.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .cleanDisabled(false)
                    .load();

            flyway.clean();
            var first = flyway.migrate();
            assertThat(first.success).isTrue();
            assertThat(first.migrationsExecuted).isEqualTo(EXPECTED_VERSIONED_MIGRATIONS);

            var validation = flyway.validateWithResult();
            assertThat(validation.validationSuccessful)
                    .withFailMessage(() -> "Flyway validation failed: " + validation.errorDetails)
                    .isTrue();

            MigrationInfo[] applied = flyway.info().applied();
            assertThat(applied).hasSize(EXPECTED_VERSIONED_MIGRATIONS);
            assertThat(flyway.info().current().getVersion().toString())
                    .isEqualTo(EXPECTED_CURRENT_VERSION);

            Set<String> versions = new LinkedHashSet<>();
            Set<String> migrationsWithoutChecksum = new LinkedHashSet<>();
            Arrays.stream(applied).forEach(info -> {
                assertThat(info.getVersion())
                        .as("every packaged migration is versioned: %s", info.getDescription())
                        .isNotNull();
                String version = info.getVersion().toString();
                assertThat(versions.add(version))
                        .as("migration version is unique: %s", info.getVersion())
                        .isTrue();
                if (info.getChecksum() == null) {
                    migrationsWithoutChecksum.add(version);
                }
            });
            assertThat(migrationsWithoutChecksum)
                    .as("all SQL migrations have checksums; Flyway's BaseJavaMigration V151 does not")
                    .containsExactly("151");

            var second = flyway.migrate();
            assertThat(second.success).isTrue();
            assertThat(second.migrationsExecuted).isZero();
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        }
    }
}
