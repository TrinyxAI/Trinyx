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
 * Real-Postgres regression for {@code V437__chinese_models_full_catalog.sql}.
 *
 * <p>V437 does four things that only SQL can get wrong: it seeds the MiniMax +
 * tiered-priced Qwen rows without clobbering admin edits, fills the two NULL
 * prices on {@code zai/glm-5-turbo}, backfills category rows for the orphans the
 * sync created after V388, and mirrors every rate into {@code auth.model_pricing}
 * (the only table the billing path reads). A wrong predicate in any of those
 * ships silently - the catalog just looks slightly different.
 *
 * <p>Runs the file the way the real runner does: {@code beforeEachMigrate.sql}
 * resets {@code search_path TO orchestrator, public} on the same connection
 * immediately before each migration, so an unqualified reference would hit the
 * wrong schema. The orchestrator decoy table proves the {@code SET search_path
 * TO agent} is load-bearing.
 */
// Named *Test, not *IT, deliberately: this module runs surefire with default
// includes and has no failsafe plugin, so an *IT class is never executed.
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V437 Chinese model catalog - real Postgres, real migration search_path")
class ChineseModelsV437MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "V437__chinese_models_full_catalog.sql";

    static JdbcTemplate jdbc;
    static String v437;

    @BeforeAll
    static void setUpClass() {
        v437 = loadMigration(MIGRATION);
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v437 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute("CREATE SCHEMA auth");

        jdbc.execute(overridesDdl("agent"));
        // Decoy: if V437 loses its `SET search_path TO agent`, it writes here
        // instead and every assertion below still sees plausible agent rows.
        jdbc.execute(overridesDdl("orchestrator"));

        jdbc.execute("""
                CREATE TABLE agent.model_category_settings (
                    model_config_id BIGINT      NOT NULL REFERENCES agent.model_config_overrides(id) ON DELETE CASCADE,
                    category        VARCHAR(32) NOT NULL,
                    rank            INTEGER,
                    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
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

        // Same-connection sequence Flyway executes.
        jdbc.execute(beforeEach + "\n" + v437);
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
                    bundle_version          BIGINT,
                    mode                    TEXT,
                    tier                    VARCHAR(20),
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
                    deprecated_at           TIMESTAMPTZ,
                    last_synced_at          TIMESTAMPTZ,
                    is_custom               BOOLEAN     NOT NULL DEFAULT FALSE,
                    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE (provider, model_id)
                )""";
    }

    /** The prod shapes V437 has to cope with, in both schemas. */
    private static void seedPreExistingRows() {
        for (String schema : List.of("agent", "orchestrator")) {
            jdbc.update("INSERT INTO " + schema + ".model_config_overrides "
                    + "(provider, model_id, display_name, enabled, source, mode, "
                    + " price_input, price_output, deprecated_at) VALUES "
                    // Already present with an admin price: the seed must NOT clobber it.
                    + "('qwen','qwen3-max','My Qwen3 Max',TRUE,'manual','chat',9.99,99.99,NULL),"
                    // The unpriced Z.AI row V437 repairs.
                    + "('zai','glm-5-turbo','Glm 5 Turbo',NULL,'manual','chat',NULL,NULL,NULL),"
                    // A priced Z.AI row: the repair must leave it alone.
                    + "('zai','glm-4.7','glm-4.7',NULL,'litellm','chat',0.60,2.20,NULL),"
                    // Orphaned chat row (the post-V388 sync inserts).
                    + "('moonshot','kimi-k2-thinking','kimi-k2-thinking',NULL,'litellm','chat',0.60,2.50,NULL),"
                    // Orphaned chat row with mode NULL - same default treatment.
                    + "('qwen','qwq-plus','qwq-plus',NULL,'litellm',NULL,0.80,2.40,NULL),"
                    // Orphan but DEPRECATED: must stay orphaned, it is EOL.
                    + "('moonshot','moonshot-v1-8k-0430','moonshot-v1-8k-0430',NULL,'litellm','chat',0.20,2.00,NOW()),"
                    // Orphan image row -> image_generation, not chat.
                    + "('qwen','qwen-image-2.0','qwen-image-2.0',NULL,'litellm','image',0.10,0.10,NULL),"
                    // Already categorised: the backfill must not add a second category.
                    + "('deepseek','deepseek-chat','deepseek-chat',TRUE,'litellm','chat',0.28,0.42,NULL)");
        }
        jdbc.update("INSERT INTO agent.model_category_settings (model_config_id, category, enabled) "
                + "SELECT id, 'chat', TRUE FROM agent.model_config_overrides "
                + "WHERE provider = 'deepseek' AND model_id = 'deepseek-chat'");
    }

    // ── Seed ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The 6 MiniMax rows land enabled, priced and chat-mode")
    void seedsMiniMax() {
        assertThat(modelIds("minimax")).containsExactlyInAnyOrder(
                "MiniMax-M3", "MiniMax-M2.5", "MiniMax-M2.5-lightning",
                "MiniMax-M2.1", "MiniMax-M2.1-lightning", "MiniMax-M2");

        assertThat(bool("minimax", "MiniMax-M3", "enabled")).isTrue();
        assertThat(num("minimax", "MiniMax-M3", "price_input")).isEqualByComparingTo("0.30");
        assertThat(num("minimax", "MiniMax-M3", "price_output")).isEqualByComparingTo("1.20");
        assertThat(str("minimax", "MiniMax-M3", "mode")).isEqualTo("chat");
        // M3 is the only vision model of the family - a copy/paste over the
        // whole block would silently claim vision on all six.
        assertThat(bool("minimax", "MiniMax-M3", "supports_vision")).isTrue();
        assertThat(bool("minimax", "MiniMax-M2.5", "supports_vision")).isFalse();
        // The lightning variants cost double on output, hence a different tier.
        assertThat(num("minimax", "MiniMax-M2.5-lightning", "price_output"))
                .isEqualByComparingTo("2.40");
    }

    @Test
    @DisplayName("The tiered-priced Qwen flagships land at their BASE bracket price")
    void seedsQwenFlagshipsAtBaseBracket() {
        assertThat(modelIds("qwen")).contains(
                "qwen3-max", "qwen3-coder-plus", "qwen3-coder-flash",
                "qwen3.7-plus", "qwen3.5-plus", "qwen3-vl-plus", "qwen-flash");

        // 1.00/5.00 is the [0, 32k] bracket; the feed's top bracket is 6.00/60.00.
        assertThat(num("qwen", "qwen3-coder-plus", "price_input")).isEqualByComparingTo("1.00");
        assertThat(num("qwen", "qwen3-coder-plus", "price_output")).isEqualByComparingTo("5.00");
        assertThat(num("qwen", "qwen3-coder-plus", "price_cache_read")).isEqualByComparingTo("0.10");
        assertThat(bool("qwen", "qwen3-vl-plus", "supports_vision")).isTrue();
    }

    @Test
    @DisplayName("An existing row keeps its admin price - the seed is insert-only")
    void doesNotClobberExistingRows() {
        assertThat(num("qwen", "qwen3-max", "price_input"))
                .as("ON CONFLICT DO NOTHING must leave the admin's 9.99 alone")
                .isEqualByComparingTo("9.99");
        assertThat(str("qwen", "qwen3-max", "display_name")).isEqualTo("My Qwen3 Max");
    }

    // ── glm-5-turbo repair ──────────────────────────────────────────────────

    @Test
    @DisplayName("glm-5-turbo gets its list price; an already-priced GLM row is untouched")
    void fillsOnlyTheNullPrices() {
        assertThat(num("zai", "glm-5-turbo", "price_input")).isEqualByComparingTo("1.20");
        assertThat(num("zai", "glm-5-turbo", "price_output")).isEqualByComparingTo("4.00");
        assertThat(str("zai", "glm-5-turbo", "tier")).isEqualTo("mid");

        assertThat(num("zai", "glm-4.7", "price_input")).isEqualByComparingTo("0.60");
        assertThat(num("zai", "glm-4.7", "price_output")).isEqualByComparingTo("2.20");
    }

    // ── Category backfill ───────────────────────────────────────────────────

    @Test
    @DisplayName("Orphaned chat rows get chat + browser_agent; mode NULL counts as chat")
    void backfillsChatOrphans() {
        assertThat(categories("moonshot", "kimi-k2-thinking"))
                .containsExactlyInAnyOrder("browser_agent", "chat");
        assertThat(categories("qwen", "qwq-plus"))
                .containsExactlyInAnyOrder("browser_agent", "chat");
        // The rows the migration itself inserted must be selectable too.
        assertThat(categories("minimax", "MiniMax-M3"))
                .containsExactlyInAnyOrder("browser_agent", "chat");
        assertThat(categories("qwen", "qwen3-max"))
                .containsExactlyInAnyOrder("browser_agent", "chat");
    }

    @Test
    @DisplayName("Image rows get image_generation only - never the chat pair")
    void backfillsImageOrphansSeparately() {
        assertThat(categories("qwen", "qwen-image-2.0")).containsExactly("image_generation");
    }

    @Test
    @DisplayName("A deprecated orphan stays orphaned - the picker must not advertise EOL models")
    void skipsDeprecatedOrphans() {
        assertThat(categories("moonshot", "moonshot-v1-8k-0430")).isEmpty();
    }

    @Test
    @DisplayName("A row that already had one category keeps exactly that one")
    void doesNotTouchRowsThatAlreadyHaveASidecar() {
        assertThat(categories("deepseek", "deepseek-chat"))
                .as("partial sidecars are an admin decision, not an orphan")
                .containsExactly("chat");
    }

    // ── Billing mirror ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Every seeded rate is mirrored into auth.model_pricing, the table billing reads")
    void mirrorsPricingIntoAuthSchema() {
        assertThat(pricingKeys()).contains(
                "minimax/MiniMax-M3", "minimax/MiniMax-M2",
                "qwen/qwen3-max", "qwen/qwen-flash", "zai/glm-5-turbo");

        BigDecimal input = jdbc.queryForObject(
                "SELECT input_rate FROM auth.model_pricing "
                + "WHERE provider = 'qwen' AND model = 'qwen3-max'", BigDecimal.class);
        assertThat(input).isEqualByComparingTo("1.20");
    }

    // ── Invariants ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Runs against the agent schema despite beforeEachMigrate resetting search_path to orchestrator")
    void targetsTheAgentSchemaNotOrchestrator() {
        Integer decoyMinimax = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orchestrator.model_config_overrides WHERE provider = 'minimax'",
                Integer.class);
        assertThat(decoyMinimax)
                .as("the decoy table proves the SET search_path TO agent is load-bearing")
                .isZero();
        Integer decoyTurbo = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orchestrator.model_config_overrides "
                + "WHERE provider = 'zai' AND model_id = 'glm-5-turbo' AND price_input IS NOT NULL",
                Integer.class);
        assertThat(decoyTurbo).isZero();
    }

    @Test
    @DisplayName("Re-running V437 is a no-op - no duplicate rows, no duplicate categories")
    void rerunIsIdempotent() {
        int modelsBefore = count("SELECT COUNT(*) FROM agent.model_config_overrides");
        int categoriesBefore = count("SELECT COUNT(*) FROM agent.model_category_settings");
        int pricingBefore = count("SELECT COUNT(*) FROM auth.model_pricing");

        jdbc.execute(v437); // must not throw

        assertThat(count("SELECT COUNT(*) FROM agent.model_config_overrides")).isEqualTo(modelsBefore);
        assertThat(count("SELECT COUNT(*) FROM agent.model_category_settings")).isEqualTo(categoriesBefore);
        assertThat(count("SELECT COUNT(*) FROM auth.model_pricing")).isEqualTo(pricingBefore);
        // And the admin's price survives a second pass too.
        assertThat(num("qwen", "qwen3-max", "price_input")).isEqualByComparingTo("9.99");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<String> modelIds(String provider) {
        return jdbc.queryForList(
                "SELECT model_id FROM agent.model_config_overrides WHERE provider = ? ORDER BY 1",
                String.class, provider);
    }

    private static List<String> categories(String provider, String modelId) {
        return jdbc.queryForList(
                "SELECT mcs.category FROM agent.model_category_settings mcs "
                + "JOIN agent.model_config_overrides mco ON mco.id = mcs.model_config_id "
                + "WHERE mco.provider = ? AND mco.model_id = ? ORDER BY 1",
                String.class, provider, modelId);
    }

    private static List<String> pricingKeys() {
        return jdbc.queryForList(
                "SELECT provider || '/' || model FROM auth.model_pricing ORDER BY 1", String.class);
    }

    private static BigDecimal num(String provider, String modelId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM agent.model_config_overrides "
                + "WHERE provider = ? AND model_id = ?", BigDecimal.class, provider, modelId);
    }

    private static String str(String provider, String modelId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM agent.model_config_overrides "
                + "WHERE provider = ? AND model_id = ?", String.class, provider, modelId);
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
