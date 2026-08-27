package com.apimarketplace.orchestrator.controllers.folder;

import com.apimarketplace.common.folder.AbstractResourceFolderController;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.orchestrator.domain.WorkflowFolderEntity;
import com.apimarketplace.orchestrator.services.folder.WorkflowFolderService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Folders of the workflow list. The lifecycle (create / rename / move / delete / file) is
 * the shared one in {@link AbstractResourceFolderController}; this class only says which
 * list it belongs to. The folder TILES come back with {@code GET /api/workflows}, which
 * already holds the scoped workflows they are computed from.
 */
@RestController
@RequestMapping("/api/workflow-folders")
public class WorkflowFolderController extends AbstractResourceFolderController<WorkflowFolderEntity> {

    private final WorkflowFolderService folderService;

    public WorkflowFolderController(WorkflowFolderService folderService) {
        this.folderService = folderService;
    }

    @Override
    protected ResourceFolderCoreService<WorkflowFolderEntity> folders() {
        return folderService;
    }

    @Override
    protected String itemIdsField() {
        return "workflowIds";
    }

    @Override
    protected int assignItems(FolderScope scope, UUID folderId, List<String> resourceIds) {
        return folderService.assignWorkflows(scope, folderId, toUuids(resourceIds));
    }

    @Override
    protected String resourceLabel() {
        return "Workflow";
    }
}
