package com.apimarketplace.orchestrator.services.folder;

import com.apimarketplace.common.folder.FolderPreviewItem;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderDto;
import com.apimarketplace.common.folder.ResourceFolderException;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowFolderEntity;
import com.apimarketplace.orchestrator.repository.WorkflowFolderRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
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
 * What a workflow folder TILE says about itself: how much it holds (over its whole
 * subtree, not just its first level), how fresh that content is, and which workflows it
 * draws - plus the workspace branch every write has to take.
 */
@DisplayName("WorkflowFolderService - folder tiles and filing")
class WorkflowFolderServiceTest {

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final FolderScope ORG_SCOPE = new FolderScope(USER, ORG);
    private static final FolderScope PERSONAL_SCOPE = new FolderScope(USER, null);
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-08-01T00:00:00Z");

    private WorkflowFolderRepository folderRepository;
    private WorkflowRepository workflowRepository;
    private WorkflowFolderService service;
    private final List<WorkflowFolderEntity> folders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        folderRepository = mock(WorkflowFolderRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        service = new WorkflowFolderService(folderRepository, workflowRepository);
        folders.clear();
        when(folderRepository.findByOrganizationId(ORG)).thenReturn(folders);
        when(folderRepository.findByOwnerIdAndOrganizationIdIsNull(USER)).thenReturn(folders);
        when(folderRepository.findById(any())).thenAnswer(inv ->
                folders.stream().filter(f -> f.getId().equals(inv.getArgument(0))).findFirst());
    }

    private WorkflowFolderEntity folder(String name, UUID parentId) {
        WorkflowFolderEntity folder = new WorkflowFolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName(name);
        folder.setParentFolderId(parentId);
        folder.setOwnerId(USER);
        folder.setOrganizationId(ORG);
        folders.add(folder);
        return folder;
    }

    private WorkflowEntity workflow(String name, UUID folderId, Instant updatedAt, Instant lastRun) {
        WorkflowEntity workflow = new WorkflowEntity(USER, name, USER);
        workflow.setId(UUID.randomUUID());
        workflow.setFolderId(folderId);
        workflow.setUpdatedAt(updatedAt);
        workflow.setLastExecutedAt(lastRun);
        return workflow;
    }

    private List<ResourceFolderDto> summaries(UUID parentId, List<WorkflowEntity> workflows, String sort) {
        return service.listFolderSummaries(ORG_SCOPE, parentId, workflows, sort, null);
    }

