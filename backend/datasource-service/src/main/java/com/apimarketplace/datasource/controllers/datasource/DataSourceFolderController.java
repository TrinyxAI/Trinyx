package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.common.folder.AbstractResourceFolderController;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.datasource.persistence.DataSourceFolderRepository.DataSourceFolder;
import com.apimarketplace.datasource.services.DataSourceFolderService;
import com.apimarketplace.datasource.services.DataSourceService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Folders of the tables list. The lifecycle is the shared one in
 * {@link AbstractResourceFolderController}; the folder TILES come back with
 * {@code GET /api/data-sources/paged}, which already holds the scoped tables they describe.
 *
 * <p>A table's filing lives in its own row (V451), which carries no tenant of the table
 * itself - so filing resolves the caller's visible ids first and writes only those.
 */
@RestController
@RequestMapping("/api/table-folders")
public class DataSourceFolderController extends AbstractResourceFolderController<DataSourceFolder> {

    private final DataSourceFolderService folderService;
    private final DataSourceService dataSourceService;

    public DataSourceFolderController(DataSourceFolderService folderService,
                                      DataSourceService dataSourceService) {
        this.folderService = folderService;
        this.dataSourceService = dataSourceService;
    }

    @Override
    protected ResourceFolderCoreService<DataSourceFolder> folders() {
        return folderService;
    }

    @Override
    protected String itemIdsField() {
        return "tableIds";
    }

    /**
     * Table ids are numbers, not UUIDs. The filing row carries no tenant of the table itself,
     * so the caller's VISIBLE ids are resolved first and only those are written - that set is
     * the authorization.
     */
    @Override
    protected int assignItems(FolderScope scope, UUID folderId, List<String> resourceIds) {
        List<Long> requested = new java.util.ArrayList<>(resourceIds.size());
        for (String id : resourceIds) {
            try {
                requested.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // Not an id of this list - skip it.
            }
        }
        if (requested.isEmpty()) return 0;
        List<Long> visible = dataSourceService
                .getDataSources(scope.userId(), scope.organizationId(), null)
                .stream().map(ds -> ds.id()).toList();
        return folderService.assignTables(scope, folderId, requested, visible);
    }

    @Override
    protected String resourceLabel() {
        return "Table";
    }
}
