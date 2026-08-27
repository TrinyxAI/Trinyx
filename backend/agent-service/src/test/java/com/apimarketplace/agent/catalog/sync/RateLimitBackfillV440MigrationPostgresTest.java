package com.apimarketplace.agent.catalog.sync;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression for
 * {@code V440__rate_limits_for_migration_seeded_models.sql}.
 *
 * <p>V440 is a targeted backfill, and both halves of "targeted" matter. It must
 * fill the rows that migrations inserted directly (bypassing
 * {@code CatalogMergeService} and therefore its rate-limit fallback), and it
 * must NOT touch the rows whose NULL is deliberate: a model with a curated
 * {@code ai.agent.rate-limits} entry relies on that NULL, because the DB column
 * wins per-field over the YAML seed and a generic 60000 would silently cap it
 * at a fraction of its researched limit.
 *
 * <p>A too-wide WHERE clause here would be invisible in production: nothing
 * errors, the models simply get throttled 8 to 33 times lower than intended.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V440 rate-limit backfill - real Postgres, real migration search_path")
class RateLimitBackfillV440MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "V440__rate_limits_for_migration_seeded_models.sql";

    static JdbcTemplate jdbc;
    static String v440;

    @BeforeAll
    static void setUpClass() {
        v440 = loadMigration(MIGRATION);
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v440 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute(ddl("agent"));
        jdbc.execute(ddl("orchestrator"));

        seed("agent");
        seed("orchestrator");

        jdbc.execute(beforeEach + "\n" + v440);
    }

    private static String ddl(String schema) {
        return "CREATE TABLE " + schema + ".model_config_overrides " + """
                (
                    id                        BIGSERIAL PRIMARY KEY,
                    provider                  VARCHAR(50)  NOT NULL,
                    model_id                  VARCHAR(150) NOT NULL,
                    rate_limit_tpm            INTEGER,
                    rate_limit_rpm            INTEGER,
                    rate_limit_tpm_per_tenant INTEGER,
                    rate_limit_rpm_per_tenant INTEGER,
                    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE (provider, model_id)
                )""";
    }

    private static void seed(String schema) {
        jdbc.update("INSERT INTO " + schema + ".model_config_overrides "
                + "(provider, model_id, rate_limit_tpm, rate_limit_rpm, "
                + " rate_limit_tpm_per_tenant, rate_limit_rpm_per_tenant) VALUES "
                // The 7 that migrations inserted directly: all four NULL.
                + "('zai','glm-5.3',NULL,NULL,NULL,NULL),"
                + "('zai','glm-5.2',NULL,NULL,NULL,NULL),"
                + "('zai','glm-5v-turbo',NULL,NULL,NULL,NULL),"
                + "('zai','glm-4.6v',NULL,NULL,NULL,NULL),"
                + "('moonshot','kimi-k3',NULL,NULL,NULL,NULL),"
                + "('moonshot','kimi-k2.7-code',NULL,NULL,NULL,NULL),"
                + "('minimax','MiniMax-M2.7',NULL,NULL,NULL,NULL),"
                // NULL on purpose - each has a curated YAML entry worth 500k-2M TPM.
                + "('zai','glm-5-turbo',NULL,NULL,NULL,NULL),"
                + "('perplexity','sonar-pro',NULL,NULL,NULL,NULL),"
                + "('mistral','mistral-medium-3',NULL,NULL,NULL,NULL),"
                + "('openrouter','google/gemini-3-pro-preview',NULL,NULL,NULL,NULL),"
                // Already carries the family fallback - must not move.
                + "('zai','glm-5.1',60000,500,20000,200),"
                // An admin raised this one by hand: partially set, so out of scope.
                + "('moonshot','kimi-k2.6',900000,NULL,NULL,NULL)");
    }

    @Test
    @DisplayName("The 7 migration-seeded models get the documented catalog fallback")
    void fillsTheMigrationSeededRows() {
        for (String[] key : new String[][] {
                {"zai", "glm-5.3"}, {"zai", "glm-5.2"}, {"zai", "glm-5v-turbo"}, {"zai", "glm-4.6v"},
                {"moonshot", "kimi-k3"}, {"moonshot", "kimi-k2.7-code"}, {"minimax", "MiniMax-M2.7"}}) {
            assertThat(limits(key[0], key[1]))
                    .as("%s/%s", key[0], key[1])
                    .containsExactly(60000, 500, 20000, 200);
        }
    }

    @Test
    @DisplayName("Rows whose NULL is deliberate keep it - a value here would cap them below their curated limit")
    void leavesYamlCoveredRowsNull() {
        // Each of these resolves through ai.agent.rate-limits to 500k-2M TPM.
        // Writing 60000 into the DB would win per-field and throttle them.
        for (String[] key : new String[][] {
                {"zai", "glm-5-turbo"}, {"perplexity", "sonar-pro"},
                {"mistral", "mistral-medium-3"}, {"openrouter", "google/gemini-3-pro-preview"}}) {
            assertThat(limits(key[0], key[1]))
                    .as("%s/%s must stay NULL", key[0], key[1])
                    .containsExactly(null, null, null, null);
        }
    }

    @Test
    @DisplayName("A partially-set row is out of scope - the guard needs all four NULL")
    void skipsPartiallySetRows() {
        assertThat(limits("moonshot", "kimi-k2.6"))
                .as("an admin's hand-raised 900000 must survive")
                .containsExactly(900000, null, null, null);
    }

    @Test
    @DisplayName("An already-filled row is untouched")
    void leavesFilledRowsAlone() {
        assertThat(limits("zai", "glm-5.1")).containsExactly(60000, 500, 20000, 200);
    }

    @Test
    @DisplayName("Runs against the agent schema despite beforeEachMigrate pointing at orchestrator")
    void targetsTheAgentSchema() {
        Integer decoyFilled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orchestrator.model_config_overrides WHERE rate_limit_tpm IS NOT NULL "
                + "AND model_id <> 'glm-5.1' AND model_id <> 'kimi-k2.6'", Integer.class);
        assertThat(decoyFilled)
                .as("the decoy proves the SET search_path TO agent is load-bearing")
                .isZero();
    }

    @Test
    @DisplayName("Re-running changes nothing")
    void rerunIsIdempotent() {
        jdbc.execute(v440);
        assertThat(limits("zai", "glm-5.3")).containsExactly(60000, 500, 20000, 200);
        assertThat(limits("zai", "glm-5-turbo")).containsExactly(null, null, null, null);
        assertThat(limits("moonshot", "kimi-k2.6")).containsExactly(900000, null, null, null);
    }

    private static List<Integer> limits(String provider, String modelId) {
        return jdbc.queryForObject(
                "SELECT rate_limit_tpm, rate_limit_rpm, rate_limit_tpm_per_tenant, rate_limit_rpm_per_tenant "
                + "FROM agent.model_config_overrides WHERE provider = ? AND model_id = ?",
                (rs, n) -> java.util.Arrays.asList(
                        (Integer) rs.getObject(1), (Integer) rs.getObject(2),
                        (Integer) rs.getObject(3), (Integer) rs.getObject(4)),
                provider, modelId);
    }

    private static String loadMigration(String fileName) {
        String[] candidates = {
                "../migration-service/src/main/resources/db/migration/" + fileName,
                "backend/migration-service/src/main/resources/db/migration/" + fileName,
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p)) {
                try {
                    return Files.readString(p);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
