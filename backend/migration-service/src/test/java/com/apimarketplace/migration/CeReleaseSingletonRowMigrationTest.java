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
 * Runs V415 + V419 against a real Postgres and checks the row they are supposed to leave behind.
 *
 * <p>Why this had to be a real-database test. The announced-release row is written by two paths
 * that both do {@code SELECT ... FOR UPDATE} and then decide whether to write. That takes NO lock
 * on a row which does not exist, and {@code CeRelease} has an assigned id so {@code save()} goes
 * through {@code merge()} - which re-reads and emits an UPDATE rather than failing on the primary
 * key. So with an absent row, a release announced between the two statements was silently
 * overwritten. V419 seeds the row (with no version) precisely so the lock has something to take.
 *
 * <p>The text-matching test that guarded this before could not tell a working seed from a no-op:
 * replacing the INSERT with {@code SELECT ... WHERE false} kept every assertion green while
 * reopening the race entirely. Only executing the migration proves anything.
 */
@DisplayName("auth.ce_release singleton row (V415 + V419)")
class CeReleaseSingletonRowMigrationTest {

    private static final String MIGRATIONS = "src/main/resources/db/migration";

    /** Copies just the two migrations under test into an isolated directory. */
    private static void stageMigrations(Path target) throws Exception {
        Path source = Path.of(MIGRATIONS);
        for (String name : new String[] {
                "V415__ce_release_announcement.sql", "V419__ce_release_singleton_row.sql" }) {
            Path file = source.resolve(name);
            assertThat(Files.isRegularFile(file)).as("missing migration %s", name).isTrue();
            Files.copy(file, target.resolve(name));
        }
        // V415 targets the auth schema, which an empty database does not have.
        Files.writeString(target.resolve("V001__schema.sql"), "CREATE SCHEMA IF NOT EXISTS auth;\n");
    }

    private static String query(PostgreSQLContainer<?> postgres, String db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        FlywayTestSupport.jdbcUrl(postgres, db), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    @DisplayName("the row exists after migrating, and carries no version")
    void seedsExactlyOneVersionlessRow(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        stageMigrations(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "ce_release_seed");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "ce_release_seed", tempDir))
                    .doesNotThrowAnyException();

            // The row must EXIST: this is what gives SELECT ... FOR UPDATE something to lock, and
            // it is the assertion the previous text-matching test could not make.
            assertThat(query(postgres, "ce_release_seed", "SELECT count(*) FROM auth.ce_release"))
                    .as("without this row both write paths race and the older release can win")
                    .isEqualTo("1");

            // And must carry NO version: this migration runs on every self-hosted install too, so
            // seeding a version would invent a release for the whole CE fleet.
            assertThat(query(postgres, "ce_release_seed",
                    "SELECT latest_version FROM auth.ce_release WHERE id = 1"))
                    .as("a seeded version would make every CE install advertise a release")
                    .isNull();
        }
    }

    @Test
    @DisplayName("an existing announcement survives the seed, so re-running cannot erase it")
    void doesNotOverwriteAnExistingAnnouncement(@TempDir Path tempDir) throws Exception {
        // The production case: V415 already ran there and the row may already hold a release.
        // ON CONFLICT DO NOTHING is what keeps V419 safe to apply on top of it.
        FlywayTestSupport.assumeDockerAvailable();
        Path source = Path.of(MIGRATIONS);
        Files.writeString(tempDir.resolve("V001__schema.sql"), "CREATE SCHEMA IF NOT EXISTS auth;\n");
        Files.copy(source.resolve("V415__ce_release_announcement.sql"),
                tempDir.resolve("V415__ce_release_announcement.sql"));
        Files.writeString(tempDir.resolve("V416__existing.sql"),
                "INSERT INTO auth.ce_release (id, latest_version) VALUES (1, '0.2.7');\n");
        Files.copy(source.resolve("V419__ce_release_singleton_row.sql"),
                tempDir.resolve("V419__ce_release_singleton_row.sql"));

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "ce_release_existing");

            FlywayTestSupport.runFlyway(postgres, "ce_release_existing", tempDir);

            assertThat(query(postgres, "ce_release_existing",
                    "SELECT latest_version FROM auth.ce_release WHERE id = 1"))
                    .as("V419 must never clear an announcement that is already there")
                    .isEqualTo("0.2.7");
            assertThat(query(postgres, "ce_release_existing", "SELECT count(*) FROM auth.ce_release"))
                    .isEqualTo("1");
        }
    }
}
