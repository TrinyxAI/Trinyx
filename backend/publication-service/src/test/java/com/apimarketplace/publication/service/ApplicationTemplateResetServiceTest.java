package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationTemplateResetService} - the "reset the installed
 * application's tables to the template it was downloaded with" action.
 *
 * <p>The contract under test: snapshot table nodes are joined to the acquirer's cloned
 * datasources by NODE ID (the only key that survives the clone, since {@code dataSourceId}
 * is remapped), the publication's own snapshot must never be mutated in the process, and
 * anything that could not be restored has to be REPORTED rather than silently dropped.
 */
@DisplayName("ApplicationTemplateResetService - restore an installed app's tables")
class ApplicationTemplateResetServiceTest {

    /** The teammate clicking reset. Not necessarily the member who installed the app. */
    private static final String TENANT = "tenant-caller";
    /** The tenant that installed the app, and therefore owns its datasources and their rows. */
    private static final String OWNER = "tenant-installer";
    private static final String ORG = "org-shared";
    private static final UUID PUB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLONE_WF_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private DataSourceClient dataSourceClient;
    private DataSourceFileCloneService fileCloneService;
    private StorageBreakdownService breakdownService;
    private ApplicationTemplateResetService service;
    private com.apimarketplace.publication.config.OrchestratorInternalClient orchestrator;

    /**
     * What the orchestrator answers for this test. Held in a mutable box (rather than
     * re-stubbing per test) so a test only has to overwrite the one lookup it is about.
     */
    private static class OrchestratorAnswers {
        Map<String, Object> clone;
        Map<String, Object> cloneWorkflow;
    }

    private OrchestratorAnswers orchestratorClient;

    @BeforeEach
    void setUp() {
        orchestratorClient = new OrchestratorAnswers();
        orchestrator = mock(com.apimarketplace.publication.config.OrchestratorInternalClient.class);
        dataSourceClient = mock(DataSourceClient.class);
        fileCloneService = mock(DataSourceFileCloneService.class);
        breakdownService = mock(StorageBreakdownService.class);
        service = new ApplicationTemplateResetService(
                orchestrator, dataSourceClient, fileCloneService, breakdownService, new ObjectMapper());

        // Default happy wiring: the caller owns a clone, and the clone's plan is readable.
        when(orchestrator.findBySourcePublication(eq(PUB_ID), eq(TENANT), eq(ORG)))
                .thenAnswer(inv -> orchestratorClient.clone);
        when(orchestrator.getWorkflowForPublication(eq(CLONE_WF_ID), eq(TENANT), eq(ORG)))
                .thenAnswer(inv -> orchestratorClient.cloneWorkflow);
        // The install belongs to a DIFFERENT tenant than the caller throughout this suite:
        // the clone is resolved by organization, so in a shared workspace that is the normal
        // case, and it is the only wiring under which a caller/owner mix-up is visible.
        orchestratorClient.clone = Map.of("id", CLONE_WF_ID.toString(), "tenantId", OWNER);
        // Default: replace succeeds, table was empty before.
        when(dataSourceClient.getItemsCount(anyLong(), anyString())).thenReturn(0);
        when(dataSourceClient.replaceItems(anyLong(), anyList(), anyString(), any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private WorkflowPublicationEntity publicationWithTables(List<Map<String, Object>> tables) {
        Map<String, Object> planSnapshot = new LinkedHashMap<>();
        planSnapshot.put("tables", new ArrayList<>(tables));
        WorkflowPublicationEntity pub = mock(WorkflowPublicationEntity.class);
        when(pub.getId()).thenReturn(PUB_ID);
        when(pub.getPlanSnapshot()).thenReturn(planSnapshot);
        return pub;
    }

    /** A snapshot table node as the publish path writes it (captured name + rows). */
    private Map<String, Object> snapshotTable(String nodeId, String label, List<Map<String, Object>> items) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("label", label);
        node.put("_snapshot_ds_name", label);
        node.put("_snapshot_ds_sourceType", "INLINE");
        if (items != null) node.put("_snapshot_ds_items", new ArrayList<>(items));
        return node;
    }

    private Map<String, Object> row(String value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", new LinkedHashMap<>(Map.of("col", value)));
        item.put("priority", 0);
        return item;
    }

    /** Wires the acquirer's cloned plan: node id -> the acquirer's own datasource id. */
    private void cloneHasTables(Map<String, Object>... tableNodes) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", new ArrayList<>(List.of(tableNodes)));
        orchestratorClient.cloneWorkflow = Map.of("plan", plan);
    }

    private Map<String, Object> cloneTable(String nodeId, long dataSourceId) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("dataSourceId", String.valueOf(dataSourceId));
        return node;
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Restores the snapshot rows into the datasource the node maps to in the acquirer's clone")
    @SuppressWarnings("unchecked")
    void restoresSnapshotRowsIntoTheClonedDatasource() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a"), row("b")))));
        cloneHasTables(cloneTable("table:leads", 99L));

        ApplicationTemplateResetService.ResetResult result = service.resetTables(pub, TENANT, ORG);

        ArgumentCaptor<List<Map<String, Object>>> items = ArgumentCaptor.forClass(List.class);
        verify(dataSourceClient).replaceItems(eq(99L), items.capture(), eq(OWNER), eq(ORG));
        assertThat(items.getValue()).hasSize(2);
        assertThat(((Map<String, Object>) items.getValue().get(0).get("data")).get("col")).isEqualTo("a");
        assertThat(result.tablesReset()).isEqualTo(1);
        assertThat(result.rowsRestored()).isEqualTo(2);
        assertThat(result.tablesSkipped()).isEmpty();
    }

