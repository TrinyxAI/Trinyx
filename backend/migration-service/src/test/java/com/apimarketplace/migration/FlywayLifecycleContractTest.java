package com.apimarketplace.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the complete packaged migration inventory through the same Flyway lifecycle used by a
 * deployment. Text inspection cannot prove ordering, checksums, PostgreSQL compatibility or
 * restart idempotency; this contract intentionally executes every migration on real PostgreSQL.
 */
class FlywayLifecycleContractTest {

    private static final int EXPECTED_VERSIONED_HISTORY_ENTRIES = 441; // baseline V0 + 439 SQL + V151 Java
    private static final String EXPECTED_CURRENT_VERSION = "453.3";

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void cleanMigrateValidateInfoAndSecondMigrateAreDeterministic() {
        FlywayTestSupport.assumeDockerAvailable();
        DockerImageName postgresWithVector = DockerImageName
                .parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(postgresWithVector)) {
            postgres.start();
            Flyway flyway = configured(postgres, null);

            flyway.clean();
            var first = flyway.migrate();
            assertThat(first.success).isTrue();
            assertThat(first.migrationsExecuted).isEqualTo(EXPECTED_VERSIONED_HISTORY_ENTRIES);

            var validation = flyway.validateWithResult();
            assertThat(validation.validationSuccessful)
                    .withFailMessage(() -> "Flyway validation failed: " + validation.errorDetails)
                    .isTrue();

            MigrationInfo[] applied = flyway.info().applied();
            List<MigrationInfo> versionedApplied = Arrays.stream(applied)
                    .filter(info -> info.getVersion() != null)
                    .toList();
            long schemaCreationMarkers = Arrays.stream(applied)
                    .filter(info -> info.getVersion() == null)
                    .count();
            assertThat(versionedApplied).hasSize(EXPECTED_VERSIONED_HISTORY_ENTRIES);
            assertThat(schemaCreationMarkers)
                    .as("Flyway records exactly one non-versioned schema-creation marker")
                    .isEqualTo(1);
            assertThat(flyway.info().current().getVersion().toString())
                    .isEqualTo(EXPECTED_CURRENT_VERSION);

            Set<String> versions = new LinkedHashSet<>();
            Set<String> migrationsWithoutChecksum = new LinkedHashSet<>();
            versionedApplied.forEach(info -> {
                String version = info.getVersion().toString();
                assertThat(versions.add(version))
                        .as("migration version is unique: %s", info.getVersion())
                        .isTrue();
                if (info.getChecksum() == null) {
                    migrationsWithoutChecksum.add(version);
                }
            });
            assertThat(migrationsWithoutChecksum)
                    .as("all SQL history entries have checksums; BaseJavaMigration V151 does not")
                    .containsExactly("151");

            var second = flyway.migrate();
            assertThat(second.success).isTrue();
            assertThat(second.migrationsExecuted).isZero();
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

            // Published pre-sync databases stop at V434. Replaying to that exact point and then
            // applying the synchronized LiveContext + Trinyx tail proves an upgrade, not only a
            // clean install. PR-only V435+ Trinyx migrations were never published.
            flyway.clean();
            Flyway preSync = configured(postgres, "434");
            assertThat(preSync.migrate().success).isTrue();
            assertThat(preSync.info().current().getVersion().toString()).isEqualTo("434");
            assertThat(preSync.validateWithResult().validationSuccessful).isTrue();

            Flyway upgrade = configured(postgres, null);
            var upgraded = upgrade.migrate();
            assertThat(upgraded.success).isTrue();
            assertThat(upgraded.migrationsExecuted).isPositive();
            assertThat(upgrade.info().current().getVersion().toString())
                    .isEqualTo(EXPECTED_CURRENT_VERSION);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        }
    }

    private static Flyway configured(
            PostgreSQLContainer<?> postgres, String targetVersion) {
        String jdbcUrl = postgres.getJdbcUrl()
                + "?currentSchema=orchestrator"
                + "&options=-c%20lc.migration.source_timezone%3DUTC";
        FluentConfiguration configuration = Flyway.configure()
                // Mirror the migration service's production-critical Flyway settings.
                .configuration(Map.ofEntries(
                        Map.entry("flyway.postgresql.transactional.lock", "false"),
                        Map.entry("flyway.baselineOnMigrate", "true"),
                        Map.entry("flyway.baselineVersion", "0"),
                        Map.entry("flyway.outOfOrder", "true"),
                        Map.entry("flyway.mixed", "true"),
                        Map.entry("flyway.schemas",
                                "orchestrator,storage,agent,trigger,interface,publication,"
                                        + "auth,datasource,catalog,conversation"),
                        Map.entry("flyway.defaultSchema", "orchestrator"),
                        Map.entry("flyway.table", "flyway_schema_history_orchestrator")))
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (targetVersion != null) {
            configuration.target(MigrationVersion.fromVersion(targetVersion));
        }
        return configuration.load();
    }
}
