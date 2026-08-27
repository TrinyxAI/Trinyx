package com.apimarketplace.interfaces.controller;

import com.apimarketplace.common.folder.AbstractResourceFolderController;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.interfaces.domain.InterfaceFolderEntity;
import com.apimarketplace.interfaces.service.InterfaceFolderService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Folders of the interface list. The lifecycle is the shared one in
 * {@link AbstractResourceFolderController}; the folder TILES come back with
 * {@code GET /api/interfaces/paged}, which already holds the scoped pages they describe.
 */
@RestController
@RequestMapping("/api/interface-folders")
public class InterfaceFolderController extends AbstractResourceFolderController<InterfaceFolderEntity> {

    private final InterfaceFolderService folderService;

    public InterfaceFolderController(InterfaceFolderService folderService) {
        this.folderService = folderService;
    }

    @Override
    protected ResourceFolderCoreService<InterfaceFolderEntity> folders() {
        return folderService;
    }

    @Override
    protected String itemIdsField() {
        return "interfaceIds";
    }

    @Override
    protected int assignItems(FolderScope scope, UUID folderId, List<String> resourceIds) {
        return folderService.assignInterfaces(scope, folderId, toUuids(resourceIds));
    }

    @Override
    protected String resourceLabel() {
        return "Interface";
    }
}
