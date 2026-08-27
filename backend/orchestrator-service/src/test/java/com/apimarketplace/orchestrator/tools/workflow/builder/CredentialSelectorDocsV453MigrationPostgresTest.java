package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression for {@code V453__credential_selector_names_are_discoverable.sql}.
 *
 * <p>The migration rewrites two keys inside a jsonb blob that V442 already wrote, and that
 * shape is the risk: the obvious way to change a description is to overwrite
 * {@code parameters} wholesale, which silently drops the type/required/example siblings and
 * every OTHER mcp parameter with them. Nothing fails at migrate time. The damage surfaces
 * later as an agent reading a node doc that has lost half its fields, which reads like a
 * model problem rather than a migration one. A mocked JdbcTemplate cannot see any of this:
 * it would pin the SQL string, which is exactly the thing that is wrong.
 *
 * <p>So this pins what the targeted {@code jsonb_set} is FOR: the new text lands, the
 * siblings survive, unrelated rows are untouched, and the concepts append does not grow a
 * duplicate when the migration is applied twice (Flyway will not re-run it, but a repair or
 * a hand-replay will, and the guard exists precisely for that).
 *
 * <p><b>How it runs.</b> Over plain JDBC rather than Testcontainers, because the
 * {@code arc-build} CI runners expose no Docker socket: a {@code @Testcontainers} class
 * there does not fail, it SKIPS, which is indistinguishable from having no test while
 * looking like coverage. CI provides a {@code postgres:16-alpine} service container and sets
 * {@code ORCHESTRATOR_TEST_PG_URL}. With {@code CI} unset and no URL the class aborts as
 * skipped (a laptop without a scratch Postgres is not a failure); with {@code CI} set it
 * REFUSES to skip, so deleting the env block or moving the class out of the job that carries
 * the service container breaks the build rather than quietly disabling this file.
 *
 * <p>Note the consequence of refusing to skip: this class sits in
 * {@code com.apimarketplace.orchestrator.tools.**}, one of the six group runs AGENTS.md
 * documents, so running that group with {@code CI} set in the environment and no URL
 * fails here. That is the intended trade (a test that skips in CI is the same as no
 * test); set the URL, or unset {@code CI}, for a local group run.
 *
 * <p>It creates and drops {@code node_type_documentation}, so it refuses to start unless the
 * database name contains {@code test}. Locally:
 * {@code ORCHESTRATOR_TEST_PG_URL=jdbc:postgresql://localhost:5432/lc_orch_test mvn -pl
 * orchestrator-service test -Dtest=CredentialSelectorDocsV453MigrationPostgresTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V453 credential_selector docs - real Postgres, real migration SQL")
class CredentialSelectorDocsV453MigrationPostgresTest {

    private static final String MIGRATION = "V453__credential_selector_names_are_discoverable.sql";

    private static final String URL = System.getenv("ORCHESTRATOR_TEST_PG_URL");
    private static final String USER =
            System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_PASSWORD", "postgres");

    private String migrationSql;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpSchema() {
        requireDatabaseOnCi();

        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(java.util.Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test drops node_type_documentation), got: " + database);
        }

        migrationSql = loadMigration();
        if (migrationSql == null) {
            throw new IllegalStateException(
                    "cannot read " + MIGRATION + " from the module working directory. The test "
                            + "reads the SHIPPED migration on purpose: a copy of the SQL inlined "
                            + "here would pass while the real file was broken.");
        }

        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("DROP TABLE IF EXISTS node_type_documentation");
        jdbc.execute("""
                CREATE TABLE node_type_documentation (
                    type       VARCHAR(100) PRIMARY KEY,
                    parameters JSONB,
                    concepts   JSONB
                )
                """);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE node_type_documentation");
        // 'mcp' as V442 left it: credential_selector present, with the siblings the
        // migration must not disturb, alongside another parameter entirely.
        jdbc.update("INSERT INTO node_type_documentation (type, parameters, concepts) VALUES (?, ?::jsonb, ?::jsonb)",
                "mcp",
                """
                {
                  "credential_selector": {
                    "type": "string",
                    "required": false,
                    "example": "{{trigger.output.account}}",
                    "description": "the old text"
                  },
                  "tool_slug": {"type": "string", "required": true, "description": "untouched"}
                }
                """,
                "[\"an existing concept\"]");
        // A node type that never had the field: the WHERE guard must skip it.
        jdbc.update("INSERT INTO node_type_documentation (type, parameters, concepts) VALUES (?, ?::jsonb, ?::jsonb)",
                "agent", "{\"prompt\": {\"type\": \"string\"}}", "[\"agent concept\"]");
    }

    @Test
    @DisplayName("rewrites credential_selector.description, keeping its type/required siblings and the other parameters")
    void rewritesOnlyTheTargetedKeys() {
        jdbc.execute(migrationSql);

        assertThat(str("SELECT parameters #>> '{credential_selector,description}' FROM node_type_documentation WHERE type = 'mcp'"))
                .as("the description the agent reads")
                .doesNotContain("the old text")
                .contains("get_connected_services")
                .contains("credential(action='list')")
                .contains("FAILS");

        // The siblings are the part a wholesale overwrite would have eaten.
        assertThat(str("SELECT parameters #>> '{credential_selector,type}' FROM node_type_documentation WHERE type = 'mcp'"))
                .isEqualTo("string");
        assertThat(str("SELECT parameters #>> '{credential_selector,required}' FROM node_type_documentation WHERE type = 'mcp'"))
                .isEqualTo("false");
        assertThat(str("SELECT parameters #>> '{tool_slug,description}' FROM node_type_documentation WHERE type = 'mcp'"))
                .as("an unrelated parameter of the same row")
                .isEqualTo("untouched");
    }

    @Test
    @DisplayName("replaces the example that named a trigger form the engine does not resolve")
    void replacesTheUnresolvableExample() {
        // V442's example was the one value in this doc an agent copies verbatim, and it
        // matched neither supported trigger shape. Fail-closed, that is a failed step.
        jdbc.execute(migrationSql);

        assertThat(str("SELECT parameters #>> '{credential_selector,example}' FROM node_type_documentation WHERE type = 'mcp'"))
                .isEqualTo("{{item.ig_account}}");
    }

    @Test
    @DisplayName("states the numeric-name rule as POSITIVE, matching what the resolver actually treats as an id")
    void keepsTheNumericRulePositive() {
        // positiveId() returns null for anything <= 0, so a credential named "0" or "-1"
        // IS reachable by name. Dropping "positive" would document a refusal that the
        // code does not make.
        jdbc.execute(migrationSql);

        assertThat(str("SELECT parameters #>> '{credential_selector,description}' FROM node_type_documentation WHERE type = 'mcp'"))
                .contains("positive whole number");
    }

    @Test
    @DisplayName("appends the discovery concept without dropping the concepts already there")
    void appendsTheDiscoveryConcept() {
        jdbc.execute(migrationSql);

        assertThat(strings("SELECT jsonb_array_elements_text(concepts) FROM node_type_documentation WHERE type = 'mcp'"))
                .hasSize(2)
                .contains("an existing concept")
                .anyMatch(c -> c.contains("get_connected_services") && c.contains("'active'"));
    }

    @Test
    @DisplayName("a second application adds no duplicate concept")
    void isIdempotentOnConcepts() {
        jdbc.execute(migrationSql);
        // A repair or a hand-replay runs it again; the @> guard is what stops the
        // concepts list from growing a copy of the same sentence every time.
        jdbc.execute(migrationSql);

        assertThat(strings("SELECT jsonb_array_elements_text(concepts) FROM node_type_documentation WHERE type = 'mcp'"))
                .hasSize(2);
    }

    @Test
    @DisplayName("a node type without the field is left exactly as it was")
    void leavesUnrelatedNodeTypesAlone() {
        jdbc.execute(migrationSql);

        assertThat(str("SELECT parameters::text FROM node_type_documentation WHERE type = 'agent'"))
                .doesNotContain("credential_selector");
        assertThat(strings("SELECT jsonb_array_elements_text(concepts) FROM node_type_documentation WHERE type = 'agent'"))
                .containsExactly("agent concept");
    }

    private String str(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private List<String> strings(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL is unset on CI. This class must execute there: it is "
                            + "the only thing that runs V453 against a real engine, and a jsonb "
                            + "migration that eats sibling keys fails silently. Restore the env "
                            + "block on the workflow step that runs it, and keep that step in a "
                            + "job carrying the postgres service.");
        }
        Assumptions.abort(
                "no scratch Postgres: set ORCHESTRATOR_TEST_PG_URL to run this locally "
                        + "(CI always sets it)");
    }

    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException(
                        "ORCHESTRATOR_TEST_PG_URL is set but the database is unreachable: " + URL, e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private static String loadMigration() {
        String[] candidates = {
                "../migration-service/src/main/resources/db/migration/" + MIGRATION,
                "backend/migration-service/src/main/resources/db/migration/" + MIGRATION,
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                try {
                    return Files.readString(path);
                } catch (Exception e) {
                    throw new IllegalStateException("unreadable migration: " + path, e);
                }
            }
        }
        return null;
    }
}
