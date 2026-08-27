package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentFolderEntity;
import com.apimarketplace.agent.service.AgentFolderService;
import com.apimarketplace.common.folder.AbstractResourceFolderController;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Folders of the agent list. The lifecycle is the shared one in
 * {@link AbstractResourceFolderController}; the folder TILES come back with
 * {@code GET /api/agents/paged}, which already holds the scoped agents they describe.
 *
 * <p>Sibling of {@code /api/skill-folders}, which organises SKILLS in the same service -
 * two lists, two sets of folders, no relation between them.
 */
@RestController
@RequestMapping("/api/agent-folders")
public class AgentFolderController extends AbstractResourceFolderController<AgentFolderEntity> {

    private final AgentFolderService folderService;

    public AgentFolderController(AgentFolderService folderService) {
        this.folderService = folderService;
    }

    @Override
    protected ResourceFolderCoreService<AgentFolderEntity> folders() {
        return folderService;
    }

    @Override
    protected String itemIdsField() {
        return "agentIds";
    }

    @Override
    protected int assignItems(FolderScope scope, UUID folderId, List<String> resourceIds) {
        return folderService.assignAgents(scope, folderId, toUuids(resourceIds));
    }

    @Override
    protected String resourceLabel() {
        return "Agent";
    }
}
