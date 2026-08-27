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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression for {@code V439__latest_chinese_models.sql}.
 *
 * <p>V439 adds the current Chinese flagships that no feed carries, cloned on the
 * shape of the curated rows already in the catalog. What can silently go wrong
 * is not the INSERT, it is everything around it: a model with no
 * {@code model_category_settings} row is listed in the raw catalog yet skipped
 * by every category-scoped selector, and the ranking backfill must not overrun
 * a value an admin set by hand.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V439 latest Chinese models - real Postgres, real migration search_path")
class LatestChineseModelsV439MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "V439__latest_chinese_models.sql";

    static JdbcTemplate jdbc;
    static String v439;

    @BeforeAll
    static void setUpClass() {
        v439 = loadMigration(MIGRATION);
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v439 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute("CREATE SCHEMA auth");
        jdbc.execute(overridesDdl("agent"));
        jdbc.execute(overridesDdl("orchestrator"));
        jdbc.execute("""
                CREATE TABLE agent.model_category_settings (
                    model_config_id BIGINT      NOT NULL REFERENCES agent.model_config_overrides(id) ON DELETE CASCADE,
                    category        VARCHAR(32) NOT NULL,
                    rank            INTEGER,
                    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (model_config_id, category)
                )""");
        jdbc.execute("""
                CREATE TABLE auth.model_pricing (
                    provider       VARCHAR(50)  NOT NULL,
                    model          VARCHAR(150) NOT NULL,
                    input_rate     NUMERIC(10,6),
                    output_rate    NUMERIC(10,6),
                    fixed_cost     NUMERIC(10,6) NOT NULL DEFAULT 0,
                    effective_from DATE         NOT NULL,
                    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (provider, model, effective_from)
                )""");

        seedPreExistingRows();
        jdbc.execute(beforeEach + "\n" + v439);
    }

    private static String overridesDdl(String schema) {
        return "CREATE TABLE " + schema + ".model_config_overrides " + """
                (
                    id                      BIGSERIAL PRIMARY KEY,
                    provider                VARCHAR(50)  NOT NULL,
                    model_id                VARCHAR(150) NOT NULL,
                    display_name            VARCHAR(255),
                    enabled                 BOOLEAN,
                    source                  VARCHAR(20)  NOT NULL DEFAULT 'manual',
                    provider_kind           VARCHAR(16),
                    mode                    TEXT,
                    tier                    VARCHAR(20),
                    ranking                 INTEGER,
                    price_input             NUMERIC(10,4),
                    price_output            NUMERIC(10,4),
                    price_cache_read        NUMERIC(10,4),
                    price_floor_input       NUMERIC(10,4),
                    price_floor_output      NUMERIC(10,4),
                    context_window          INTEGER,
                    max_output_tokens       INTEGER,
                    supports_tools          BOOLEAN,
                    supports_vision         BOOLEAN,
                    supports_reasoning      BOOLEAN,
                    supports_prompt_caching BOOLEAN,
                    last_synced_at          TIMESTAMPTZ,
                    feed_metadata           JSONB,
                    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE (provider, model_id)
                )""";
    }

    private static void seedPreExistingRows() {
        for (String schema : List.of("agent", "orchestrator")) {
            jdbc.update("INSERT INTO " + schema + ".model_config_overrides "
                    + "(provider, model_id, display_name, source, ranking, price_input, price_output, mode) VALUES "
                    // The reference sibling V439 clones its ranking from.
                    + "('zai','glm-5.1','glm-5.1','manual',65,1.40,4.40,'chat'),"
                    // Already present with an admin price: the insert must not clobber it.
                    + "('zai','glm-5.3','My GLM 5.3','manual',7,9.99,99.99,'chat'),"
                    // V437's MiniMax rows: ranking NULL, to be backfilled.
                    + "('minimax','MiniMax-M3','MiniMax M3','curated',NULL,0.30,1.20,'chat'),"
                    // An admin already ranked this one - the backfill must skip it.
                    + "('minimax','MiniMax-M2','MiniMax M2','curated',12,0.30,1.20,'chat'),"
                    // The half-filled row V437 left behind: priced, but mode NULL.
                    + "('zai','glm-5-turbo','Glm 5 Turbo','manual',69,1.20,4.00,NULL)");
        }
    }

    // ── Inserts ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The 7 flagships land with the curated shape: manual/byok/chat, priced, visible")
    void insertsTheFlagships() {
        assertThat(ids("zai")).contains("glm-5.2", "glm-5v-turbo", "glm-4.6v");
        assertThat(ids("moonshot")).containsExactlyInAnyOrder("kimi-k3", "kimi-k2.7-code");
        assertThat(ids("minimax")).contains("MiniMax-M2.7");

        assertThat(str("zai", "glm-5.2", "source")).isEqualTo("manual");
        assertThat(str("zai", "glm-5.2", "provider_kind")).isEqualTo("byok");
        assertThat(str("zai", "glm-5.2", "mode")).isEqualTo("chat");
        assertThat(num("zai", "glm-5.2", "price_input")).isEqualByComparingTo("0.9660");
        assertThat(num("zai", "glm-5.2", "price_output")).isEqualByComparingTo("3.0360");
        // enabled stays NULL: "no explicit decision", which the catalog treats
        // as visible. Forcing TRUE would override an admin's future choice.
        assertThat(bool("zai", "glm-5.2", "enabled")).isNull();
    }

    @Test
    @DisplayName("Vision and tier follow the model, not a copy-paste of the block")
    void perModelCapabilitiesAreDistinct() {
        assertThat(bool("zai", "glm-5v-turbo", "supports_vision")).isTrue();
        assertThat(bool("zai", "glm-5.2", "supports_vision")).isFalse();
        // classifyTier on the output price: 15.00 -> top, 0.90 -> budget.
        assertThat(str("moonshot", "kimi-k3", "tier")).isEqualTo("top");
        assertThat(str("zai", "glm-4.6v", "tier")).isEqualTo("budget");
    }

    @Test
    @DisplayName("Each price records the OpenRouter entry it came from")
    void stampsPriceProvenance() {
        // glm-5.3 pre-exists in this fixture (the no-clobber case), so read a
        // row the migration actually inserted.
        assertThat(str("zai", "glm-5v-turbo", "feed_metadata"))
                .contains("openrouter")
                .contains("z-ai/glm-5v-turbo");
        // The one row whose OpenRouter rate is knowingly below the vendor's
        // carries that caveat with it rather than only in the migration file.
        assertThat(str("zai", "glm-5.2", "feed_metadata"))
                .contains("Z.AI direct list is 1.40/4.40");
    }

    @Test
    @DisplayName("An existing row keeps its admin price - the insert is insert-only")
    void doesNotClobberExistingRows() {
        assertThat(num("zai", "glm-5.3", "price_input")).isEqualByComparingTo("9.99");
        assertThat(str("zai", "glm-5.3", "display_name")).isEqualTo("My GLM 5.3");
    }

    // ── Category sidecars ───────────────────────────────────────────────────

    @Test
    @DisplayName("Every inserted model gets chat + browser_agent, or it stays invisible to the pickers")
    void createsCategorySidecars() {
        assertThat(categories("zai", "glm-5.2"))
                .containsExactlyInAnyOrder("browser_agent", "chat");
        assertThat(categories("moonshot", "kimi-k3"))
                .containsExactlyInAnyOrder("browser_agent", "chat");
        assertThat(categories("minimax", "MiniMax-M2.7"))
                .containsExactlyInAnyOrder("browser_agent", "chat");
    }

    @Test
    @DisplayName("Z.AI rows carry their sibling's explicit rank; Moonshot and MiniMax inherit the global one")
    void ranksMirrorTheSiblings() {
        assertThat(rank("zai", "glm-5.2", "chat")).isEqualTo(26);
        assertThat(rank("zai", "glm-5v-turbo", "chat")).isEqualTo(28);
        assertThat(rank("moonshot", "kimi-k3", "chat")).isNull();
    }

    // ── Backfills ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("MiniMax rows left rankless by V437 get one; an admin-ranked row is not overrun")
    void backfillsOnlyNullRankings() {
        assertThat(intOf("minimax", "MiniMax-M3", "ranking")).isEqualTo(531);
        assertThat(intOf("minimax", "MiniMax-M2", "ranking"))
                .as("an admin had ranked this one at 12 - the backfill must skip it")
                .isEqualTo(12);
    }

    @Test
    @DisplayName("glm-5-turbo's half-filled row is completed, not overwritten")
    void repairsTheHalfFilledRow() {
        assertThat(str("zai", "glm-5-turbo", "mode")).isEqualTo("chat");
        assertThat(intOf("zai", "glm-5-turbo", "context_window")).isEqualTo(200000);
        assertThat(num("zai", "glm-5-turbo", "price_input"))
                .as("prices were already right, they must not move")
                .isEqualByComparingTo("1.20");
    }

    // ── Invariants ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Prices are mirrored into auth.model_pricing, the table billing reads")
    void mirrorsPricing() {
        BigDecimal rate = jdbc.queryForObject(
                "SELECT output_rate FROM auth.model_pricing WHERE provider='moonshot' AND model='kimi-k3'",
                BigDecimal.class);
        assertThat(rate).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("Runs against the agent schema despite beforeEachMigrate pointing at orchestrator")
    void targetsTheAgentSchema() {
        Integer decoy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orchestrator.model_config_overrides WHERE model_id = 'kimi-k3'",
                Integer.class);
        assertThat(decoy).isZero();
    }

    @Test
    @DisplayName("Re-running is a no-op - no duplicate rows, no duplicate categories")
    void rerunIsIdempotent() {
        int models = count("SELECT COUNT(*) FROM agent.model_config_overrides");
        int cats = count("SELECT COUNT(*) FROM agent.model_category_settings");

        jdbc.execute(v439);

        assertThat(count("SELECT COUNT(*) FROM agent.model_config_overrides")).isEqualTo(models);
        assertThat(count("SELECT COUNT(*) FROM agent.model_category_settings")).isEqualTo(cats);
        assertThat(num("zai", "glm-5.3", "price_input")).isEqualByComparingTo("9.99");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<String> ids(String provider) {
        return jdbc.queryForList(
                "SELECT model_id FROM agent.model_config_overrides WHERE provider = ? ORDER BY 1",
                String.class, provider);
    }

    private static List<String> categories(String provider, String modelId) {
        return jdbc.queryForList(
                "SELECT c.category FROM agent.model_category_settings c "
                + "JOIN agent.model_config_overrides m ON m.id = c.model_config_id "
                + "WHERE m.provider = ? AND m.model_id = ? ORDER BY 1",
                String.class, provider, modelId);
    }

    private static Integer rank(String provider, String modelId, String category) {
        return jdbc.queryForObject(
                "SELECT c.rank FROM agent.model_category_settings c "
                + "JOIN agent.model_config_overrides m ON m.id = c.model_config_id "
                + "WHERE m.provider = ? AND m.model_id = ? AND c.category = ?",
                Integer.class, provider, modelId, category);
    }

    private static BigDecimal num(String provider, String modelId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM agent.model_config_overrides "
                + "WHERE provider = ? AND model_id = ?", BigDecimal.class, provider, modelId);
    }

    private static String str(String provider, String modelId, String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM agent.model_config_overrides "
                + "WHERE provider = ? AND model_id = ?", String.class, provider, modelId);
    }

    private static Integer intOf(String provider, String modelId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM agent.model_config_overrides "
                + "WHERE provider = ? AND model_id = ?", Integer.class, provider, modelId);
    }

    private static Boolean bool(String provider, String modelId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM agent.model_config_overrides "
                + "WHERE provider = ? AND model_id = ?", Boolean.class, provider, modelId);
    }

    private static int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? -1 : n;
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
