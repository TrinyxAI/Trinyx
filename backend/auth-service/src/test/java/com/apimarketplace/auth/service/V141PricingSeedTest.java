package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards what V141 still seeds after the legacy image-generation tool was retired.
 *
 * <p>V141 originally carried two things: the per-image rates of the legacy image
 * tool, and the web_search row. The image half had a Java counterpart
 * ({@code ImageProviderCatalog}) and this class asserted the two never drifted.
 * That catalog is gone with the tool, so the parity check went with it: there is
 * no longer a second source of truth to drift from, and the image rows survive
 * only as history for the ledger rows already written against them.
 *
 * <p>What stays asserted is the part that is still live: the web_search seed row,
 * and V141's idempotency, so re-applying the migration cannot move rates under a
 * running install.
 */
@DisplayName("V141 pricing seed - web_search row + idempotency")
class V141PricingSeedTest {

    private static final String V141_MIGRATION_REL =
            "migration-service/src/main/resources/db/migration/V141__image_generation_and_websearch_pricing_seed.sql";

    @Test
    @DisplayName("V141 keeps legacy web_search row with fixed_cost=1, input_rate=0")
    void v141SeedsWebSearch() throws IOException {
        String v141 = stripSqlComments(Files.readString(resolveBackendFile(V141_MIGRATION_REL)));
        // ('websearch', 'default', 0, 0, 1, ...)
        assertThat(v141)
                .as("V141 must seed (websearch, default) with input_rate=0, output_rate=0, fixed_cost=1")
                .containsPattern("\\('websearch'\\s*,\\s*'default'\\s*,\\s*0\\s*,\\s*0\\s*,\\s*1\\s*,");
    }

    @Test
    @DisplayName("V141 uses ON CONFLICT DO UPDATE for idempotent re-application")
    void v141IsIdempotent() throws IOException {
        String v141 = Files.readString(resolveBackendFile(V141_MIGRATION_REL));
        String normalized = v141.toUpperCase().replaceAll("\\s+", " ");
        assertThat(normalized)
                .as("V141 must use ON CONFLICT to re-align rates on re-apply")
                .contains("ON CONFLICT (PROVIDER, MODEL, EFFECTIVE_FROM)")
                .contains("DO UPDATE SET");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Path resolveBackendFile(String rel) {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            Path backend = p.resolve("backend");
            if (Files.isDirectory(backend)) return backend.resolve(rel);
            if (p.getFileName() != null && "backend".equals(p.getFileName().toString())) {
                return p.resolve(rel);
            }
        }
        throw new IllegalStateException("Could not locate 'backend/' ancestor from " + start);
    }

    /** Strips SQL comment lines so prose mentions don't false-match assertions. */
    private static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.stripLeading().startsWith("--")) out.append(line).append('\n');
        }
        return out.toString();
    }

}
