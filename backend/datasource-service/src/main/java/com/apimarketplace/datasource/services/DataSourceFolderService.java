package com.apimarketplace.datasource.services;

import com.apimarketplace.common.folder.FolderPreviewItem;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.common.folder.ResourceFolderDto;
import com.apimarketplace.common.folder.ResourceFolderOrdering;
import com.apimarketplace.common.folder.ResourceFolderStore;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.persistence.DataSourceFolderRepository;
import com.apimarketplace.datasource.persistence.DataSourceFolderRepository.DataSourceFolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Folders of the /app/tables list: the shared folder rules
 * ({@link ResourceFolderCoreService}) plus what is specific to tables - a tile shows the
 * tables it holds as small grids, the way the table cards show their first rows.
 *
 * <p>Membership lives in its own row here rather than on the table itself (see V451), so
 * every read takes the folder map the caller hands in - resolved once per request from the
 * scoped set, never per folder.
 */
@Service
public class DataSourceFolderService extends ResourceFolderCoreService<DataSourceFolder> {

    /** Items drawn inside one folder tile: the face is a 3x2 grid, so six of them fill it. */
    private static final int PREVIEW_SIZE = 6;

    /** Columns named per table on the tile - two is what a cell of the face has room for. */
    private static final int PREVIEW_COLUMNS = 2;

    /**
     * Columns every table has and that describe none of them (the grid's own furniture).
     * Mirrors {@code DataSourceDefaults.SYSTEM_COLUMNS}, which is private to that helper.
     */
    private static final Set<String> SYSTEM_COLUMN_FIELDS =
            Set.of("checkbox", "index", "id", "priority", "created_at");

    private final DataSourceFolderRepository folderRepository;

    public DataSourceFolderService(DataSourceFolderRepository folderRepository) {
        super(new DataSourceFolderStore(folderRepository));
        this.folderRepository = folderRepository;
    }

    /** Which folder each filed table of the workspace is in - one query, read once per request. */
    @Transactional(readOnly = true)
    public Map<Long, UUID> memberships(FolderScope scope) {
        return folderRepository.findMembershipsInScope(scope);
    }

    /**
     * The folders shown at one level of the list, each already carrying what its tile needs,
     * ordered by the same key the page sorts its rows with.
     *
     * @param scopedTables every table the caller can see (the paged endpoint's own set)
     * @param memberships  table id -> folder id, from {@link #memberships}
     */
    @Transactional(readOnly = true)
    public List<ResourceFolderDto> listFolderSummaries(FolderScope scope,
                                                       UUID parentFolderId,
                                                       List<DataSource> scopedTables,
                                                       Map<Long, UUID> memberships,
                                                       String sortKey) {
        List<DataSourceFolder> all = listAll(scope);
        List<DataSourceFolder> children = childrenOf(all, parentFolderId);
        if (children.isEmpty()) return List.of();

        Map<UUID, List<DataSource>> byFolder = groupByFolder(scopedTables, memberships);
        List<ResourceFolderDto> summaries = new ArrayList<>(children.size());
        for (DataSourceFolder folder : children) {
            Set<UUID> subtree = subtreeIds(all, folder.getId());
            List<DataSource> contained = new ArrayList<>();
            for (UUID folderId : subtree) {
                contained.addAll(byFolder.getOrDefault(folderId, List.of()));
            }
            summaries.add(summarize(folder, contained, childrenOf(all, folder.getId()).size()));
        }
        return ResourceFolderOrdering.sort(summaries, ResourceFolderOrdering.keyOf(sortKey));
    }

    /**
     * File tables into {@code folderId}, or back to the top level when it is {@code null}.
     *
     * @param visibleIds every table id the caller can see - the membership row carries no
     *                   tenant of its own, so this set IS the authorization
     * @return how many tables were actually re-filed
     */
    @Transactional
    public int assignTables(FolderScope scope, UUID folderId,
                            Collection<Long> tableIds, Collection<Long> visibleIds) {
        if (tableIds == null || tableIds.isEmpty()) return 0;
        if (folderId != null) {
            requireInScope(folderId, scope);
        }
        return folderRepository.assign(scope, folderId, tableIds, visibleIds);
    }

