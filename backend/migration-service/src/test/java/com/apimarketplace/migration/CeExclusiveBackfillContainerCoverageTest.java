package com.apimarketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The V420 backfill and {@code CeExclusiveFeatureDetector} label the SAME thing
 * from the SAME snapshots, by two independent implementations (jsonpath and
 * Java). When they disagree, a publication is flagged by the migration and then
 * silently UN-flagged by its next publish (or vice versa): two sources of truth
 * for one label, and a cloud install that appears or disappears with no user
 * action.
 *
 * <p>This test pins the STRUCTURAL half of that agreement, cheaply and without a
 * database: every snapshot CONTAINER the detector inspects must have a predicate
 * in the SQL, and no predicate may be loosened to a bare key. The SEMANTIC half
 * is covered by {@link CeExclusiveBackfillExecutionTest}, which replays the real
 * migration against Postgres. What this one catches is the drift that actually
 * happens: someone teaches the detector a new container and forgets the SQL, or
 * relaxes an anchor to make a shape match.
 */
@DisplayName("V420 CE-exclusive backfill - container coverage")
class CeExclusiveBackfillContainerCoverageTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V420__publication_ce_exclusive.sql");

    /** Every container the Java detector walks, with the jsonpath that must cover it. */
    private static final List<String[]> REQUIRED_PREDICATES = List.of(
            new String[]{"plan-root agents[] node (entity, inline and compaction providers)",
                    "$.agents[*] ? (@.type() == \"object\").keyvalue()"},
            new String[]{"agents[] inside any nested plan (sub-workflows, agent-embedded workflows)",
                    "$.**.plan.agents[*] ? (@.type() == \"object\").keyvalue()"},
            new String[]{"agent{} object of an AGENT publication",
                    "$.agent ? (@.type() == \"object\").keyvalue()"},
            new String[]{"agent{} object of every sub-agent",
                    "$.**.subAgents.*.agent ? (@.type() == \"object\").keyvalue()"},
            new String[]{"table node captured schema",
                    "$.**._snapshot_ds_mappingSpec.*.type"},
            new String[]{"table node schema, nested children columns",
                    "$.**._snapshot_ds_mappingSpec.**.children.*.type"},
            new String[]{"standalone TABLE root schema",
                    "$.mappingSpec.*.type"},
            new String[]{"standalone INTERFACE backing table",
                    "$.**.embeddedTable.mappingSpec.*.type"},
            new String[]{"agent-attached datasources",
                    "$.**.datasources.*.mappingSpec.*.type"},
            new String[]{"similarity block at the table-node root",
                    "$.**.tables[*].similarity.column"},
            new String[]{"similarity block under crud",
                    "$.**.tables[*].crud.similarity.column"},
            new String[]{"similarity block under params",
                    "$.**.tables[*].params.similarity.column"},
            new String[]{"similarity block, stringified form",
                    "$.**.tables[*].similarity ? (@ like_regex"},
            new String[]{"agents as a keyed map (mapsIn accepts it)",
                    "$.agents.* ? (@.type() == \"object\").keyvalue()"});

    /**
     * Predicates that would reach FREE-FORM payload the detector never reads
     * (interface snapshot data, agent config blobs). Each one caused a real
     * false positive during review.
     */
    private static final List<String> FORBIDDEN_UNANCHORED = List.of(
            // bare key names anywhere in the snapshot
            "'$.**.type ?",
            "'$.**.provider ?",
            "'$.**.modelProvider ?",
            "'$.**.similarity.column",
            // a nested mappingSpec also matches an agent CONFIG blob
            "'$.**.mappingSpec.",
            // a bare recursive agents[] descends into an agent CONFIG blob
            "'$.**.agents",
            // `.**.type` under a schema also matches a column's `display.type`,
            // which is a render hint, not a column type
            "mappingSpec.**.type",
            // unbounded descent under a table node also matches user ROW data
            // (_snapshot_ds_items), which is not a query configuration
            "tables[*].**.similarity",
            // a level wildcard also matches level-1 containers the plan parser
            // does NOT merge into the crud config (e.g. _snapshot_ds_sourceConfig)
            "tables[*].**{0 to 1}");

    /**
     * {@code .keyvalue()} raises an item-method error on a non-object, and lax
     * mode does NOT suppress it: the whole migration aborts, Flyway fails and the
     * deploy rolls back. The key {@code agents} is a node LIST in a plan but a
     * list of ID STRINGS in an agent's toolsConfig, so the type guard is the only
     * thing standing between this backfill and a failed release.
     */
    private static final List<String> KEYVALUE_GUARD_REQUIRED = List.of(
            "$.agents[*] ? (@.type() == \"object\").keyvalue()",
            "$.**.plan.agents[*] ? (@.type() == \"object\").keyvalue()",
            "$.agent ? (@.type() == \"object\").keyvalue()",
            "$.**.subAgents.*.agent ? (@.type() == \"object\").keyvalue()");

    @Test
    @DisplayName("every container the detector inspects has a backfill predicate")
    void everyDetectorContainerIsCovered() throws IOException {
        String sql = Files.readString(MIGRATION);
        List<String> missing = new ArrayList<>();

        for (String[] entry : REQUIRED_PREDICATES) {
            if (!sql.contains(entry[1])) {
                missing.add(entry[0] + "  (expected jsonpath fragment: " + entry[1] + ")");
            }
        }

        assertThat(missing)
                .as("The detector labels these containers but the backfill does not, so existing "
                        + "publications using them stay installable on managed cloud until someone "
                        + "re-publishes them")
                .isEmpty();
    }

    @Test
    @DisplayName("no predicate is anchored on a bare key name (that would over-match free-form payload)")
    void noUnanchoredPredicates() throws IOException {
        String sql = Files.readString(MIGRATION);
        List<String> offenders = new ArrayList<>();

        for (String pattern : FORBIDDEN_UNANCHORED) {
            if (sql.contains(pattern)) {
                offenders.add(pattern);
            }
        }

        assertThat(offenders)
                .as("An unanchored predicate matches payload the Java detector never reads "
                        + "(interface data, agent config blobs), so the backfill would flag a row "
                        + "that the next publish silently un-flags")
                .isEmpty();
    }

    @Test
    @DisplayName("every keyvalue() is type-guarded - an unguarded one ABORTS the migration")
    void everyKeyvalueIsTypeGuarded() throws IOException {
        String sql = Files.readString(MIGRATION);

        for (String guarded : KEYVALUE_GUARD_REQUIRED) {
            assertThat(sql)
                    .as("keyvalue() must be preceded by a type guard, or a toolsConfig grant list "
                            + "of ID strings aborts the whole backfill and fails the deploy")
                    .contains(guarded);
        }
        // No keyvalue() may appear without one. Comments are stripped first: they
        // discuss keyvalue() by name and would otherwise be counted as call sites.
        String code = sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
        int keyvalues = code.split("\\.keyvalue\\(\\)", -1).length - 1;
        int guards = code.split("\\? \\(@\\.type\\(\\) == \"object\"\\)\\.keyvalue\\(\\)", -1).length - 1;
        assertThat(guards)
                .as("found %d keyvalue() call(s) but only %d type-guarded", keyvalues, guards)
                .isEqualTo(keyvalues);
    }

    @Test
    @DisplayName("the backfill keeps its cheap prefilter (snapshots are multi-MB)")
    void keepsThePrefilter() throws IOException {
        String sql = Files.readString(MIGRATION);

        // Without it, 15 recursive jsonpath scans run over every snapshot - and a
        // snapshot embeds its datasource ROWS - which can stall an --atomic rollout.
        assertThat(sql).contains("claude-code|codex|gemini-cli|mistral-vibe");
        // Case-INsensitive: the predicates tolerate any spelling of a provider
        // name, so a case-sensitive prefilter would skip rows they would match.
        assertThat(sql).contains("~* '(");
    }

    @Test
    @DisplayName("the column defaults match the entity so no legacy row can read as null")
    void columnDefaultsMatchTheEntity() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("ce_exclusive BOOLEAN NOT NULL DEFAULT FALSE");
        assertThat(sql).contains("ce_exclusive_features JSONB NOT NULL DEFAULT '[]'::jsonb");
    }
}
