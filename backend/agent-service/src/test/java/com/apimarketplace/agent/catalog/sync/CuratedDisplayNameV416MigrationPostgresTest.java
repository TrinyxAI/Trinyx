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
 * Real-Postgres regression for {@code V416__protect_curated_display_names_from_catalog_sync.sql}.
 *
 * <p>V416 is what stops the widened {@code LiteLlmFeedParser.PROVIDER_MAP}
 * (moonshot / dashscope->qwen / zai) from renaming the five curated rows seeded
 * by V384/V386 - {@code Kimi K2.6}, {@code Qwen Max}, ... - to their raw model
 * ids, because neither feed publishes a display name. The whole fix lives in
 * one UPDATE predicate, so an inverted {@code = ANY} or a dropped bridge clause
 * would ship silently: the unit tests cover the merge MECHANISM, this covers
 * the SQL that arms it.
 *
 * <p>Runs the file the way the real runner does: {@code beforeEachMigrate.sql}
 * resets {@code search_path TO orchestrator, public} on the same connection
 * immediately before each migration, so an unqualified reference would hit the
 * wrong schema (the failure mode that rolled back the V381 release).
 */
// Named *Test, not *IT, deliberately: this module runs surefire with default
// includes (Test*/*Test/*Tests/*TestCase) and has no failsafe plugin, so an
// *IT class is compiled and never executed.
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V416 curated display-name protection - real Postgres, real migration search_path")
class CuratedDisplayNameV416MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION =
            "V416__protect_curated_display_names_from_catalog_sync.sql";

    static JdbcTemplate jdbc;
    static String V416;

    @BeforeAll
    static void setUpClass() {
        // No Docker assumption here: @Testcontainers(disabledWithoutDocker)
        // starts the container BEFORE @BeforeAll, so an assumption at this
        // point would already be unreachable on a Docker-less machine.
        V416 = loadMigration(MIGRATION);
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(V416 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        // The columns V416 touches, with V109's real types/defaults - notably
        // user_modified_fields TEXT[] NOT NULL DEFAULT '{}', which is why the
        // NOT (... = ANY(...)) guard has no NULL trap.
        jdbc.execute("""
                CREATE TABLE agent.model_config_overrides (
                    id                   BIGSERIAL PRIMARY KEY,
                    provider             VARCHAR(50)  NOT NULL,
                    model_id             VARCHAR(150) NOT NULL,
                    display_name         VARCHAR(200),
                    source               VARCHAR(20)  NOT NULL DEFAULT 'manual',
                    user_modified_fields TEXT[]       NOT NULL DEFAULT '{}'
                )""");
        // A decoy table in the schema beforeEachMigrate points at: if V416
        // ever loses its `SET search_path TO agent`, it silently updates this
        // one and every assertion below still sees untouched agent rows.
        jdbc.execute("""
                CREATE TABLE orchestrator.model_config_overrides (
                    id                   BIGSERIAL PRIMARY KEY,
                    provider             VARCHAR(50)  NOT NULL,
                    model_id             VARCHAR(150) NOT NULL,
                    display_name         VARCHAR(200),
                    source               VARCHAR(20)  NOT NULL DEFAULT 'manual',
                    user_modified_fields TEXT[]       NOT NULL DEFAULT '{}'
                )""");

        seed("agent");
        seed("orchestrator");

        // Same-connection sequence Flyway executes.
        jdbc.execute(beforeEach + "\n" + V416);
    }

    /** The rows that actually exist in prod, plus the ones that must NOT move. */
    private static void seed(String schema) {
        jdbc.update("INSERT INTO " + schema + ".model_config_overrides "
                + "(provider, model_id, display_name, source) VALUES "
                // V384/V386: curated + human name -> MUST be protected.
                + "('qwen','qwen-max','Qwen Max','curated'),"
                + "('qwen','qwen-plus','Qwen Plus','curated'),"
                + "('qwen','qwen-turbo','Qwen Turbo','curated'),"
                + "('moonshot','kimi-k2.6','Kimi K2.6','curated'),"
                + "('moonshot','kimi-k2.5','Kimi K2.5','curated'),"
                // V112: curated but display_name == model_id -> nothing to protect.
                + "('zai','glm-5.1','glm-5.1','curated'),"
                + "('anthropic','claude-opus-4-6','claude-opus-4-6','curated'),"
                // Bridges: curated + human name, but BridgeModelDeriver re-derives
                // these every sync -> protecting them would freeze the deriver out.
                + "('claude-code','claude-opus-4-7','Claude Opus 4.7','curated'),"
                + "('codex','gpt-5.4-mini','GPT-5.4 mini','curated'),"
                + "('gemini-cli','gemini-2.5-pro','Gemini 2.5 Pro','curated'),"
                + "('mistral-vibe','devstral-2','Devstral 2','curated'),"
                // Not curated: an admin/feed row is none of V416's business.
                + "('openai','my-ft-model','My Fine Tune','manual'),"
                + "('openrouter','moonshotai/kimi-k2','MoonshotAI: Kimi K2','openrouter')");
    }

    @Test
    @DisplayName("Exactly the 5 curated human-named non-bridge rows get displayName protected")
    void protectsOnlyTheCuratedHumanNamedRows() {
        assertThat(protectedKeys("agent")).containsExactlyInAnyOrder(
                "moonshot/kimi-k2.5", "moonshot/kimi-k2.6",
                "qwen/qwen-max", "qwen/qwen-plus", "qwen/qwen-turbo");
    }

    @Test
    @DisplayName("Bridge rows keep an empty user_modified_fields - BridgeModelDeriver stays in control of their names")
    void bridgeRowsAreLeftAlone() {
        assertThat(protectedKeys("agent"))
                .noneMatch(k -> k.startsWith("claude-code/") || k.startsWith("codex/")
                        || k.startsWith("gemini-cli/") || k.startsWith("mistral-vibe/"));
    }

    @Test
    @DisplayName("Rows whose display_name already equals model_id, and non-curated rows, are untouched")
    void nothingElseIsTouched() {
        assertThat(protectedKeys("agent"))
                .doesNotContain("zai/glm-5.1", "anthropic/claude-opus-4-6",
                        "openai/my-ft-model", "openrouter/moonshotai/kimi-k2");
    }

    @Test
    @DisplayName("Runs against the agent schema despite beforeEachMigrate resetting search_path to orchestrator")
    void targetsTheAgentSchemaNotOrchestrator() {
        assertThat(protectedKeys("orchestrator"))
                .as("the decoy table proves the SET search_path TO agent is load-bearing")
                .isEmpty();
    }

    @Test
    @DisplayName("Re-running V416 is a no-op - array_append cannot duplicate the entry")
    void rerunIsIdempotent() {
        jdbc.execute(V416); // must not throw
        assertThat(protectedKeys("agent")).hasSize(5);
        Integer maxEntries = jdbc.queryForObject(
                "SELECT MAX(array_length(user_modified_fields, 1)) "
                + "FROM agent.model_config_overrides "
                + "WHERE 'displayName' = ANY(user_modified_fields)",
                Integer.class);
        assertThat(maxEntries)
                .as("'displayName' must appear once, not twice")
                .isEqualTo(1);
    }

    private static List<String> protectedKeys(String schema) {
        return jdbc.queryForList(
                "SELECT provider || '/' || model_id FROM " + schema + ".model_config_overrides "
                + "WHERE 'displayName' = ANY(user_modified_fields) ORDER BY 1",
                String.class);
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
