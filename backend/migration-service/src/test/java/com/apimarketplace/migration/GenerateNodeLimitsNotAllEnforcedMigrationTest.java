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
 * V435 retracts a promise the generate node's documentation could no longer keep.
 *
 * <p>V429 told the agent that a value outside a model's limits is refused before
 * the provider is called, so trying one costs nothing. Some limits are now
 * inherited from the catalogue and enforced by nobody, so a value outside those
 * is dispatched and paid for. An agent that believes the old sentence retries
 * variations of a rejected value.
 *
 * <p>Written because the first version of this migration named a column and a
 * key that do not exist ({@code usage_notes}, {@code node_type = 'core:generate'}
 * instead of {@code concepts}, {@code type = 'generate'}). It parsed as valid SQL
 * to the eye, would have failed Flyway at startup, and would have blocked every
 * deploy. A migration that rewrites one array element deserves the same proof as
 * the code around it.
 */
@DisplayName("generate node limits are not all enforced (V435)")
class GenerateNodeLimitsNotAllEnforcedMigrationTest {

    private static final String OLD_SENTENCE =
            "A param the model does not accept, or a value outside its limits, is refused BEFORE "
            + "the provider is called - the run costs nothing and the error names the accepted values.";

    @Test
    @DisplayName("rewrites the one sentence, leaves its neighbours and the other rows alone")
    void rewritesOnlyTheStaleSentence(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "generate_limits");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "generate_limits", tempDir))
                    .doesNotThrowAnyException();

            // The new sentence names the exception and what it costs.
            assertThat(concept(postgres, "generate_limits", "generate", 1))
                    .contains("allowedEnforced=false")
                    .contains("after the call is paid for")
                    .doesNotContain("or a value outside its limits, is refused BEFORE");

            // Its neighbours in the same array are untouched: a jsonb_set with a
            // wrong path would quietly rewrite the wrong advice.
            assertThat(concept(postgres, "generate_limits", "generate", 0))
                    .isEqualTo("Pick the model FIRST.");
            assertThat(concept(postgres, "generate_limits", "generate", 2))
                    .isEqualTo("Every successful run is charged.");

            // And another node's documentation is not collateral.
            assertThat(concept(postgres, "generate_limits", "agent", 0))
                    .isEqualTo(OLD_SENTENCE);
        }
    }

    @Test
    @DisplayName("a row someone has already edited is left exactly as it was")
    void leavesAHandEditedRowAlone(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);
        Files.writeString(tempDir.resolve("V2__hand_edit.sql"), """
                UPDATE orchestrator.node_type_documentation
                SET concepts = jsonb_set(concepts, '{1}', to_jsonb('Someone rewrote this.'::text))
                WHERE type = 'generate';
                """);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "generate_limits_edited");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "generate_limits_edited", tempDir))
                    .doesNotThrowAnyException();

            assertThat(concept(postgres, "generate_limits_edited", "generate", 1))
                    .isEqualTo("Someone rewrote this.");
        }
    }

    @Test
    @DisplayName("applying it twice changes nothing the second time")
    void isIdempotent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);
        Files.writeString(tempDir.resolve("V3__reapply.sql"),
                Files.readString(Path.of("src/main/resources/db/migration/"
                        + "V435__generate_node_limits_are_not_all_enforced.sql")));

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "generate_limits_idem");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "generate_limits_idem", tempDir))
                    .doesNotThrowAnyException();

            assertThat(concept(postgres, "generate_limits_idem", "generate", 1))
                    .contains("allowedEnforced=false");
            assertThat(concept(postgres, "generate_limits_idem", "generate", 2))
                    .isEqualTo("Every successful run is charged.");
        }
    }

    /**
     * The columns V435 touches, a `generate` row whose second concept is the
     * sentence V429 wrote, and an `agent` row carrying the same text so a
     * migration that forgot its WHERE would be caught rewriting it too.
     */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), """
                CREATE SCHEMA orchestrator;

                CREATE TABLE orchestrator.node_type_documentation (
                    type       VARCHAR(128) PRIMARY KEY,
                    concepts   JSONB,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                INSERT INTO orchestrator.node_type_documentation (type, concepts) VALUES
                ('generate', '["Pick the model FIRST.", "%s", "Every successful run is charged."]'::jsonb),
                ('agent',    '["%s"]'::jsonb);
                """.formatted(OLD_SENTENCE, OLD_SENTENCE));

        Files.copy(
                Path.of("src/main/resources/db/migration/"
                        + "V435__generate_node_limits_are_not_all_enforced.sql"),
                directory.resolve("V435__generate_node_limits_are_not_all_enforced.sql"));
    }

    private static String concept(PostgreSQLContainer<?> postgres, String database,
                                  String type, int index) throws Exception {
        String sql = "SELECT concepts->>%d FROM orchestrator.node_type_documentation WHERE type = '%s'"
                .formatted(index, type);
        try (var connection = DriverManager.getConnection(
                     FlywayTestSupport.jdbcUrl(postgres, database),
                     postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
