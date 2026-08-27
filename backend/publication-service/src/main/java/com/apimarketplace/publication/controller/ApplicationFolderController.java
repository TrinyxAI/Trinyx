package com.apimarketplace.publication.controller;

import com.apimarketplace.common.folder.AbstractResourceFolderController;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.publication.domain.ApplicationFolderEntity;
import com.apimarketplace.publication.service.ApplicationFolderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Folders of the applications list. The lifecycle is the shared one in
 * {@link AbstractResourceFolderController}.
 *
 * <p>One endpoint of its own: {@code GET /memberships}. The other four lists carry the
 * filing on the resource row, so their list endpoint answers it for free; an application's
 * filing lives apart (a publication row is shared), and the page - which already holds its
 * whole set, published and acquired merged - asks for the map once and builds its own tiles.
 */
@RestController
@RequestMapping("/api/application-folders")
public class ApplicationFolderController extends AbstractResourceFolderController<ApplicationFolderEntity> {

    private final ApplicationFolderService folderService;

    public ApplicationFolderController(ApplicationFolderService folderService) {
        this.folderService = folderService;
    }

    /** {@code publicationId -> folderId} for the caller's workspace; absent = top level. */
    @GetMapping("/memberships")
    public ResponseEntity<?> memberships(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (isAnonymous(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Map<String, String> body = new LinkedHashMap<>();
        folderService.memberships(new FolderScope(userId, orgId))
                .forEach((publicationId, folderId) -> body.put(publicationId.toString(), folderId.toString()));
        return ResponseEntity.ok(Map.of("memberships", body));
    }

    @Override
    protected ResourceFolderCoreService<ApplicationFolderEntity> folders() {
        return folderService;
    }

    @Override
    protected String itemIdsField() {
        return "publicationIds";
    }

    @Override
    protected int assignItems(FolderScope scope, UUID folderId, List<String> resourceIds) {
        return folderService.assignApplications(scope, folderId, toUuids(resourceIds));
    }

    @Override
    protected String resourceLabel() {
        return "Application";
    }
}
