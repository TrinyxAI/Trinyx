package com.apimarketplace.publication.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant: every service that PERSISTS a snapshot must recompute the
 * CE-exclusive label in the same method.
 *
 * <p>The label is derived data. If a publish path stores a snapshot without
 * calling {@code CeExclusiveFeatureDetector.applyTo}, the row keeps
 * {@code ce_exclusive = false} forever: no badge, no acquire refusal, and an app
 * that cannot run gets installed on managed cloud. That failure is SILENT - every
 * other test stays green, because each one exercises the detector or the guard in
 * isolation rather than the wiring between them.
 *
 * <p>Source-level rather than behavioural on purpose: the three publish paths have
 * heavy constructors and call out to four HTTP clients, so a per-path integration
 * test would be slow and brittle, while deleting one {@code applyTo} line is
 * exactly the regression that must be impossible to land. Same rationale as the
 * JSONB write-callsite invariant in orchestrator-service.
 *
 * <p>Adding a new publish path? Call {@code applyTo} in it. Genuinely need an
 * exemption? Add the file to {@link #EXEMPT} with a comment saying why.
 */
@DisplayName("CE-exclusive - applyTo call-site invariant")
class CeExclusiveApplyCallsiteInvariantTest {

    private static final Path MAIN_SOURCES = Paths.get("src/main/java");

    /** Writes a snapshot onto the entity - the trigger for the invariant. */
    private static final List<String> SNAPSHOT_WRITES =
            List.of(".setPlanSnapshot(", ".setAgentSnapshot(");

    private static final String APPLY_CALL = "CeExclusiveFeatureDetector.applyTo(";

    /** Files allowed to write a snapshot without recomputing the label. */
    private static final List<String> EXEMPT = List.of(
            // The detector itself never writes a snapshot; listed defensively so a
            // future helper of its own does not trip the scan.
            "CeExclusiveFeatureDetector.java"
    );

    @Test
    @DisplayName("every file that persists a snapshot also recomputes the CE-exclusive label")
    void everySnapshotWriterRecomputesTheLabel() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String fileName = file.getFileName().toString();
                if (EXEMPT.contains(fileName)) {
                    continue;
                }
                String source = Files.readString(file);
                int writes = SNAPSHOT_WRITES.stream().mapToInt(needle -> count(source, needle)).sum();
                if (writes == 0) {
                    continue;
                }
                // Counting, not merely "contains": a NEW publish method added to a
                // service that already calls applyTo elsewhere would otherwise pass
                // while persisting an unlabelled snapshot - the exact regression
                // this test exists to make impossible.
                int recomputes = count(source, APPLY_CALL);
                if (recomputes < writes) {
                    offenders.add(fileName + " (" + writes + " snapshot write(s), "
                            + recomputes + " label recompute(s))");
                }
            }
        }

        assertThat(offenders)
                .as("These files persist a snapshot more often than they call %s, so at least one "
                        + "publish path leaves ce_exclusive=false and its publications stay "
                        + "installable on managed cloud even when they cannot run there", APPLY_CALL)
                .isEmpty();
    }

    private static int count(String source, String needle) {
        int total = 0;
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) {
            total++;
        }
        return total;
    }

    @Test
    @DisplayName("the invariant actually has subjects - it must not pass by scanning nothing")
    void invariantCoversTheKnownPublishPaths() throws IOException {
        List<String> writers = new ArrayList<>();

        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (SNAPSHOT_WRITES.stream().anyMatch(source::contains)) {
                    writers.add(file.getFileName().toString());
                }
            }
        }

        // A green scan over zero files would be a false sense of safety. These are
        // the three publish paths that exist today; a NEW one only has to satisfy
        // the invariant above, it does not have to be listed here.
        assertThat(writers).contains(
                "WorkflowPublicationService.java",
                "AgentPublicationService.java",
                "ResourcePublicationService.java");
    }
}
