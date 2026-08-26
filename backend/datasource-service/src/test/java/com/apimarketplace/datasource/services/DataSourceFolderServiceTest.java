package com.apimarketplace.datasource.services;

import com.apimarketplace.common.folder.FolderPreviewItem;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderDto;
import com.apimarketplace.common.folder.ResourceFolderException;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.persistence.DataSourceFolderRepository;
import com.apimarketplace.datasource.persistence.DataSourceFolderRepository.DataSourceFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a folder of TABLES says about itself. The rules it shares with the other lists are
 * pinned once in {@code ResourceFolderCoreServiceTest}; here we pin what is specific to
 * tables - the filing lives in its OWN row (V451), so every read takes a membership map, and
 * a write is authorized by the caller's visible ids rather than by a tenant column.
 */
@DisplayName("DataSourceFolderService - folder tiles and filing")
class DataSourceFolderServiceTest {

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final FolderScope ORG_SCOPE = new FolderScope(USER, ORG);
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-08-01T00:00:00Z");

    private DataSourceFolderRepository folderRepository;
    private DataSourceFolderService service;
    private final List<DataSourceFolder> folders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        folderRepository = mock(DataSourceFolderRepository.class);
        service = new DataSourceFolderService(folderRepository);
        folders.clear();
        when(folderRepository.findAllInScope(any())).thenReturn(folders);
        when(folderRepository.findById(any())).thenAnswer(inv ->
                folders.stream().filter(f -> f.getId().equals(inv.getArgument(0))).findFirst());
    }

    private DataSourceFolder folder(String name, UUID parentId) {
        DataSourceFolder folder = new DataSourceFolder();
        folder.setId(UUID.randomUUID());
        folder.setName(name);
        folder.setParentFolderId(parentId);
        folder.setOwnerId(USER);
        folder.setOrganizationId(ORG);
        folders.add(folder);
        return folder;
    }

    /** A table row with only the fields a folder tile reads. */
    private DataSource table(long id, String name, Instant updatedAt) {
        return table(id, name, updatedAt, List.of());
    }

    /** Same, with a column order - what the tile turns into the mini-table's headers. */
    private DataSource table(long id, String name, Instant updatedAt, List<String> fields) {
        List<Map<String, Object>> columnOrder = new ArrayList<>();
        int order = 0;
        for (String field : fields) {
            columnOrder.add(Map.of("field", field, "order", order++));
        }
        return new DataSource(id, USER, name, null, null, Map.of(), null,
                OLD, updatedAt, USER, columnOrder, Map.of(), null, null, null, ORG);
    }

    private List<ResourceFolderDto> summaries(UUID parentId, List<DataSource> tables,
                                              Map<Long, UUID> memberships, String sort) {
        return service.listFolderSummaries(ORG_SCOPE, parentId, tables, memberships, sort);
    }

    @Test
    @DisplayName("counts the whole subtree from the membership map")
    void countsDeep() {
        DataSourceFolder parent = folder("Sales", null);
        DataSourceFolder child = folder("2026", parent.getId());
        List<DataSource> tables = List.of(
                table(1L, "direct", OLD), table(2L, "nested", OLD), table(3L, "loose", OLD));
        Map<Long, UUID> memberships = new HashMap<>();
        memberships.put(1L, parent.getId());
        memberships.put(2L, child.getId());

        ResourceFolderDto tile = summaries(null, tables, memberships, "name").get(0);

        assertThat(tile.name()).isEqualTo("Sales");
        assertThat(tile.itemCount()).isEqualTo(2);
        assertThat(tile.subfolderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a table with no filing row belongs to no folder")
    void unfiledTablesAreNotCounted() {
        DataSourceFolder parent = folder("Sales", null);

        ResourceFolderDto tile = summaries(null, List.of(table(1L, "loose", OLD)), Map.of(), "name").get(0);

        assertThat(tile.itemCount()).isZero();
        assertThat(tile.preview()).isEmpty();
        assertThat(tile.lastModifiedAt()).isNull();
        assertThat(parent.getId()).isNotNull();
    }

    @Test
    @DisplayName("borrows the freshest change inside it")
    void aggregatesLastModified() {
        DataSourceFolder parent = folder("Sales", null);
        Map<Long, UUID> memberships = Map.of(1L, parent.getId(), 2L, parent.getId());

        ResourceFolderDto tile = summaries(
                null, List.of(table(1L, "a", OLD), table(2L, "b", RECENT)), memberships, "lastModified").get(0);

        assertThat(tile.lastModifiedAt()).isEqualTo(RECENT);
        assertThat(tile.lastActivityAt()).isNull();
    }

    @Test
    @DisplayName("draws at most one table per cell of the 3x2 face, newest first")
    void previewIsCappedAtSix() {
        DataSourceFolder parent = folder("Sales", null);
        List<DataSource> tables = new ArrayList<>();
        Map<Long, UUID> memberships = new HashMap<>();
        for (int i = 1; i <= 8; i++) {
            tables.add(table(i, "table-" + i, Instant.parse("2026-0" + (i % 9) + "-01T00:00:00Z")));
            memberships.put((long) i, parent.getId());
        }

        List<FolderPreviewItem> preview = summaries(null, tables, memberships, "name").get(0).preview();

        assertThat(preview).hasSize(6);
        assertThat(preview.get(0).name()).isEqualTo("table-8");
    }

    @Test
    @DisplayName("the tile names each table and its first REAL columns, not the grid's furniture")
    void previewCarriesTheTablesOwnColumns() {
        DataSourceFolder parent = folder("Sales", null);
        DataSource leads = table(1L, "Leads", OLD, List.of("checkbox", "index", "id", "email", "city", "score"));
        Map<Long, UUID> memberships = Map.of(1L, parent.getId());

        FolderPreviewItem item = summaries(null, List.of(leads), memberships, "name").get(0).preview().get(0);

        assertThat(item.name()).isEqualTo("Leads");
        // System columns (checkbox / index / id) describe no table, so they never show; and a
        // cell of the face only has room for two.
        assertThat(item.icons()).containsExactly(Map.of("name", "email"), Map.of("name", "city"));
    }

    @Test
    @DisplayName("a table with no columns of its own previews with none, rather than fake ones")
    void previewWithoutColumns() {
        DataSourceFolder parent = folder("Sales", null);
        Map<Long, UUID> memberships = Map.of(1L, parent.getId());

        FolderPreviewItem item = summaries(
                null, List.of(table(1L, "Empty", OLD, List.of("checkbox", "id"))), memberships, "name")
                .get(0).preview().get(0);

        assertThat(item.name()).isEqualTo("Empty");
        assertThat(item.icons()).isEmpty();
    }

    @Test
    @DisplayName("files only the ids the caller can actually see")
    void filingIsLimitedToVisibleTables() {
        DataSourceFolder target = folder("Target", null);
        when(folderRepository.assign(any(), any(), anyCollection(), anyCollection())).thenReturn(1);

        service.assignTables(ORG_SCOPE, target.getId(), List.of(1L, 99L), List.of(1L));

        verify(folderRepository).assign(eq(ORG_SCOPE), eq(target.getId()),
                eq(List.of(1L, 99L)), eq(List.of(1L)));
    }

    @Test
    @DisplayName("refuses to file into a folder of another workspace")
    void refusesForeignTargetFolder() {
        DataSourceFolder foreign = folder("Theirs", null);
        foreign.setOrganizationId("org-2");

        assertThatThrownBy(() -> service.assignTables(ORG_SCOPE, foreign.getId(), List.of(1L), List.of(1L)))
                .isInstanceOf(ResourceFolderException.class);
        verify(folderRepository, never()).assign(any(), any(), anyCollection(), anyCollection());
    }

    @Test
    @DisplayName("an empty selection touches nothing")
    void emptySelectionIsANoOp() {
        assertThat(service.assignTables(ORG_SCOPE, null, List.of(), List.of())).isZero();
        verify(folderRepository, never()).assign(any(), any(), anyCollection(), anyCollection());
    }

    @Test
    @DisplayName("deleting a folder drops the filings instead of deleting the tables")
    void deleteDropsFilings() {
        DataSourceFolder parent = folder("Parent", null);
        DataSourceFolder child = folder("Child", parent.getId());

        service.delete(parent.getId(), ORG_SCOPE);

        verify(folderRepository).clearFolders(org.mockito.ArgumentMatchers.argThat(ids ->
                ids.containsAll(List.of(parent.getId(), child.getId()))));
        verify(folderRepository).deleteAll(anyCollection());
    }

    @Test
    @DisplayName("existsInScope hides a folder that belongs to another workspace")
    void existsInScopeIsWorkspaceStrict() {
        DataSourceFolder mine = folder("Mine", null);
        DataSourceFolder theirs = folder("Theirs", null);
        theirs.setOrganizationId("org-2");

        assertThat(service.existsInScope(mine.getId(), ORG_SCOPE)).isTrue();
        assertThat(service.existsInScope(theirs.getId(), ORG_SCOPE)).isFalse();
        assertThat(Optional.of(mine)).isPresent();
    }
}
