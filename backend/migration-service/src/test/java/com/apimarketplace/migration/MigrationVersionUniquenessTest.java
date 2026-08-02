package com.apimarketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against two branches landing the same Flyway version.
 *
 * <p>Flyway aborts with "Found more than one migration with version N" and
 * migration-service is first in the deploy order, so a duplicate takes the
 * whole stack down at startup - after merge, on a branch where every test was
 * green in isolation. It is an easy collision to create: parallel worktrees
 * each pick "the next number" against the same base.
 *
 * <p>Caught in practice on 2026-07-30, when a catalog-sync branch and a
 * CE-release branch both authored a {@code V415__}.
 */
@DisplayName("Flyway migrations - version numbers are unique")
class MigrationVersionUniquenessTest {

    /** {@code V<version>__<description>.sql}; version may be dotted (V1.1__). */
    private static final Pattern VERSIONED =
            Pattern.compile("^V(\\d+(?:\\.\\d+)*)__.+\\.sql$");

    @Test
    @DisplayName("No two migration files share a version number")
    void migrationVersionsAreUnique() throws IOException {
        Map<String, List<String>> byVersion = new LinkedHashMap<>();

        try (Stream<Path> files = Files.list(migrationDir())) {
            files.map(p -> p.getFileName().toString())
                 .sorted()
                 .forEach(name -> {
                     Matcher m = VERSIONED.matcher(name);
                     if (m.matches()) {
                         byVersion.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(name);
                     }
                 });
        }

        assertThat(byVersion).isNotEmpty();

        List<String> duplicates = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " -> " + e.getValue())
                .toList();

        assertThat(duplicates)
                .as("Flyway refuses to start when two files declare the same version. "
                        + "Renumber the newer migration (and every reference to it) to the "
                        + "next free number.")
                .isEmpty();
    }

    /** Resolves whether surefire runs from the module dir or the reactor root. */
    private static Path migrationDir() {
        String[] candidates = {
                "src/main/resources/db/migration",
                "migration-service/src/main/resources/db/migration",
                "backend/migration-service/src/main/resources/db/migration",
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("db/migration not found from " + Path.of("").toAbsolutePath());
    }
}
