package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres contract for {@code WorkflowRepository.findSubWorkflowEdgesByOrganization}, the one
 * query behind the parent/child menus.
 *
 * <p>It is native jsonb SQL, so it is invisible to every other kind of test here: a mocked
 * repository pins the Java around it, and the unit test suite runs on H2, which does not have
 * {@code jsonb_array_elements} at all. The failure mode that matters is not a wrong result but a
 * RAISED one - {@code jsonb_array_elements} errors on a non-array, and one workflow whose
 * {@code plan.cores} is an object would then take down the relation lookup for the whole workspace,
 * i.e. every card in the grid. That guard cannot be asserted anywhere but here.
 *
 * <p>The SQL is read off the shipped {@code @Query} annotation by reflection rather than copied into
 * this file: a copy would keep passing while the query the application actually runs was broken.
 *
 * <p><b>How it runs.</b> Plain JDBC against a scratch database named by
 * {@code ORCHESTRATOR_TEST_PG_URL}, mirroring
 * {@code CredentialSelectorDocsV453MigrationPostgresTest} (the {@code arc-build} runners expose no
 * Docker socket, so a Testcontainers class SKIPS there, which looks like coverage and is not). With
 * {@code CI} set and no URL the class FAILS rather than skipping, so it cannot be silently disabled
 * by dropping the env block from its workflow step.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("sub-workflow edge query - real Postgres, the shipped SQL")
class SubWorkflowEdgeQueryPostgresTest {

    private static final String URL = System.getenv("ORCHESTRATOR_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_PASSWORD", "postgres");

    private static final String ORG = "org-under-test";
    private static final String OTHER_ORG = "org-next-door";

    private static final UUID PARENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHILD = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_CHILD = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** The speed pre-filter, stripped by one case to isolate the guard that stops the raise. */
    private static final String CONTAINMENT_PREFILTER =
            "  AND w.plan->'cores' @> '[{\"type\": \"sub_workflow\"}]'::jsonb ";

