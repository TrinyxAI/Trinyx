package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.folder.ResourceFolderDto;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowFolderEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowFolderRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.folder.WorkflowFolderService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/workflows} once folders exist (V448). What is pinned here is the set of
 * rules that decide WHICH workflows come back:
 *  - no {@code folderId} at all: nothing changes, the endpoint still lists everything (the
 *    board and the pickers call it that way);
 *  - {@code folderId=root}: only the workflows filed nowhere;
 *  - {@code folderId=<id>}: that folder's own workflows;
 *  - a search looks EVERYWHERE, folder filter and folder tiles both step aside;
 *  - a folder that no longer exists falls back to the top level and says so, instead of
 *    leaving the page permanently empty.
 */
@DisplayName("WorkflowListController.listWorkflows - folder filtering")
class WorkflowListControllerFoldersTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";

    private WorkflowManagementService workflowService;
    private WorkflowFolderRepository folderRepository;
    private WorkflowListController controller;
    private final List<WorkflowFolderEntity> folders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowManagementService.class);
        folderRepository = mock(WorkflowFolderRepository.class);
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        PublicationClient publicationClient = mock(PublicationClient.class);
        folders.clear();
        lenient().when(folderRepository.findByOrganizationId(ORG)).thenReturn(folders);
        lenient().when(folderRepository.findById(any())).thenAnswer(inv ->
                folders.stream().filter(f -> f.getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(publicationClient.findPublicationStatusesByWorkflowIds(any(), any()))
                .thenReturn(Map.of());
        controller = new WorkflowListController(
                workflowRepository,
                mock(WorkflowRunRepository.class),
                mock(SignalWaitRepository.class),
                mock(TriggerClient.class),
                publicationClient,
                workflowService,
                mock(WorkflowBoardService.class),
                mock(OrgAccessGuard.class),
                new WorkflowFolderService(folderRepository, workflowRepository));
    }

    private WorkflowFolderEntity folder(String name, UUID parentId) {
        WorkflowFolderEntity folder = new WorkflowFolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName(name);
        folder.setParentFolderId(parentId);
        folder.setOwnerId(TENANT);
        folder.setOrganizationId(ORG);
        folders.add(folder);
        return folder;
    }

    private WorkflowEntity workflow(String name, UUID folderId) {
        WorkflowEntity workflow = new WorkflowEntity(TENANT, name, TENANT);
        workflow.setId(UUID.randomUUID());
        workflow.setFolderId(folderId);
        workflow.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return workflow;
    }

    private void given(WorkflowEntity... workflows) {
        when(workflowService.listWorkflows(TENANT, ORG, null)).thenReturn(List.of(workflows));
    }

    private Map<String, Object> list(String folderId, boolean includeFolders, String q) {
        ResponseEntity<Map<String, Object>> response = controller.listWorkflows(
                TENANT, ORG, null, null, null, 25, 0, q, null, null, folderId, includeFolders);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<String> names(Map<String, Object> body) {
        return ((List<WorkflowSummary>) body.get("workflows")).stream().map(WorkflowSummary::name).toList();
    }

    @SuppressWarnings("unchecked")
    private List<ResourceFolderDto> tiles(Map<String, Object> body) {
        return (List<ResourceFolderDto>) body.get("folders");
    }

    @Test
    @DisplayName("without a folderId the listing is unchanged - every workflow, filed or not")
    void noFolderParameterListsEverything() {
        WorkflowFolderEntity marketing = folder("Marketing", null);
        given(workflow("filed", marketing.getId()), workflow("loose", null));

        assertThat(names(list(null, false, null))).containsExactlyInAnyOrder("filed", "loose");
    }

    @Test
    @DisplayName("folderId=root keeps only the workflows filed nowhere")
    void rootKeepsUnfiledWorkflows() {
        WorkflowFolderEntity marketing = folder("Marketing", null);
        given(workflow("filed", marketing.getId()), workflow("loose", null));

        assertThat(names(list("root", false, null))).containsExactly("loose");
    }

    @Test
    @DisplayName("a folder id keeps that folder's own workflows, not those of its subfolders")
    void folderKeepsItsOwnWorkflows() {
        WorkflowFolderEntity parent = folder("Parent", null);
        WorkflowFolderEntity child = folder("Child", parent.getId());
        given(workflow("direct", parent.getId()), workflow("nested", child.getId()), workflow("loose", null));

        assertThat(names(list(parent.getId().toString(), false, null))).containsExactly("direct");
    }

    @Test
    @DisplayName("totalCount counts the folder, not the whole workspace, so the pager is right")
    void totalCountFollowsTheFilter() {
        WorkflowFolderEntity marketing = folder("Marketing", null);
        given(workflow("filed", marketing.getId()), workflow("loose", null), workflow("loose-2", null));

        assertThat(list(marketing.getId().toString(), false, null).get("totalCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("a search looks everywhere: the folder filter and the tiles both step aside")
    void searchIgnoresTheFolderFilter() {
        WorkflowFolderEntity marketing = folder("Marketing", null);
        given(workflow("needle", marketing.getId()), workflow("haystack", null));

        Map<String, Object> body = list("root", true, "needle");

        assertThat(names(body)).containsExactly("needle");
        assertThat(tiles(body)).isEmpty();
    }

    @Test
    @DisplayName("a folder that no longer exists shows the top level and asks the caller to drop it")
    void missingFolderFallsBackToTheTopLevel() {
        given(workflow("loose", null), workflow("filed", UUID.randomUUID()));

        Map<String, Object> body = list(UUID.randomUUID().toString(), true, null);

        assertThat(names(body)).containsExactly("loose");
        assertThat(body.get("folderMissing")).isEqualTo(true);
    }

    @Test
    @DisplayName("an unparseable folderId shows the top level instead of taking the page down")
    void garbageFolderIdShowsTheTopLevel() {
        WorkflowFolderEntity marketing = folder("Marketing", null);
        given(workflow("filed", marketing.getId()), workflow("loose", null));

        assertThat(names(list("not-a-uuid", false, null))).containsExactly("loose");
    }

    @Test
    @DisplayName("includeFolders adds the tiles of the current level, with their subtree counts")
    void includeFoldersReturnsTilesForTheLevel() {
        WorkflowFolderEntity parent = folder("Parent", null);
        WorkflowFolderEntity child = folder("Child", parent.getId());
        given(workflow("direct", parent.getId()), workflow("nested", child.getId()));

        List<ResourceFolderDto> tiles = tiles(list("root", true, null));

        assertThat(tiles).hasSize(1);
        assertThat(tiles.get(0).name()).isEqualTo("Parent");
        assertThat(tiles.get(0).itemCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the folders are left out entirely unless the caller asks for them")
    void foldersAreOptional() {
        folder("Marketing", null);
        given(workflow("loose", null));

        assertThat(list("root", false, null)).doesNotContainKey("folders");
    }

    @Test
    @DisplayName("inside a folder the response carries the trail that leads to it")
    void breadcrumbIsReturned() {
        WorkflowFolderEntity parent = folder("Parent", null);
        WorkflowFolderEntity child = folder("Child", parent.getId());
        given(workflow("nested", child.getId()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trail =
                (List<Map<String, Object>>) list(child.getId().toString(), true, null).get("folderTrail");

        assertThat(trail).extracting(crumb -> crumb.get("name")).containsExactly("Parent", "Child");
    }

    @Test
    @DisplayName("at the top level the trail is empty")
    void trailIsEmptyAtTheTopLevel() {
        folder("Parent", null);
        given(workflow("loose", null));

        assertThat((List<?>) list("root", true, null).get("folderTrail")).isEmpty();
    }

    @Test
    @DisplayName("every row says which folder it is filed in")
    void rowsCarryTheirFolder() {
        WorkflowFolderEntity marketing = folder("Marketing", null);
        given(workflow("filed", marketing.getId()));

        @SuppressWarnings("unchecked")
        List<WorkflowSummary> rows = (List<WorkflowSummary>) list(null, false, null).get("workflows");

        assertThat(rows.get(0).folderId()).isEqualTo(marketing.getId());
    }
}
