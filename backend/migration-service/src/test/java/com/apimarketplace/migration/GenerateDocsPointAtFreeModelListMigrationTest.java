package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V436 stops the generate node's documentation ordering a tool the reader may
 * not hold.
 *
 * <p>V429 wrote "call generation(action='models')" into {@code description},
 * {@code concepts[0]} and two parameter descriptions. That tool is opt-in per
 * agent because CREATING spends credits; listing does not. An agent without the
 * grant therefore read an instruction it could not follow, for the one required
 * param of this node whose values cannot be guessed.
 *
 * <p>The help payload is the DB row merged under a static block, so code alone
 * could only fix half of it: the row kept putting the old instruction in front
 * of the agent beside the new one. Only a migration can fix its own half, which
 * is why this exists.
 *
 * <p>Written for the same reason as its sibling for V435: a draft of that one
 * named a column that does not exist, parsed fine to the eye, and would have
 * failed Flyway at startup and blocked every deploy. Four UPDATEs deserve the
 * same proof as the code around them.
 */
@DisplayName("generate docs point at the free model list (V436)")
class GenerateDocsPointAtFreeModelListMigrationTest {

    private static final String GATED = "generation(action='models')";
    private static final String FREE = "workflow(action='help', topics=['generate'])";

    @Test
    @DisplayName("no column of the generate row still orders the gated tool")
    void nothingOrdersTheGatedTool(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "generate_docs");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "generate_docs", tempDir))
                    .doesNotThrowAnyException();

            // Every string an agent reads out of this row, in one assertion: a
            // fix that missed a column would leave both instructions in the
            // same payload, which is the state this migration exists to end.
            assertThat(wholeRow(postgres, "generate_docs", "generate"))
                    .doesNotContain(GATED)
                    .contains(FREE);
        }
    }

    @Test
    @DisplayName("rewrites description, concepts[0] and both parameter descriptions")
    void rewritesEveryStalePlace(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "generate_docs_each");

            FlywayTestSupport.runFlyway(postgres, "generate_docs_each", tempDir);

            assertThat(text(postgres, "generate_docs_each", "description")).contains(FREE);
            assertThat(concept(postgres, "generate_docs_each", 0)).contains(FREE).contains("runs_on");
            assertThat(param(postgres, "generate_docs_each", "model")).contains(FREE);
            // The payer sentence must state what the NODE does: unstated is
            // platform, substituted before the run, not a fallback.
            assertThat(param(postgres, "generate_docs_each", "credential_source"))
                    .contains("UNSTATED MEANS 'platform'")
                    .contains("runs_on");
        }
    }

    @Test
    @DisplayName("leaves the neighbouring concept and another node's row alone")
    void touchesNothingElse(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "generate_docs_scope");

            FlywayTestSupport.runFlyway(postgres, "generate_docs_scope", tempDir);

            // A jsonb_set on the wrong index rewrites the wrong advice.
            assertThat(concept(postgres, "generate_docs_scope", 1))
                    .isEqualTo("Every successful run is charged.");
            // And a missing WHERE would take another node's row with it.
            assertThat(text(postgres, "generate_docs_scope", "description", "agent"))
                    .contains(GATED);
        }
    }

    @Test
    @DisplayName("a row someone has already edited is left exactly as it was")
    void leavesAHandEditedRowAlone(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);
        Files.writeString(tempDir.resolve("V437__hand_edit.sql"), """
                UPDATE orchestrator.node_type_documentation
                SET parameters = jsonb_set(parameters, '{credential_source,description}',
                        to_jsonb('Someone rewrote this.'::text))
                WHERE type = 'generate';
                """);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "generate_docs_edited");

            FlywayTestSupport.runFlyway(postgres, "generate_docs_edited", tempDir);

            // The header promises a later hand edit survives. An unguarded
            // UPDATE would silently break that promise.
            assertThat(param(postgres, "generate_docs_edited", "credential_source"))
                    .isEqualTo("Someone rewrote this.");
        }
    }

    @Test
    @DisplayName("applying it twice changes nothing the second time")
    void isIdempotent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);
        Files.writeString(tempDir.resolve("V438__reapply.sql"),
                Files.readString(Path.of("src/main/resources/db/migration/"
                        + "V436__generate_docs_point_at_the_free_model_list.sql")));

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "generate_docs_idem");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "generate_docs_idem", tempDir))
                    .doesNotThrowAnyException();

            assertThat(wholeRow(postgres, "generate_docs_idem", "generate")).doesNotContain(GATED);
            assertThat(concept(postgres, "generate_docs_idem", 1))
                    .isEqualTo("Every successful run is charged.");
        }
    }

    /**
     * The columns V436 touches, carrying the exact V429 text its WHERE clauses
     * match, plus an `agent` row holding the same instruction so a migration
     * that forgot its WHERE is caught rewriting that too.
     */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), """
                CREATE SCHEMA orchestrator;

                CREATE TABLE orchestrator.node_type_documentation (
                    type        VARCHAR(128) PRIMARY KEY,
                    description TEXT,
                    parameters  JSONB,
                    concepts    JSONB,
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                INSERT INTO orchestrator.node_type_documentation (type, description, parameters, concepts)
                VALUES (
                    'generate',
                    'Generates ONE asset from a prompt. Model ids cannot be guessed - call generation(action=''models'') to list them with what each accepts, its limits and its rate, then use one of those ids.',
                    '{"model": {"type": "string", "description": "REQUIRED. A generation model id from generation(action=''models''), e.g. seedance-2.0-fast."},
                      "credential_source": {"type": "string", "description": "Which key runs the call. Omitting it means ''platform'', so a run you did not mark is charged."},
                      "credential_id": {"type": "number", "description": "Left by V433."}}'::jsonb,
                    '["Pick the model FIRST: it decides the format, the accepted params and the price. generation(action=''models'') lists every id with its accepts, limits and rate; a model id cannot be guessed.", "Every successful run is charged."]'::jsonb
                ), (
                    'agent',
                    'Another node whose text mentions generation(action=''models'') and must not be touched.',
                    '{}'::jsonb,
                    '[]'::jsonb
                );
                """);

        // The migration under test, run against that fixture. The harness only
        // reads this directory, so the real file has to be copied in - a test
        // that forgets it asserts against an untouched row and fails loudly,
        // which is how this one caught its own omission.
        Files.copy(
                Path.of("src/main/resources/db/migration/"
                        + "V436__generate_docs_point_at_the_free_model_list.sql"),
                directory.resolve("V436__generate_docs_point_at_the_free_model_list.sql"));
    }

    private static String wholeRow(PostgreSQLContainer<?> postgres, String db, String type)
            throws Exception {
        return query(postgres, db,
                "SELECT description || ' ' || parameters::text || ' ' || concepts::text "
                        + "FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static String text(PostgreSQLContainer<?> postgres, String db, String column)
            throws Exception {
        return text(postgres, db, column, "generate");
    }

    private static String text(PostgreSQLContainer<?> postgres, String db, String column, String type)
            throws Exception {
        return query(postgres, db, "SELECT " + column
                + " FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static String concept(PostgreSQLContainer<?> postgres, String db, int index)
            throws Exception {
        return query(postgres, db, "SELECT concepts->>" + index
                + " FROM orchestrator.node_type_documentation WHERE type = 'generate'");
    }

    private static String param(PostgreSQLContainer<?> postgres, String db, String name)
            throws Exception {
        return query(postgres, db, "SELECT parameters -> '" + name + "' ->> 'description' "
                + "FROM orchestrator.node_type_documentation WHERE type = 'generate'");
    }

    private static String query(PostgreSQLContainer<?> postgres, String db, String sql)
            throws Exception {
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + db);
        try (var connection = DriverManager.getConnection(
                        url, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