    private String edgeSql;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpSchema() throws Exception {
        requireDatabaseOnCi();

        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test drops a table named 'workflows'), got: " + database);
        }

        edgeSql = shippedEdgeSql();

        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        // Only the columns the query reads. A fuller mirror of the real table would drift; what
        // this pins is the SQL's behaviour over jsonb, not the schema.
        jdbc.execute("DROP TABLE IF EXISTS workflows");
        jdbc.execute("""
                CREATE TABLE workflows (
                    id              UUID PRIMARY KEY,
                    name            VARCHAR(255),
                    plan            JSONB,
                    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
                    organization_id VARCHAR(255)
                )
                """);
    }

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE workflows");
    }

    @Test
    @DisplayName("a plan that calls a sub-workflow yields one [parent, child] edge")
    void yieldsTheEdgeOfASubWorkflowCall() {
        insert(PARENT, "Parent", ORG, true, plan(core("sub_workflow", CHILD.toString())));

        assertThat(edges()).containsExactly(Map.entry(PARENT.toString(), CHILD.toString()));
    }

    @Test
    @DisplayName("several calls in one plan yield one edge each")
    void yieldsOneEdgePerCall() {
        insert(PARENT, "Parent", ORG, true,
                plan(core("sub_workflow", CHILD.toString()), core("sub_workflow", SECOND_CHILD.toString())));

        assertThat(edges()).containsExactlyInAnyOrder(
                Map.entry(PARENT.toString(), CHILD.toString()),
                Map.entry(PARENT.toString(), SECOND_CHILD.toString()));
    }

    @Test
    @DisplayName("a plan whose cores is an object does not raise - it just contributes nothing")
    void survivesAMalformedCoresObject() {
        // jsonb_array_elements() RAISES on a non-array. Left unguarded, this single row would fail
        // the query for every workflow in the workspace, not just for itself - which is why a
        // HEALTHY row is seeded next to it and asserted to still come back.
        insert(PARENT, "Parent", ORG, true, "{\"cores\": {\"type\": \"sub_workflow\"}}");
        insert(SECOND_CHILD, "Healthy", ORG, true, plan(core("sub_workflow", CHILD.toString())));

        assertThat(edges()).containsExactly(Map.entry(SECOND_CHILD.toString(), CHILD.toString()));
    }

    @Test
    @DisplayName("the typeof guard alone survives a malformed plan, without the containment pre-filter")
    void theTypeofGuardStandsOnItsOwn() {
        // Two guards sit between a malformed plan and a raised error, and the case above cannot
        // tell them apart: the `@>` pre-filter happens to discard that row too. Strip the
        // pre-filter (it exists for SPEED, and a planner is free to reorder it) and the CASE
        // jsonb_typeof wrapper must still carry the row on its own. Without it, this raises.
        insert(PARENT, "Parent", ORG, true, "{\"cores\": {\"type\": \"sub_workflow\"}}");
        insert(SECOND_CHILD, "Healthy", ORG, true, plan(core("sub_workflow", CHILD.toString())));

        String withoutPreFilter = edgeSql.replace(CONTAINMENT_PREFILTER, " ");
        assertThat(withoutPreFilter)
                .as("the containment pre-filter this case strips is no longer in the shipped query - "
                        + "re-derive it from the @Query before trusting this assertion")
                .isNotEqualTo(edgeSql);

        assertThat(jdbc.query(withoutPreFilter.replace(":orgId", "?"),
                ps -> ps.setString(1, ORG),
                (rs, rowNum) -> Map.entry(rs.getString("parent_id"), rs.getString("child_id"))))
                .containsExactly(Map.entry(SECOND_CHILD.toString(), CHILD.toString()));
    }

    @Test
    @DisplayName("a plan with no cores, or no plan at all, contributes nothing and does not raise")
    void survivesAMissingPlan() {
        insert(PARENT, "No cores", ORG, true, "{\"triggers\": []}");
        insert(CHILD, "No plan", ORG, true, null);

        assertThat(edges()).isEmpty();
    }

    @Test
    @DisplayName("the self-reference placeholder is not an edge")
    void ignoresTheSelfPlaceholder() {
        insert(PARENT, "Parent", ORG, true, plan(core("sub_workflow", "__self__")));

        assertThat(edges()).isEmpty();
    }

    @Test
    @DisplayName("a sub_workflow core with no workflowId is not an edge")
    void ignoresACallWithNoTarget() {
        insert(PARENT, "Parent", ORG, true, "{\"cores\": [{\"type\": \"sub_workflow\", \"subWorkflow\": {}}]}");

        assertThat(edges()).isEmpty();
    }

    @Test
    @DisplayName("cores of any other type are not edges")
    void ignoresOtherCoreTypes() {
        insert(PARENT, "Parent", ORG, true, plan(core("decision", CHILD.toString())));

        assertThat(edges()).isEmpty();
    }

    @Test
    @DisplayName("another workspace's call is not visible")
    void excludesOtherOrganizations() {
        insert(PARENT, "Foreign parent", OTHER_ORG, true, plan(core("sub_workflow", CHILD.toString())));

        assertThat(edges()).isEmpty();
    }

    @Test
    @DisplayName("a deleted workflow stops declaring its calls")
    void excludesInactiveWorkflows() {
        insert(PARENT, "Deleted parent", ORG, false, plan(core("sub_workflow", CHILD.toString())));

        assertThat(edges()).isEmpty();
    }

    /** Runs the shipped query for {@link #ORG} and returns its rows as parentId -> childId. */
    private List<Map.Entry<String, String>> edges() {
        return jdbc.query(edgeSql.replace(":orgId", "?"),
                ps -> ps.setString(1, ORG),
                (rs, rowNum) -> Map.entry(rs.getString("parent_id"), rs.getString("child_id")));
    }

    private void insert(UUID id, String name, String orgId, boolean active, String planJson) {
        jdbc.update("INSERT INTO workflows (id, name, plan, is_active, organization_id) VALUES (?, ?, ?::jsonb, ?, ?)",
                id, name, planJson, active, orgId);
    }

    private static String plan(String... cores) {
        return "{\"cores\": [" + String.join(",", cores) + "]}";
    }

    private static String core(String type, String workflowId) {
        return "{\"type\": \"" + type + "\", \"subWorkflow\": {\"workflowId\": \"" + workflowId + "\"}}";
    }

    /**
     * The SQL as the application ships it, read off the repository method's {@code @Query}. Copying
     * the query into this file would let it pass against a string nothing runs.
     */
    private static String shippedEdgeSql() throws NoSuchMethodException {
        Query query = WorkflowRepository.class
                .getMethod("findSubWorkflowEdgesByOrganization", String.class)
                .getAnnotation(Query.class);
        if (query == null || !query.nativeQuery()) {
            throw new IllegalStateException(
                    "findSubWorkflowEdgesByOrganization no longer carries a native @Query. If the "
                            + "edge lookup moved, move this test with it - it is the only place the "
                            + "jsonb guards are exercised against a real engine.");
        }
        return query.value();
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL is unset on CI. This class must execute there: it is "
                            + "the only test that runs the sub-workflow edge SQL against a real "
                            + "engine, and its jsonb guards fail as a raised error, not a wrong "
                            + "answer. Restore the env block on the workflow step that runs it, and "
                            + "keep that step in a job carrying the postgres service.");
        }
        Assumptions.abort(
                "no scratch Postgres: set ORCHESTRATOR_TEST_PG_URL to run this locally "
                        + "(CI always sets it)");
    }

    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException(
                        "ORCHESTRATOR_TEST_PG_URL is set but the database is unreachable: " + URL, e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }
}
