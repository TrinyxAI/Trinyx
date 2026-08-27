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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres regression for
 * {@code V438__model_config_source_discovery.sql}.
 *
 * <p>The migration is one widened CHECK, which is exactly the kind of change
 * that looks too small to test and fails loudly in production: get the value
 * list wrong and EVERY discovery insert dies inside the sync transaction, which
 * surfaces as "sync applied 0 rows" rather than as an error anyone can read.
 * So this pins both directions - the new value is admitted, and the constraint
 * still rejects an unknown one instead of having been dropped outright.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V438 discovery source - real Postgres, real migration search_path")
class DiscoverySourceV438MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "V438__model_config_source_discovery.sql";

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() {
        String v438 = loadMigration(MIGRATION);
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v438 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute("""
                CREATE TABLE agent.model_config_overrides (
                    id       BIGSERIAL PRIMARY KEY,
                    provider VARCHAR(50)  NOT NULL,
                    model_id VARCHAR(150) NOT NULL,
                    source   VARCHAR(20)  NOT NULL DEFAULT 'manual',
                    CONSTRAINT model_config_overrides_source_check
                        CHECK (source IN ('manual','curated','openrouter','litellm','bundle')),
                    UNIQUE (provider, model_id)
                )""");

        // Pre-existing rows under the OLD value set: the widen must not
        // invalidate any of them (a CHECK is validated against existing rows
        // when added, so a typo here fails the migration outright).
        jdbc.update("INSERT INTO agent.model_config_overrides (provider, model_id, source) VALUES "
                + "('zai','glm-5.1','litellm'),"
                + "('qwen','qwen-max','curated'),"
                + "('openrouter','z-ai/glm-5.2','openrouter'),"
                + "('anthropic','claude-opus-4-8','bundle'),"
                + "('openai','my-ft','manual')");

        jdbc.execute(beforeEach + "\n" + v438);
    }

    @Test
    @DisplayName("source='discovery' is admitted - without it every discovered row would be rejected")
    void admitsTheDiscoverySource() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO agent.model_config_overrides (provider, model_id, source) "
                + "VALUES ('zai','glm-5.3','discovery')"))
                .doesNotThrowAnyException();

        String source = jdbc.queryForObject(
                "SELECT source FROM agent.model_config_overrides "
                + "WHERE provider = 'zai' AND model_id = 'glm-5.3'", String.class);
        assertThat(source).isEqualTo("discovery");
    }

    @Test
    @DisplayName("The constraint still rejects an unknown source - it was widened, not dropped")
    void stillRejectsUnknownSources() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO agent.model_config_overrides (provider, model_id, source) "
                + "VALUES ('zai','glm-9','from-a-typo')"))
                .hasMessageContaining("model_config_overrides_source_check");
    }

    @Test
    @DisplayName("Every previously-valid source survives the widen")
    void keepsAllPreExistingSources() {
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT source FROM agent.model_config_overrides ORDER BY 1", String.class))
                .contains("bundle", "curated", "litellm", "manual", "openrouter");
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
