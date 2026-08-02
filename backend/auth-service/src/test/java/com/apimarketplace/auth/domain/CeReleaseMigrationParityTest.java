package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link CeRelease} to the migration that creates its table.
 *
 * <p>Cloud auth-service runs {@code ddl-auto: validate}, so a column the entity declares and the
 * DDL does not have is not a failing test, it is every auth pod crash-looping in production behind
 * an {@code --atomic} rollback. Nothing else in the build compares the two: the entity is only ever
 * exercised against a mocked repository.
 *
 * <p>Read from the real migration file rather than a hand-written mirror. A mirror is the drift it
 * is supposed to catch: it agrees with whatever it was copied from, forever.
 */
class CeReleaseMigrationParityTest {

    private static final String MIGRATION =
            "migration-service/src/main/resources/db/migration/V415__ce_release_announcement.sql";

    /** Locates a migration from the module directory Maven runs the test in. */
    private static Path migrationFile(String name) throws Exception {
        String relative = "migration-service/src/main/resources/db/migration/" + name;
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new IllegalStateException("could not locate " + relative + " from " + here);
    }

    private static String migrationSql() throws Exception {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(MIGRATION);
            if (Files.isRegularFile(file)) {
                return Files.readString(file);
            }
        }
        throw new IllegalStateException(
                "could not locate " + MIGRATION + " from " + here
                        + " - this test must fail loudly rather than silently skip, since its whole "
                        + "job is to notice that the entity and the DDL disagree");
    }

    private static List<String> entityColumns() {
        List<String> columns = new ArrayList<>();
        for (Field field : CeRelease.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && !column.name().isBlank()) {
                columns.add(column.name());
            }
        }
        return columns;
    }

    @Test
    @DisplayName("every column the entity maps exists in V415")
    void everyMappedColumnExistsInTheMigration() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);
        List<String> columns = entityColumns();

        // Guard against the test going vacuous if the annotations are ever restructured.
        assertThat(columns)
                .as("the entity must declare explicit @Column names for this comparison to mean anything")
                .hasSize(6)
                .contains("id", "latest_version", "release_url", "security_fix", "published_at", "updated_at");

        for (String column : columns) {
            assertThat(sql)
                    .as("column %s is mapped by CeRelease but absent from V415; cloud auth-service runs "
                            + "ddl-auto: validate, so this crash-loops every pod", column)
                    .contains(column);
        }
    }

    @Test
    @DisplayName("the entity's table and schema are the ones V415 creates")
    void tableAndSchemaMatchTheMigration() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);
        Table table = CeRelease.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("ce_release");
        assertThat(table.schema()).isEqualTo("auth");
        assertThat(sql).contains(table.schema() + "." + table.name());
    }

    @Test
    @DisplayName("V419 seeds the singleton row WITHOUT a version, so the row lock has something to lock")
    void v419SeedsAnEmptyRow() throws Exception {
        String sql = Files.readString(migrationFile(
                "V419__ce_release_singleton_row.sql")).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        // The row must exist for SELECT ... FOR UPDATE to lock anything, and must carry no version
        // so a self-hosted install still answers "no release".
        assertThat(sql).contains("insert into auth.ce_release");
        assertThat(sql).contains("on conflict (id) do nothing");
        assertThat(sql)
                .as("seeding a VERSION would make every self-hosted install advertise one")
                .contains("values (1, null, null, false)");
    }

    @Test
    @DisplayName("V415 keeps the singleton constraint the store relies on")
    void migrationKeepsTheSingletonConstraint() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        // CeReleaseStore always addresses SINGLETON_ID and never queries for "the newest row".
        // Without the CHECK a second row is possible and the store would silently ignore it.
        assertThat(sql)
                .as("the single-row CHECK is what lets the store address one fixed id")
                .contains("check (id = 1)");
        assertThat(CeRelease.SINGLETON_ID).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("V415 seeds nothing, so a self-hosted install does not advertise a release to itself")
    void migrationSeedsNothing() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        // This migration runs on every CE install too, so it must not invent a RELEASE for them.
        // V419 does insert the singleton row, but with a null version, which the store and the feed
        // both read as "nothing announced" - that one is checked separately below.
        assertThat(sql)
                .as("V415 must not announce a release: it runs on self-hosted installs as well")
                .doesNotContain("insert into");
    }
}