    /**
     * Delete a folder and its subfolders, dropping their filings FIRST. Both steps are one
     * transaction: a filing pointing at a folder that no longer exists would hide its table
     * from the top level AND from every folder.
     */
    @Override
    @Transactional
    public Set<UUID> delete(UUID folderId, FolderScope scope) {
        return super.delete(folderId, scope);
    }

    /** Whether the folder still exists, so a stale filter can fall back to the top level. */
    @Transactional(readOnly = true)
    public boolean existsInScope(UUID folderId, FolderScope scope) {
        return findInScope(folderId, scope).isPresent();
    }

    private ResourceFolderDto summarize(DataSourceFolder folder, List<DataSource> contained, int subfolderCount) {
        Instant lastModified = null;
        for (DataSource table : contained) {
            lastModified = latest(lastModified, table.updatedAt());
        }
        return new ResourceFolderDto(
                folder.getId(),
                folder.getName(),
                folder.getParentFolderId(),
                contained.size(),
                subfolderCount,
                lastModified,
                // A table has no run history of its own, so a folder of tables has nothing to
                // borrow for that key; the ordering falls back on the newest change.
                null,
                null,
                previewOf(contained),
                folder.getCreatedAt(),
                folder.getUpdatedAt());
    }

    /**
     * The few tables the tile draws: most recently changed first, each with its NAME and its
     * first real columns. A grid of anonymous rules says "these are tables"; the columns say
     * WHICH tables, which is the only reason to look at a folder without opening it.
     */
    private List<FolderPreviewItem> previewOf(List<DataSource> contained) {
        return contained.stream()
                .sorted(Comparator.comparing(
                        DataSource::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(PREVIEW_SIZE)
                .map(table -> new FolderPreviewItem(
                        String.valueOf(table.id()), table.name(), columnsOf(table), null, null))
                .toList();
    }

    /**
     * The table's own columns, system ones dropped (a checkbox and a row number describe no
     * table), capped at what a tile cell can show. Shaped as {@code [{name: "email"}, ...]}
     * so the preview item stays the same record for every list.
     */
    private static List<Map<String, Object>> columnsOf(DataSource table) {
        List<Map<String, Object>> columnOrder = table.columnOrder();
        if (columnOrder == null || columnOrder.isEmpty()) return List.of();
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> column : columnOrder) {
            Object field = column.get("field");
            if (field == null) continue;
            String name = field.toString();
            if (SYSTEM_COLUMN_FIELDS.contains(name)) continue;
            columns.add(Map.of("name", name));
            if (columns.size() == PREVIEW_COLUMNS) break;
        }
        return columns;
    }

    private static Map<UUID, List<DataSource>> groupByFolder(List<DataSource> tables, Map<Long, UUID> memberships) {
        Map<UUID, List<DataSource>> byFolder = new HashMap<>();
        for (DataSource table : tables) {
            UUID folderId = memberships.get(table.id());
            if (folderId == null) continue;
            byFolder.computeIfAbsent(folderId, k -> new ArrayList<>()).add(table);
        }
        return byFolder;
    }

    private static Instant latest(Instant current, Instant candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    /** Persistence for the shared folder logic, over this service's JDBC repository. */
    private record DataSourceFolderStore(DataSourceFolderRepository folders)
            implements ResourceFolderStore<DataSourceFolder> {

        @Override
        public List<DataSourceFolder> findAllInScope(FolderScope scope) {
            return folders.findAllInScope(scope);
        }

        @Override
        public Optional<DataSourceFolder> findById(UUID id) {
            return folders.findById(id);
        }

        @Override
        public DataSourceFolder newFolder() {
            return new DataSourceFolder();
        }

        @Override
        public DataSourceFolder save(DataSourceFolder folder) {
            return folders.save(folder);
        }

        @Override
        public void deleteAll(Collection<DataSourceFolder> toDelete) {
            folders.deleteAll(toDelete.stream().map(DataSourceFolder::getId).toList());
        }

        @Override
        public void detachResources(Collection<UUID> folderIds, FolderScope scope) {
            folders.clearFolders(folderIds);
        }
    }
}