    @Test
    @DisplayName("Every datasource write is keyed on the INSTALL's tenant, never on the teammate who clicked reset")
    void keysEveryWriteOnTheInstallTenantNotTheCaller() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a")))));
        cloneHasTables(cloneTable("table:leads", 99L));
        when(dataSourceClient.getItemsCount(eq(99L), eq(OWNER))).thenReturn(4);

        service.resetTables(pub, TENANT, ORG);

        // The clone is resolved by ORGANIZATION, so the caller is routinely not the member
        // who installed the app. Using the caller here would (a) re-upload the row files
        // into the wrong tenant while the rows themselves stay stamped with the owner's
        // (V333), (b) count zero existing rows through a tenant-scoped count, and (c) charge
        // the whole template to the wrong quota on every reset.
        verify(fileCloneService).rewriteFilePaths(
                eq("INLINE"), any(), anyList(), any(), eq(OWNER), eq(PUB_ID.toString()), eq(ORG));
        verify(dataSourceClient).getItemsCount(99L, OWNER);
        verify(dataSourceClient).replaceItems(eq(99L), anyList(), eq(OWNER), eq(ORG));
        // 1 written over the 4 that were really there = -3, booked against the OWNER.
        verify(breakdownService).increment(eq(OWNER), eq("DATA"), eq(-600L), eq(-3), eq(ORG));
        verify(dataSourceClient, never()).getItemsCount(anyLong(), eq(TENANT));
    }

    @Test
    @DisplayName("Falls back to the caller's tenant only when the clone lookup returns no owner")
    void fallsBackToTheCallerTenantWhenTheCloneCarriesNoOwner() {
        // Defensive: an older orchestrator that omits tenantId must not make the reset
        // write with a null tenant.
        orchestratorClient.clone = Map.of("id", CLONE_WF_ID.toString());
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a")))));
        cloneHasTables(cloneTable("table:leads", 99L));

        service.resetTables(pub, TENANT, ORG);

        verify(dataSourceClient).replaceItems(eq(99L), anyList(), eq(TENANT), eq(ORG));
    }

    @Test
    @DisplayName("Does not copy the FILE-source config: that blob would be paid for and then discarded")
    void doesNotCopyTheFileSourceConfig() {
        Map<String, Object> table = snapshotTable("table:sheet", "Sheet", List.of(row("a")));
        table.put("_snapshot_ds_sourceType", "FILE");
        table.put("_snapshot_ds_sourceConfig", new LinkedHashMap<>(Map.of("file_path", "publisher/source.csv")));
        WorkflowPublicationEntity pub = publicationWithTables(List.of(table));
        cloneHasTables(cloneTable("table:sheet", 99L));

        service.resetTables(pub, TENANT, ORG);

        // A non-null sourceConfig makes DataSourceFileCloneService copy the source file into
        // storage and write the new path into a map this service never reads back - a fresh
        // orphaned blob against the owner's quota on every single reset.
        verify(fileCloneService).rewriteFilePaths(
                eq("FILE"), isNull(), anyList(), any(), eq(OWNER), eq(PUB_ID.toString()), eq(ORG));
    }

    @Test
    @DisplayName("Never mutates the publication's stored snapshot, even though the file rewrite mutates its input in place")
    @SuppressWarnings("unchecked")
    void doesNotMutateThePublicationsStoredSnapshot() {
        Map<String, Object> snapshotNode = snapshotTable("table:leads", "Leads", List.of(row("original")));
        WorkflowPublicationEntity pub = publicationWithTables(List.of(snapshotNode));
        cloneHasTables(cloneTable("table:leads", 99L));
        // The real DataSourceFileCloneService rewrites items IN PLACE. If the service handed
        // it the publication's own map, the publisher's stored template would be rewritten to
        // point at the acquirer's copies - corrupting it for every other acquirer.
        doAnswer(inv -> {
            List<Map<String, Object>> received = inv.getArgument(2);
            ((Map<String, Object>) received.get(0).get("data")).put("col", "REWRITTEN");
            return null;
        }).when(fileCloneService).rewriteFilePaths(
                any(), any(), anyList(), any(), anyString(), anyString(), any());

        service.resetTables(pub, TENANT, ORG);

        List<Map<String, Object>> storedItems =
                (List<Map<String, Object>>) snapshotNode.get("_snapshot_ds_items");
        assertThat(((Map<String, Object>) storedItems.get(0).get("data")).get("col"))
                .as("the publication's stored snapshot must survive the reset untouched")
                .isEqualTo("original");
    }

    @Test
    @DisplayName("Reports a snapshot table that has no counterpart in the clone instead of silently dropping it")
    void reportsATableWithNoCounterpartInTheClone() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a"))),
                snapshotTable("table:orphan", "Orphan", List.of(row("b")))));
        cloneHasTables(cloneTable("table:leads", 99L));

        ApplicationTemplateResetService.ResetResult result = service.resetTables(pub, TENANT, ORG);

        assertThat(result.tablesReset()).isEqualTo(1);
        assertThat(result.tablesSkipped()).containsExactly("Orphan");
        verify(dataSourceClient, times(1)).replaceItems(anyLong(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("Reports a table whose replace call failed (client returned -1) rather than counting it as restored")
    void reportsATableWhoseReplaceFailed() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a")))));
        cloneHasTables(cloneTable("table:leads", 99L));
        when(dataSourceClient.replaceItems(eq(99L), anyList(), anyString(), any())).thenReturn(-1);

        ApplicationTemplateResetService.ResetResult result = service.resetTables(pub, TENANT, ORG);

        assertThat(result.tablesReset()).isZero();
        assertThat(result.rowsRestored()).isZero();
        assertThat(result.tablesSkipped()).containsExactly("Leads");
        // A failed replace must not move the quota either.
        verify(breakdownService, never()).increment(anyString(), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("Ignores a table node that was never captured into the publication (no _snapshot_ds_name)")
    void ignoresATableNodeThatWasNeverCaptured() {
        Map<String, Object> uncaptured = new LinkedHashMap<>();
        uncaptured.put("id", "table:private");
        uncaptured.put("label", "Private");
        WorkflowPublicationEntity pub = publicationWithTables(List.of(uncaptured));
        cloneHasTables(cloneTable("table:private", 99L));

        ApplicationTemplateResetService.ResetResult result = service.resetTables(pub, TENANT, ORG);

        // Not restored AND not reported: there is no template for it, so nothing was expected.
        assertThat(result.tablesReset()).isZero();
        assertThat(result.tablesSkipped()).isEmpty();
        verify(dataSourceClient, never()).replaceItems(anyLong(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("A captured table with no rows key wipes the target: the app was downloaded with that table empty")
    void capturedTableWithoutRowsWipesTheTarget() {
        // The publish path omits _snapshot_ds_items entirely when the publisher's table was
        // empty, and acquire then creates the table empty - so "restore what I downloaded"
        // means wiping, not leaving the user's rows in place.
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", null)));
        cloneHasTables(cloneTable("table:leads", 99L));
        when(dataSourceClient.getItemsCount(eq(99L), eq(OWNER))).thenReturn(7);

        ApplicationTemplateResetService.ResetResult result = service.resetTables(pub, TENANT, ORG);

        verify(dataSourceClient).replaceItems(eq(99L), eq(List.of()), eq(OWNER), eq(ORG));
        assertThat(result.tablesReset()).isEqualTo(1);
        assertThat(result.rowsRestored()).isZero();
    }

    @Test
    @DisplayName("Two snapshot nodes pointing at the same datasource replace it once (the second would wipe the first)")
    void dedupesTwoNodesPointingAtTheSameDatasource() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:read", "Read", List.of(row("a"))),
                snapshotTable("table:write", "Write", List.of(row("b")))));
        // Both nodes were cloned onto the SAME datasource, as acquire does when a plan
        // references one table from two CRUD nodes.
        cloneHasTables(cloneTable("table:read", 99L), cloneTable("table:write", 99L));

        ApplicationTemplateResetService.ResetResult result = service.resetTables(pub, TENANT, ORG);

        verify(dataSourceClient, times(1)).replaceItems(eq(99L), anyList(), anyString(), any());
        assertThat(result.tablesReset()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reports the storage DELTA, not the inserted count: a replace both removes and writes rows")
    void reportsTheStorageDeltaNotTheInsertedCount() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a"), row("b")))));
        cloneHasTables(cloneTable("table:leads", 99L));
        when(dataSourceClient.getItemsCount(eq(99L), eq(OWNER))).thenReturn(5);

        service.resetTables(pub, TENANT, ORG);

        // 2 written over 5 removed = -3. Counting +2 would inflate the DATA usage on every
        // single reset, and never subtract what the replace removed.
        verify(breakdownService).increment(eq(OWNER), eq("DATA"), eq(-600L), eq(-3), eq(ORG));
    }

    @Test
    @DisplayName("Skips the quota report entirely when the row count did not move")
    void skipsTheQuotaReportWhenTheRowCountIsUnchanged() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a")))));
        cloneHasTables(cloneTable("table:leads", 99L));
        when(dataSourceClient.getItemsCount(eq(99L), eq(OWNER))).thenReturn(1);

        service.resetTables(pub, TENANT, ORG);

        verify(breakdownService, never()).increment(anyString(), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("A table that blows up mid-restore is contained: the other tables still restore and it is REPORTED, not silently dropped")
    void containsAFailureSoTheOtherTablesStillRestore() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:first", "First", List.of(row("a"))),
                snapshotTable("table:boom", "Boom", List.of(row("b"))),
                snapshotTable("table:last", "Last", List.of(row("c")))));
        cloneHasTables(
                cloneTable("table:first", 1L), cloneTable("table:boom", 2L), cloneTable("table:last", 3L));
        // The file rewrite is the realistic thrower here (a storage hiccup mid-run), and it
        // fires on the SECOND table - after the first has already been wiped and refilled.
        doNothing().doThrow(new RuntimeException("storage unavailable")).doNothing()
                .when(fileCloneService).rewriteFilePaths(
                        any(), any(), anyList(), any(), anyString(), eq(PUB_ID.toString()), any());

        ApplicationTemplateResetService.ResetResult result = service.resetTables(pub, TENANT, ORG);

        // Letting the throw escape would answer the caller "the reset failed" while the
        // first table's rows are already gone - the worst of both outcomes.
        assertThat(result.tablesReset()).isEqualTo(2);
        assertThat(result.tablesSkipped()).containsExactly("Boom");
        verify(dataSourceClient).replaceItems(eq(1L), anyList(), anyString(), any());
        verify(dataSourceClient).replaceItems(eq(3L), anyList(), anyString(), any());
        verify(dataSourceClient, never()).replaceItems(eq(2L), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("A table is never counted as BOTH restored and skipped, whatever throws inside the loop")
    void neverCountsATableAsBothRestoredAndSkipped() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a")))));
        cloneHasTables(cloneTable("table:leads", 99L));
        // Throw from the LAST step that runs before the tallies. The real
        // StorageBreakdownService swallows its own exceptions, so this cannot happen today -
        // the point is to pin the ordering, which is one refactor away from producing
        // "1 table restored... Left alone: Leads" for a single table.
        doThrow(new RuntimeException("quota backend down"))
                .when(breakdownService).increment(anyString(), anyString(), anyLong(), anyInt(), any());

        ApplicationTemplateResetService.ResetResult result = service.resetTables(pub, TENANT, ORG);

        assertThat(result.tablesSkipped()).containsExactly("Leads");
        assertThat(result.tablesReset()).isZero();
        assertThat(result.rowsRestored()).isZero();
    }

    @Test
    @DisplayName("Refuses when the caller has no installed clone of this publication")
    void refusesWhenTheCallerHasNoInstalledClone() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a")))));
        orchestratorClient.clone = null;

        assertThatThrownBy(() -> service.resetTables(pub, TENANT, ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No acquired application");
        verify(dataSourceClient, never()).replaceItems(anyLong(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("Refuses when the clone's plan cannot be read (nothing to join the snapshot against)")
    void refusesWhenTheClonePlanCannotBeRead() {
        WorkflowPublicationEntity pub = publicationWithTables(List.of(
                snapshotTable("table:leads", "Leads", List.of(row("a")))));
        orchestratorClient.cloneWorkflow = null;

        assertThatThrownBy(() -> service.resetTables(pub, TENANT, ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no readable plan");
    }

    @Test
    @DisplayName("Refuses when the publication carries no plan snapshot (there is no template to restore)")
    void refusesWhenThePublicationHasNoPlanSnapshot() {
        WorkflowPublicationEntity pub = mock(WorkflowPublicationEntity.class);
        when(pub.getPlanSnapshot()).thenReturn(null);

        assertThatThrownBy(() -> service.resetTables(pub, TENANT, ORG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no plan snapshot");
    }
}
