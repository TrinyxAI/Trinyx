package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Applies the REAL V418 and asserts what an agent ends up reading.
 *
 * <p>Two failure modes this guards, both of which ship silently otherwise because
 * {@code node_type_documentation} content is asserted nowhere else:
 * <ul>
 *   <li>The migration does not apply at all (wrong table, wrong WHERE), leaving the
 *       agent with docs that describe a run pattern the code dropped in March 2026.</li>
 *   <li>The migration applies but renders wrong. The examples embed single quotes
 *       inside a single-quoted SQL literal, so one doubling mistake turns every
 *       {@code action='execute'} into {@code action=''execute''} - still valid SQL,
 *       still valid JSON, and still uncopyable by the agent it is written for.</li>
 * </ul>
 */
@DisplayName("V418 sub_workflow docs: run precondition + copyable examples")
class SubWorkflowDocsRunPreconditionMigrationTest {

    private static final String DB = "v417_sub_workflow_docs";

    @Test
    @DisplayName("Rewrites the stale ephemeral-run docs and renders examples with single quotes")
    void v417FixesDocsAndRendersCopyableExamples(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, DB);

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, DB, tempDir))
                    .doesNotThrowAnyException();

            String description = description(postgres, "sub_workflow");
            // The claim the code has not honoured since the trigger-based rewrite.
            assertThat(description).doesNotContain("ephemeral");
            assertThat(description).contains("must already have a live run");

            List<String> concepts = jsonArray(postgres, "concepts", "sub_workflow");
            assertThat(concepts).anyMatch(c -> c.contains("No active run found for workflow X"));
            assertThat(concepts).anyMatch(c -> c.contains("PINNED"));
            // "runs with the same tenantId as the parent" was false too.
            assertThat(concepts).noneMatch(c -> c.contains("same tenantId as the parent"));

            List<String> examples = jsonArray(postgres, "examples", "sub_workflow");
            assertThat(examples).isNotEmpty();
            assertThat(examples).allMatch(e -> e.contains("workflow(action='"));
            // The doubled-quote rendering bug: valid SQL, valid JSON, useless to an agent.
            assertThat(examples).noneMatch(e -> e.contains("action=''"));
            assertThat(examples).noneMatch(e -> e.contains("workflow_builder("));
            // Scoped: the unrelated row must be untouched.
            assertThat(description(postgres, "transform")).isEqualTo("Transform data.");
        }
    }

    /**
     * Minimal fixture: the seeded shape of the two rows V418 touches (the stale
     * sub_workflow row from V11, and one unrelated row carrying the same bogus tool
     * name), then the REAL V418.
     */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed.sql"), """
                CREATE SCHEMA orchestrator;

                CREATE TABLE orchestrator.node_type_documentation (
                    type        VARCHAR(128) PRIMARY KEY,
                    description TEXT NOT NULL DEFAULT '',
                    concepts    JSONB,
                    keywords    JSONB,
                    examples    JSONB,
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                INSERT INTO orchestrator.node_type_documentation (type, description, concepts, keywords, examples)
                VALUES (
                    'sub_workflow',
                    'Executes another workflow as a function call (ephemeral run pattern).',
                    '["Executes the target workflow as an ephemeral (one-shot) run",
                      "The sub-workflow runs with the same tenantId as the parent workflow"]'::jsonb,
                    '["sub_workflow"]'::jsonb,
                    '["workflow_builder(action=''add_node'', type=''sub_workflow'')"]'::jsonb
                ), (
                    'transform',
                    'Transform data.',
                    '[]'::jsonb,
                    '["transform"]'::jsonb,
                    '["workflow_builder(action=''add_node'', type=''transform'')"]'::jsonb
                );
                """);

        Files.writeString(directory.resolve("V2__fix_sub_workflow_docs.sql"),
                Files.readString(Path.of("src/main/resources/db/migration/"
                        + "V418__fix_sub_workflow_node_docs_run_precondition.sql")));
    }

    private static String description(PostgreSQLContainer<?> postgres, String type) throws Exception {
        try (var connection = connect(postgres);
             var statement = connection.prepareStatement(
                     "SELECT description FROM orchestrator.node_type_documentation WHERE type = ?")) {
            statement.setString(1, type);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static List<String> jsonArray(PostgreSQLContainer<?> postgres, String column, String type)
            throws Exception {
        try (var connection = connect(postgres);
             var statement = connection.prepareStatement(
                     "SELECT elem #>> '{}' FROM orchestrator.node_type_documentation,"
                             + " jsonb_array_elements(" + column + ") AS elem WHERE type = ?")) {
            statement.setString(1, type);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    private static java.sql.Connection connect(PostgreSQLContainer<?> postgres) throws Exception {
        return DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, DB), postgres.getUsername(), postgres.getPassword());
    }
}
