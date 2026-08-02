package com.apimarketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Replay test for the V420 CE-exclusive backfill: runs the REAL migration file
 * against a real Postgres over a fixture table of snapshot shapes.
 *
 * <p>It catches two failure modes nothing else can:
 * <ol>
 *   <li>a jsonpath that <b>aborts</b>. {@code .keyvalue()} on a non-object is an
 *       item-method error that lax mode does NOT suppress, and the key
 *       {@code agents} is used both for a plan's node list and for a
 *       toolsConfig grant list of ID STRINGS. An abort fails Flyway, which fails
 *       the deploy;</li>
 *   <li>a row the backfill labels differently from what the publish-time
 *       detector would.</li>
 * </ol>
 *
 * <p><b>What the expectations are.</b> {@code expect} in the fixture file is a
 * HAND-MAINTAINED golden column, not a live call into
 * {@code CeExclusiveFeatureDetector}: that class lives in publication-service,
 * which migration-service does not (and should not) depend on. The same snapshot
 * shapes are asserted against the detector itself in
 * {@code CeExclusiveFeatureDetectorTest}, so the pair covers both
 * implementations; adding a shape to one means adding it to the other.
 *
 * <p>Skipped when Docker is unavailable, like every other replay test in this
 * package.
 */
@DisplayName("V420 CE-exclusive backfill - replayed against Postgres")
class CeExclusiveBackfillExecutionTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V420__publication_ce_exclusive.sql");
    private static final Path FIXTURES = Path.of(
            "src/test/resources/ce-exclusive-backfill-fixtures.sql");

    @Test
    @DisplayName("labels every fixture as its golden expectation, and never aborts")
    void backfillMatchesTheGoldenExpectations(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        // V1 seeds the schema + fixtures, V2 IS the real migration under test.
        Files.writeString(tempDir.resolve("V1__seed_ce_exclusive_fixtures.sql"),
                "CREATE SCHEMA publication;\n"
                        + Files.readString(FIXTURES, StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("V2__ce_exclusive_backfill.sql"),
                Files.readString(MIGRATION, StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            String db = "ce_exclusive_backfill_replay";
            FlywayTestSupport.createDatabase(postgres, db);

            // An aborting jsonpath surfaces HERE, exactly as it would to Flyway in prod.
            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, db, tempDir))
                    .doesNotThrowAnyException();

            List<String> mismatches = new ArrayList<>();
            int total = 0;
            try (Connection connection = DriverManager.getConnection(
                    FlywayTestSupport.jdbcUrl(postgres, db), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT name, expect, ce_exclusive, ce_exclusive_features::text
                         FROM publication.workflow_publications ORDER BY name
                         """)) {
                while (rows.next()) {
                    total++;
                    boolean expected = rows.getBoolean("expect");
                    boolean actual = rows.getBoolean("ce_exclusive");
                    if (expected != actual) {
                        mismatches.add(rows.getString("name")
                                + ": expected=" + expected + " backfill=" + actual
                                + " features=" + rows.getString(4));
                    }
                }
            }

            assertThat(total)
                    .as("the fixture file must actually load - a silent zero would make this test vacuous")
                    .isGreaterThanOrEqualTo(30);
            assertThat(mismatches)
                    .as("The backfill labels these snapshots differently from the publish-time "
                            + "detector, so the label would flip on the publication's next publish")
                    .isEmpty();
        }
    }
}
