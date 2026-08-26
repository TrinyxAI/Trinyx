package com.apimarketplace.orchestrator.controllers.folder;

import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderException;
import com.apimarketplace.orchestrator.domain.WorkflowFolderEntity;
import com.apimarketplace.orchestrator.services.folder.WorkflowFolderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST contract of the workflow folders: who may write (a VIEWER may not), what a caller
 * gets back when a folder is not theirs (404, never 403 - a workspace must not learn that
 * another one's folder exists), and how the two client-side conveniences behave: a
 * {@code null} folder means the top level, and a bad id is a 404 rather than a 500.
 */
@DisplayName("WorkflowFolderController - folder REST contract")
class WorkflowFolderControllerTest {

    private static final String USER = "user-1";
    private static final String ORG = "org-1";

    private WorkflowFolderService folderService;
    private WorkflowFolderController controller;

    @BeforeEach
    void setUp() {
        folderService = mock(WorkflowFolderService.class);
        controller = new WorkflowFolderController(folderService);
    }

    private static WorkflowFolderEntity folder(String name) {
        WorkflowFolderEntity folder = new WorkflowFolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName(name);
        folder.setOwnerId(USER);
        folder.setOrganizationId(ORG);
        return folder;
    }

    /** {@code Map.of} rejects null values, and "file at the top level" IS a null folder. */
    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("an anonymous caller gets 401, never a folder")
    void anonymousIsUnauthorized() {
        assertThat(controller.listFolders(null, ORG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.createFolder("  ", ORG, null, body("name", "X")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a VIEWER cannot create, rename, move, delete or file")
    void viewerCannotWrite() {
        UUID id = UUID.randomUUID();

        assertThat(controller.createFolder(USER, ORG, "VIEWER", body("name", "X")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.renameFolder(USER, ORG, "viewer", id, body("name", "X")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.moveFolder(USER, ORG, "VIEWER", id, body("parentFolderId", null)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.deleteFolder(USER, ORG, "VIEWER", id).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.assignItems(USER, ORG, "VIEWER",
                body("folderId", null, "workflowIds", List.of(id.toString()))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(folderService, never()).create(any(), any(), any());
        verify(folderService, never()).assignWorkflows(any(), any(), anyCollection());
    }

    @Test
    @DisplayName("creating returns 201 with the folder row")
    void createReturnsCreated() {
        WorkflowFolderEntity created = folder("Marketing");
        when(folderService.create(any(), eq("Marketing"), eq(null))).thenReturn(created);

        ResponseEntity<?> response = controller.createFolder(USER, ORG, "MEMBER", body("name", "Marketing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) response.getBody()).get("name")).isEqualTo("Marketing");
        assertThat(((Map<?, ?>) response.getBody()).get("id")).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("creating passes the caller's workspace to the service")
    void createCarriesTheWorkspace() {
        when(folderService.create(any(), any(), any())).thenReturn(folder("X"));

        controller.createFolder(USER, ORG, "MEMBER", body("name", "X"));

        verify(folderService).create(eq(new FolderScope(USER, ORG)), eq("X"), eq(null));
    }

    @Test
    @DisplayName("filing with folderId null sends the workflows back to the top level")
    void nullFolderMeansTopLevel() {
        UUID workflowId = UUID.randomUUID();
        when(folderService.assignWorkflows(any(), eq(null), anyCollection())).thenReturn(1);

        ResponseEntity<?> response = controller.assignItems(USER, ORG, "MEMBER",
                body("folderId", null, "workflowIds", List.of(workflowId.toString())));

        assertThat(((Map<?, ?>) response.getBody()).get("moved")).isEqualTo(1);
        verify(folderService).assignWorkflows(
                eq(new FolderScope(USER, ORG)), eq(null), eq(List.of(workflowId)));
    }

    @Test
    @DisplayName("filing without ids is a 400, not a silent no-op")
    void filingWithoutIdsIsRejected() {
        ResponseEntity<?> response = controller.assignItems(USER, ORG, "MEMBER",
                body("folderId", null, "workflowIds", List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(folderService, never()).assignWorkflows(any(), any(), anyCollection());
    }

    @Test
    @DisplayName("deleting answers with every folder id that went away, so the page can refresh")
    void deleteReturnsTheRemovedIds() {
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        when(folderService.delete(eq(parent), any())).thenReturn(new java.util.LinkedHashSet<>(
                Arrays.asList(parent, child)));

        ResponseEntity<?> response = controller.deleteFolder(USER, ORG, "MEMBER", parent);

        List<?> removed = (List<?>) ((Map<?, ?>) response.getBody()).get("deletedFolderIds");
        assertThat(removed).hasSize(2);
        assertThat(removed.get(0)).isEqualTo(parent);
        assertThat(removed.get(1)).isEqualTo(child);
    }

    @Test
    @DisplayName("an unknown folder is a 404 - existence never leaks across workspaces")
    void unknownFolderIsNotFound() {
        ResponseEntity<Map<String, String>> response = controller.handleFolderError(
                new ResourceFolderException(ResourceFolderException.Code.NOT_FOUND, "Folder not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    @DisplayName("a bad name is a 400 and a self-swallowing move is a 409")
    void errorsMapToTheirStatus() {
        assertThat(controller.handleFolderError(new ResourceFolderException(
                ResourceFolderException.Code.INVALID_NAME, "blank")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.handleFolderError(new ResourceFolderException(
                ResourceFolderException.Code.PARENT_NOT_FOUND, "no parent")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.handleFolderError(new ResourceFolderException(
                ResourceFolderException.Code.CYCLE, "cycle")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a folder id that is not a UUID is a 404, not a 500")
    void malformedFolderIdIsNotFound() {
        ResponseEntity<?> response;
        try {
            controller.assignItems(USER, ORG, "MEMBER",
                    body("folderId", "not-a-uuid", "workflowIds", List.of(UUID.randomUUID().toString())));
            response = null;
        } catch (ResourceFolderException e) {
            response = controller.handleFolderError(e);
        }

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
