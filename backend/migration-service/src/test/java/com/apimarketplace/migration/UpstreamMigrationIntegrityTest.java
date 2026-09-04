package com.apimarketplace.migration;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamMigrationIntegrityTest {

    private static final String ROOT =
            "backend/migration-service/src/main/resources/db/migration/";
    private static final String UPSTREAM_V435 = ROOT
            + "V435__generate_node_limits_are_not_all_enforced.sql";
    private static final String RELOCATED_V435 = ROOT
            + "V435_1__generate_node_limits_are_not_all_enforced.sql";
    private static final String CALLBACK = ROOT + "beforeEachMigrate.sql";

    @Test
    void liveContextMigrationsAreByteForBytePreservedAndTrinyxTailIsExplicit()
            throws Exception {
        Path repository = repositoryRoot();
        List<String> manifest;
        try (InputStream input = getClass().getResourceAsStream(
                "/upstream-livecontext-0.2.14-migrations.tsv")) {
            assertThat(input).as("upstream SHA manifest must be packaged").isNotNull();
            manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
        }

        Map<String, String> status = new LinkedHashMap<>();
        Set<String> representedCurrentPaths = new LinkedHashSet<>();
        for (String entry : manifest) {
            String[] columns = entry.split("\\t", 2);
            assertThat(columns).hasSize(2);
            String expectedSha = columns[0];
            String upstreamPath = columns[1];
            String currentPath = UPSTREAM_V435.equals(upstreamPath)
                    ? RELOCATED_V435 : upstreamPath;
            Path file = repository.resolve(currentPath);
            assertThat(file).as("upstream migration is missing: %s", currentPath)
                    .isRegularFile();
            String actualSha = gitBlobSha(Files.readAllBytes(file));
            representedCurrentPaths.add(currentPath);

            if (CALLBACK.equals(upstreamPath)) {
                assertSafeCallback(file);
                status.put(upstreamPath,
                        "SAFE_TRINYX_CALLBACK_EXTENSION " + expectedSha + " -> " + actualSha);
            } else {
                assertThat(actualSha)
                        .as("LiveContext migration changed: %s", upstreamPath)
                        .isEqualTo(expectedSha);
                status.put(upstreamPath,
                        UPSTREAM_V435.equals(upstreamPath)
                                ? "MATCH_RELOCATED " + actualSha : "MATCH " + actualSha);
            }
        }

        assertThat(status).hasSize(441);
        assertThat(status.get(ROOT + "V149__credit_ledger_pin_id_index.sql"))
                .startsWith("MATCH 2b51b8c976fb162297a894a9fd0341e7f1b7d614");
        assertThat(status.get(ROOT + "V150__credit_ledger_expires_at_index.sql"))
                .startsWith("MATCH c96b23a29cca6a3645834847b30a6c2c19c1e19e");
        assertThat(status.get(
                "backend/migration-service/src/main/java/db/migration/"
                        + "V151__backfill_scope_id.java"))
                .startsWith("MATCH dfa2130a17a394f21d3320628d2daecc015000c0");
        assertThat(status.get(ROOT + "V454__ce_install_telemetry.sql"))
                .startsWith("MATCH f2f8d8d8cb3babe6f68a1088eb5c32f429598a3d");
        assertThat(status.get(ROOT + "V455__repair_table_media_cells.sql"))
                .startsWith("MATCH e1325db2ec5b3ca588f9d010e83313b04287abb0");
        assertThat(status.get(ROOT + "V456__merge_docs_state_the_all_skipped_rule.sql"))
                .startsWith("MATCH 303c5b1b7f5e179b13facffcbf26d23d109308f4");
        assertThat(repository.resolve(ROOT
                + "V148_1__empty_credit_ledger_indexes.sql")).doesNotExist();

        Set<String> currentVersioned;
        try (var files = Files.list(repository.resolve(ROOT))) {
            currentVersioned = files.filter(Files::isRegularFile)
                    .map(repository::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> path.matches(".*/V[^/]+\\.sql"))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        currentVersioned.removeAll(representedCurrentPaths);
        assertThat(currentVersioned).containsExactlyInAnyOrder(
                ROOT + "V435__billing_event_processing_lifecycle.sql",
                ROOT + "V453_1__external_billing_authority.sql",
                ROOT + "V453_2__cloud_settlement_outbox_fencing.sql",
                ROOT + "V453_3__workspace_storage_erasure_outbox.sql");

        status.forEach((path, result) ->
                System.out.println(path + "\t" + result));
        System.out.printf(
                "Upstream v0.2.14 integrity: %d inventory entries; V435 content relocated; "
                        + "callback limited to session reset; Trinyx-only=%s%n",
                status.size(), currentVersioned);
    }

    private static void assertSafeCallback(Path callback) throws Exception {
        String executableSql = Files.readString(callback)
                .replaceAll("(?m)--.*$", "")
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase();
        assertThat(executableSql).isEqualTo(
                "reset lock_timeout; reset statement_timeout; "
                        + "set search_path to orchestrator, public;");
        assertThat(executableSql)
                .doesNotContain("create ")
                .doesNotContain("alter ")
                .doesNotContain("drop ")
                .doesNotContain("insert ")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }

    private static String gitBlobSha(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static Path repositoryRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(
                    "backend/migration-service/src/main/resources/db/migration"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate repository root from " + here);
    }
}