    @Test
    @DisplayName("counts the whole subtree, so a folder of subfolders never reads as empty")
    void countsDeep() {
        WorkflowFolderEntity parent = folder("Parent", null);
        WorkflowFolderEntity child = folder("Child", parent.getId());
        List<WorkflowEntity> workflows = List.of(
                workflow("direct", parent.getId(), OLD, null),
                workflow("nested", child.getId(), OLD, null),
                workflow("loose", null, OLD, null));

        ResourceFolderDto tile = summaries(null, workflows, "name").get(0);

        assertThat(tile.name()).isEqualTo("Parent");
        assertThat(tile.itemCount()).isEqualTo(2);
        assertThat(tile.subfolderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("borrows the freshest change and the most recent run found inside it")
    void aggregatesFreshestActivity() {
        WorkflowFolderEntity parent = folder("Parent", null);
        List<WorkflowEntity> workflows = List.of(
                workflow("a", parent.getId(), OLD, OLD),
                workflow("b", parent.getId(), RECENT, RECENT));

        ResourceFolderDto tile = summaries(null, workflows, "name").get(0);

        assertThat(tile.lastModifiedAt()).isEqualTo(RECENT);
        assertThat(tile.lastActivityAt()).isEqualTo(RECENT);
    }

    @Test
    @DisplayName("an empty folder carries no dates at all, so the ordering can sink it")
    void emptyFolderHasNoDates() {
        folder("Empty", null);

        ResourceFolderDto tile = summaries(null, List.of(), "name").get(0);

        assertThat(tile.itemCount()).isZero();
        assertThat(tile.lastModifiedAt()).isNull();
        assertThat(tile.lastActivityAt()).isNull();
        assertThat(tile.preview()).isEmpty();
    }

    @Test
    @DisplayName("draws the most recently changed workflows, at most one per cell of the 3x2 face")
    void previewsNewestFirstCappedAtSix() {
        WorkflowFolderEntity parent = folder("Parent", null);
        List<WorkflowEntity> workflows = List.of(
                workflow("oldest", parent.getId(), Instant.parse("2026-01-01T00:00:00Z"), null),
                workflow("second", parent.getId(), Instant.parse("2026-02-01T00:00:00Z"), null),
                workflow("third", parent.getId(), Instant.parse("2026-03-01T00:00:00Z"), null),
                workflow("fourth", parent.getId(), Instant.parse("2026-04-01T00:00:00Z"), null),
                workflow("fifth", parent.getId(), Instant.parse("2026-05-01T00:00:00Z"), null),
                workflow("sixth", parent.getId(), Instant.parse("2026-06-01T00:00:00Z"), null),
                workflow("newest", parent.getId(), Instant.parse("2026-07-01T00:00:00Z"), null));

        List<FolderPreviewItem> preview = summaries(null, workflows, "name").get(0).preview();

        assertThat(preview).hasSize(6);
        assertThat(preview).extracting(FolderPreviewItem::name)
                .containsExactly("newest", "sixth", "fifth", "fourth", "third", "second");
    }

    @Test
    @DisplayName("a preview item carries the workflow's node icons, like the card next to it")
    void previewCarriesNodeIcons() {
        WorkflowFolderEntity parent = folder("Parent", null);
        WorkflowEntity workflow = workflow("iconned", parent.getId(), OLD, null);
        workflow.setNodeIcons(List.of(Map.of("nodeKind", "entry", "iconSlug", "webhook")));

        FolderPreviewItem item = summaries(null, List.of(workflow), "name").get(0).preview().get(0);

        assertThat(item.id()).isEqualTo(workflow.getId().toString());
        assertThat(item.icons()).hasSize(1);
        assertThat(item.icons().get(0)).containsEntry("iconSlug", "webhook");
    }

    @Test
    @DisplayName("a workflow saved before icons were extracted still previews, computed from its plan")
    void previewFallsBackToThePlan() {
        WorkflowFolderEntity parent = folder("Parent", null);
        WorkflowEntity workflow = workflow("legacy", parent.getId(), OLD, null);
        workflow.setNodeIcons(null);
        workflow.setPlan(Map.of("triggers", List.of(Map.of("label", "Webhook", "type", "webhook"))));

        FolderPreviewItem item = summaries(null, List.of(workflow), "name").get(0).preview().get(0);

        assertThat(item.icons()).isNotNull();
    }

    @Test
    @DisplayName("shows only the folders of the level asked for")
    void listsOneLevelOnly() {
        WorkflowFolderEntity parent = folder("Parent", null);
        folder("Child", parent.getId());

        assertThat(summaries(null, List.of(), "name")).extracting(ResourceFolderDto::name)
                .containsExactly("Parent");
        assertThat(summaries(parent.getId(), List.of(), "name")).extracting(ResourceFolderDto::name)
                .containsExactly("Child");
    }

    @Test
    @DisplayName("orders the tiles by the same key the page sorts its rows with")
    void ordersTilesLikeTheList() {
        WorkflowFolderEntity stale = folder("stale", null);
        WorkflowFolderEntity fresh = folder("fresh", null);
        List<WorkflowEntity> workflows = List.of(
                workflow("a", stale.getId(), OLD, null),
                workflow("b", fresh.getId(), RECENT, null));

        assertThat(summaries(null, workflows, "lastModified")).extracting(ResourceFolderDto::name)
                .containsExactly("fresh", "stale");
        assertThat(summaries(null, workflows, "name")).extracting(ResourceFolderDto::name)
                .containsExactly("fresh", "stale");
    }

    @Test
    @DisplayName("sums the runs of the subtree when the page sorts by run count")
    void sumsRunCountsWhenAsked() {
        WorkflowFolderEntity parent = folder("Parent", null);
        WorkflowEntity first = workflow("a", parent.getId(), OLD, null);
        WorkflowEntity second = workflow("b", parent.getId(), OLD, null);

        ResourceFolderDto tile = service.listFolderSummaries(ORG_SCOPE, null,
                List.of(first, second), "runCount",
                Map.of(first.getId(), 3L, second.getId(), 4L)).get(0);

        assertThat(tile.activityCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("leaves the run count out entirely when the page did not count runs")
    void leavesActivityCountNullOtherwise() {
        folder("Parent", null);

        assertThat(summaries(null, List.of(), "name").get(0).activityCount()).isNull();
    }

    @Test
    @DisplayName("filing into a folder takes the workspace branch of the update")
    void filingUsesTheOrganizationBranch() {
        WorkflowFolderEntity target = folder("Target", null);
        List<UUID> ids = List.of(UUID.randomUUID());

        service.assignWorkflows(ORG_SCOPE, target.getId(), ids);

        verify(workflowRepository).assignFolderForOrganization(ids, target.getId(), ORG);
        verify(workflowRepository, never()).assignFolderForOwner(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("filing in a personal workspace matches on the owner instead")
    void filingUsesTheOwnerBranchInPersonalWorkspace() {
        when(folderRepository.findById(any())).thenReturn(Optional.empty());
        List<UUID> ids = List.of(UUID.randomUUID());

        service.assignWorkflows(PERSONAL_SCOPE, null, ids);

        verify(workflowRepository).assignFolderForOwner(ids, null, USER);
        verify(workflowRepository, never()).assignFolderForOrganization(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("refuses to file into a folder of another workspace")
    void refusesForeignTargetFolder() {
        WorkflowFolderEntity foreign = folder("Theirs", null);
        foreign.setOrganizationId("org-2");

        assertThatThrownBy(() -> service.assignWorkflows(ORG_SCOPE, foreign.getId(), List.of(UUID.randomUUID())))
                .isInstanceOf(ResourceFolderException.class);
        verify(workflowRepository, never()).assignFolderForOrganization(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("an empty selection touches nothing")
    void emptySelectionIsANoOp() {
        assertThat(service.assignWorkflows(ORG_SCOPE, null, List.of())).isZero();
        verify(workflowRepository, never()).assignFolderForOrganization(anyCollection(), any(), any());
        verify(workflowRepository, never()).assignFolderForOwner(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("deleting a folder empties it back to the top level instead of deleting its workflows")
    void deleteSendsWorkflowsBackToTheTopLevel() {
        WorkflowFolderEntity parent = folder("Parent", null);
        WorkflowFolderEntity child = folder("Child", parent.getId());

        service.delete(parent.getId(), ORG_SCOPE);

        verify(workflowRepository).clearFolderForOrganization(
                argThatContains(parent.getId(), child.getId()), eq(ORG));
        verify(folderRepository).deleteAll(anyCollection());
    }

    @Test
    @DisplayName("existsInScope hides a folder that belongs to another workspace")
    void existsInScopeIsWorkspaceStrict() {
        WorkflowFolderEntity mine = folder("Mine", null);
        WorkflowFolderEntity theirs = folder("Theirs", null);
        theirs.setOrganizationId("org-2");

        assertThat(service.existsInScope(mine.getId(), ORG_SCOPE)).isTrue();
        assertThat(service.existsInScope(theirs.getId(), ORG_SCOPE)).isFalse();
    }

    private static java.util.Collection<UUID> argThatContains(UUID... expected) {
        return org.mockito.ArgumentMatchers.argThat(ids -> ids.containsAll(List.of(expected)));
    }
}
