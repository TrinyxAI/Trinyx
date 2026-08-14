package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.dto.ExplorerSort;
import com.apimarketplace.common.storage.dto.FolderCrumbDto;
import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the two additions the Files browser needs: a user-chosen ORDER, and a folder TRAIL it
 * can rebuild after a refresh.
 *
 * <p>Both are about degrading well. The sort params come from a URL or a stored preference, so
 * garbage must produce a listing (the default order), never a 400. The trail is asked for by a page
 * that only knows a folder id, so an id that no longer resolves must answer "you are at the root"
 * rather than an error the breadcrumb cannot render.</p>
 */
@DisplayName("StorageExplorerController - sort + folder trail")
class StorageExplorerControllerSortAndTrailTest {

    private StorageExplorerService explorerService;
    private StorageService storageService;
    private OrgAccessGuard orgAccessGuard;
    private WorkflowRepository workflowRepository;
    private StorageExplorerController controller;

    @BeforeEach
    void setUp() {
        explorerService = mock(StorageExplorerService.class);
        storageService = mock(StorageService.class);
        orgAccessGuard = mock(OrgAccessGuard.class);
        workflowRepository = mock(WorkflowRepository.class);
        lenient().when(orgAccessGuard.getRestrictedResourceIds(any(), any(), eq("file"), any())).thenReturn(Set.of());
        lenient().when(workflowRepository.findIdNamePairs(any())).thenReturn(List.of());
        controller = new StorageExplorerController(explorerService, storageService, orgAccessGuard, workflowRepository);
    }

    @Nested
    @DisplayName("sort params")
    class Sort {

        @Test
        @DisplayName("are forwarded to the virtual-scope listing")
        void forwardedToVirtualScope() {
            stubVirtualScope();

            controller.search("1", "org-1", "MEMBER", 0, 50, null, null, null, null, null, null, null,
                    null, true, true, "root", true, "size", "asc");

            assertThat(captureVirtualSort()).isEqualTo(new ExplorerSort(ExplorerSort.Key.SIZE, true));
        }

        @Test
        @DisplayName("are forwarded to the manual folder-scope listing")
        void forwardedToFolderScope() {
            UUID folder = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(eq(folder), any(), any()))
                    .thenReturn(java.util.Optional.of(folderEntity(folder)));
            when(explorerService.searchFolderScope(anyString(), anyString(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(ExplorerSort.class), any()))
                    .thenReturn(emptyPage());

            controller.search("1", "org-1", "MEMBER", 0, 50, null, null, null, null, null, null, null,
                    null, true, true, folder.toString(), false, "name", null);

            ArgumentCaptor<ExplorerSort> sort = ArgumentCaptor.forClass(ExplorerSort.class);
            verify(explorerService).searchFolderScope(anyString(), anyString(), any(), any(), any(), any(), any(),
                    any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), sort.capture(), any());
            // No direction given: "sort by name" must read A→Z.
            assertThat(sort.getValue()).isEqualTo(new ExplorerSort(ExplorerSort.Key.NAME, true));
        }

        @Test
        @DisplayName("an unusable value still lists files, in the default order")
        void unusableValueStillLists() {
            stubVirtualScope();

            ResponseEntity<Page<StorageExplorerDto>> response = controller.search("1", "org-1", "MEMBER", 0, 50,
                    null, null, null, null, null, null, null, null, true, true, "root", true,
                    "created_at; DROP TABLE storage.storage", "sideways");

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(captureVirtualSort()).isEqualTo(ExplorerSort.DEFAULT);
        }

        @Test
        @DisplayName("a NAME sort orders the ROOT's folder block, which SQL cannot do")
        void nameSortOrdersRootFolders() {
            // At the root the folder block mixes manual folders with computed workflow folders whose
            // NAME only exists after resolution here - so a name sort has to be applied at this layer
            // or the user picks "Name A-Z" and the folders visibly do not move.
            UUID zebra = UUID.randomUUID();
            UUID apple = UUID.randomUUID();
            when(explorerService.searchVirtualScope(anyString(), anyString(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(ExplorerSort.class), any()))
                    .thenReturn(new PageImpl<>(List.of(
                            virtualWorkflowFolder("wf:" + zebra, zebra),
                            manualFolder("Alpha"),
                            virtualWorkflowFolder("wf:" + apple, apple),
                            file("zzz.png")), PageRequest.of(0, 50), 1));
            when(workflowRepository.findIdNamePairs(any())).thenReturn(List.<Object[]>of(
                    new Object[]{zebra, "Zebra Workflow"},
                    new Object[]{apple, "Apple Workflow"}));

            Page<StorageExplorerDto> page = controller.search("1", "org-1", "MEMBER", 0, 50,
                    null, null, null, null, null, null, null, null, true, true, "root", true, "name", "asc").getBody();

            assertThat(page.getContent()).extracting(StorageExplorerControllerSortAndTrailTest::displayName)
                    .containsExactly("Alpha", "Apple Workflow", "Zebra Workflow", "zzz.png");
        }

        @Test
        @DisplayName("a DESC name sort reverses the same block")
        void nameSortDescending() {
            UUID wf = UUID.randomUUID();
            when(explorerService.searchVirtualScope(anyString(), anyString(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(ExplorerSort.class), any()))
                    .thenReturn(new PageImpl<>(List.of(
                            manualFolder("Alpha"),
                            virtualWorkflowFolder("wf:" + wf, wf)), PageRequest.of(0, 50), 0));
            when(workflowRepository.findIdNamePairs(any()))
                    .thenReturn(List.<Object[]>of(new Object[]{wf, "Zebra Workflow"}));

            Page<StorageExplorerDto> page = controller.search("1", "org-1", "MEMBER", 0, 50,
                    null, null, null, null, null, null, null, null, true, true, "root", true, "name", "desc").getBody();

            assertThat(page.getContent()).extracting(StorageExplorerControllerSortAndTrailTest::displayName)
                    .containsExactly("Zebra Workflow", "Alpha");
        }

        @Test
        @DisplayName("a DATE sort is left exactly as the service ordered it")
        void dateSortLeavesOrderAlone() {
            // The service already applied it (a folder sorts by its last activity), so re-ordering
            // here would fight it.
            UUID wf = UUID.randomUUID();
            when(explorerService.searchVirtualScope(anyString(), anyString(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(ExplorerSort.class), any()))
                    .thenReturn(new PageImpl<>(List.of(
                            virtualWorkflowFolder("wf:" + wf, wf),
                            manualFolder("Alpha")), PageRequest.of(0, 50), 0));
            when(workflowRepository.findIdNamePairs(any()))
                    .thenReturn(List.<Object[]>of(new Object[]{wf, "Zebra Workflow"}));

            Page<StorageExplorerDto> page = controller.search("1", "org-1", "MEMBER", 0, 50,
                    null, null, null, null, null, null, null, null, true, true, "root", true, "date", "desc").getBody();

            assertThat(page.getContent()).extracting(StorageExplorerControllerSortAndTrailTest::displayName)
                    .containsExactly("Zebra Workflow", "Alpha");
        }

        @Test
        @DisplayName("a nameless folder sorts LAST in BOTH directions, like the SQL side")
        void namelessFolderSortsLast() {
            // Reversing the COMPOSED comparator would negate its null handling too and float the
            // nameless folder to the top of a descending sort - the opposite of the NULLS LAST the
            // repository guarantees in both directions.
            UUID orphan = UUID.randomUUID();
            when(explorerService.searchVirtualScope(anyString(), anyString(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(ExplorerSort.class), any()))
                    .thenReturn(new PageImpl<>(List.of(
                            manualFolder(null),
                            manualFolder("Alpha"),
                            manualFolder("Zulu")), PageRequest.of(0, 50), 0));
            when(workflowRepository.findIdNamePairs(any())).thenReturn(List.of());

            assertThat(rootNameSort("asc")).containsExactly("Alpha", "Zulu", null);
            assertThat(rootNameSort("desc")).containsExactly("Zulu", "Alpha", null);
        }

        @Test
        @DisplayName("a later page carries no folders, so there is nothing to reorder and paging is untouched")
        void laterPageIsLeftAlone() {
            // Every root folder rides page 0, so page 2 is files only - the leading-folder scan
            // finds none and the page must come back exactly as the service built it.
            when(explorerService.searchVirtualScope(anyString(), anyString(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(ExplorerSort.class), any()))
                    .thenReturn(new PageImpl<>(List.of(file("zzz.png"), file("aaa.png")), PageRequest.of(2, 50), 500));

            Page<StorageExplorerDto> page = controller.search("1", "org-1", "MEMBER", 2, 50,
                    null, null, null, null, null, null, null, null, true, true, "root", true, "name", "asc").getBody();

            assertThat(page.getContent()).extracting(StorageExplorerControllerSortAndTrailTest::displayName)
                    .containsExactly("zzz.png", "aaa.png");
            // The total survives the pass untouched (500 is well past this page, so Spring does not
            // clip it to offset+size the way it would on a partial last page).
            assertThat(page.getTotalElements()).isEqualTo(500);
        }

        /** Run a root NAME sort in the given direction and return the display names in order. */
        private List<String> rootNameSort(String direction) {
            Page<StorageExplorerDto> page = controller.search("1", "org-1", "MEMBER", 0, 50,
                    null, null, null, null, null, null, null, null, true, true, "root", true, "name", direction).getBody();
            return page.getContent().stream()
                    .map(StorageExplorerControllerSortAndTrailTest::displayName)
                    .toList();
        }

        @Test
        @DisplayName("INSIDE a folder the ordering is left to SQL - the numbered run/epoch sequence stays put")
        void insideAFolderOrderIsUntouched() {
            when(explorerService.searchVirtualScope(anyString(), anyString(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(ExplorerSort.class), any()))
                    .thenReturn(new PageImpl<>(List.of(manualFolder("Zulu"), manualFolder("Alpha")),
                            PageRequest.of(0, 50), 0));

            Page<StorageExplorerDto> page = controller.search("1", "org-1", "MEMBER", 0, 50,
                    null, null, null, null, null, null, null, null, true, true, "wf:" + UUID.randomUUID(),
                    true, "name", "asc").getBody();

            assertThat(page.getContent()).extracting(StorageExplorerControllerSortAndTrailTest::displayName)
                    .containsExactly("Zulu", "Alpha");
        }

        private void stubVirtualScope() {
            when(explorerService.searchVirtualScope(anyString(), anyString(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(ExplorerSort.class), any()))
                    .thenReturn(emptyPage());
        }

        private ExplorerSort captureVirtualSort() {
            ArgumentCaptor<ExplorerSort> sort = ArgumentCaptor.forClass(ExplorerSort.class);
            verify(explorerService).searchVirtualScope(anyString(), anyString(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any(), any(), sort.capture(), any());
            return sort.getValue();
        }
    }

    @Nested
    @DisplayName("folder trail")
    class Trail {

        @Test
        @DisplayName("the root has no trail, and costs no lookup")
        void rootHasNoTrail() {
            assertThat(controller.folderTrail("1", "org-1", "MEMBER", "root", true, true).getBody()).isEmpty();
            assertThat(controller.folderTrail("1", "org-1", "MEMBER", null, true, true).getBody()).isEmpty();
            verify(explorerService, never()).manualFolderTrail(any(), any(), any());
        }

        @Test
        @DisplayName("a virtual address is answered with the workflow-tree trail, name resolved")
        void virtualAddressResolvesWorkflowName() {
            UUID workflowId = UUID.randomUUID();
            when(explorerService.virtualFolderTrail(eq("org-1"), any(), eq(true), eq(true), any()))
                    .thenReturn(List.of(
                            FolderCrumbDto.virtual("wf:" + workflowId, "WORKFLOW", null, null, null),
                            FolderCrumbDto.virtual("wf:" + workflowId + "/rrun-c", "RUN", 12, null, null)));
            when(workflowRepository.findIdNamePairs(any()))
                    .thenReturn(List.<Object[]>of(new Object[]{workflowId, "xAI Video Sequence"}));

            List<FolderCrumbDto> trail = controller.folderTrail("1", "org-1", "MEMBER",
                    "wf:" + workflowId + "/rrun-c", true, true).getBody();

            assertThat(trail).hasSize(2);
            // The storage boundary cannot read the workflows table, so the name is filled here.
            assertThat(trail.get(0).workflowName()).isEqualTo("xAI Video Sequence");
            assertThat(trail.get(1).epoch()).isEqualTo(12);
        }

        @Test
        @DisplayName("a deleted workflow leaves the name null rather than failing the breadcrumb")
        void deletedWorkflowLeavesNameNull() {
            UUID workflowId = UUID.randomUUID();
            when(explorerService.virtualFolderTrail(any(), any(), anyBoolean(), anyBoolean(), any()))
                    .thenReturn(List.of(FolderCrumbDto.virtual("wf:" + workflowId, "WORKFLOW", null, null, null)));
            when(workflowRepository.findIdNamePairs(any())).thenReturn(List.of());

            List<FolderCrumbDto> trail = controller.folderTrail("1", "org-1", "MEMBER",
                    "wf:" + workflowId, true, true).getBody();

            assertThat(trail).hasSize(1);
            assertThat(trail.get(0).workflowName()).isNull();
        }

        @Test
        @DisplayName("a folder UUID is answered with the manual ancestry")
        void manualFolderReturnsAncestry() {
            UUID folder = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(eq(folder), any(), any()))
                    .thenReturn(java.util.Optional.of(folderEntity(folder)));
            when(explorerService.manualFolderTrail(eq("org-1"), eq(folder), any()))
                    .thenReturn(List.of(FolderCrumbDto.manual(folder.toString(), "Reports")));

            List<FolderCrumbDto> trail = controller.folderTrail("1", "org-1", "MEMBER",
                    folder.toString(), true, true).getBody();

            assertThat(trail).singleElement()
                    .satisfies(c -> assertThat(c.fileName()).isEqualTo("Reports"));
        }

        @Test
        @DisplayName("a folder outside the caller's scope is refused - a trail may not name what the listing hides")
        void outOfScopeFolderRefused() {
            // Same gate the LISTING applies before it lists a folder's children. Without it the
            // breadcrumb would hand back the names of another workspace's folders.
            UUID foreign = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(eq(foreign), any(), any()))
                    .thenReturn(java.util.Optional.empty());

            List<FolderCrumbDto> trail = controller.folderTrail("1", "org-1", "MEMBER",
                    foreign.toString(), true, true).getBody();

            assertThat(trail).isEmpty();
            verify(explorerService, never()).manualFolderTrail(any(), any(), any());
        }

        @Test
        @DisplayName("a FILE id is refused - only a folder has a trail")
        void fileIdRefused() {
            UUID file = UUID.randomUUID();
            com.apimarketplace.common.storage.domain.StorageEntity notAFolder =
                    new com.apimarketplace.common.storage.domain.StorageEntity();
            notAFolder.setId(file);
            notAFolder.setIsFolder(false);
            when(storageService.getEntityByIdForScope(eq(file), any(), any()))
                    .thenReturn(java.util.Optional.of(notAFolder));

            assertThat(controller.folderTrail("1", "org-1", "MEMBER", file.toString(), true, true).getBody())
                    .isEmpty();
            verify(explorerService, never()).manualFolderTrail(any(), any(), any());
        }

        @Test
        @DisplayName("the member restricted-id deny-list is forwarded to both branches")
        void forwardsDenyList() {
            UUID restricted = UUID.randomUUID();
            UUID folder = UUID.randomUUID();
            when(orgAccessGuard.getRestrictedResourceIds(any(), any(), eq("file"), any()))
                    .thenReturn(Set.of(restricted.toString()));
            when(storageService.getEntityByIdForScope(eq(folder), any(), any()))
                    .thenReturn(java.util.Optional.of(folderEntity(folder)));
            when(explorerService.manualFolderTrail(any(), any(), any())).thenReturn(List.of());
            when(explorerService.virtualFolderTrail(any(), any(), anyBoolean(), anyBoolean(), any()))
                    .thenReturn(List.of());

            controller.folderTrail("1", "org-1", "MEMBER", folder.toString(), true, true);
            controller.folderTrail("1", "org-1", "MEMBER", "wf:" + UUID.randomUUID(), true, true);

            ArgumentCaptor<java.util.Collection<UUID>> manual = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(explorerService).manualFolderTrail(any(), any(), manual.capture());
            assertThat(manual.getValue()).containsExactly(restricted);

            ArgumentCaptor<java.util.Collection<UUID>> virtual = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(explorerService).virtualFolderTrail(any(), any(), anyBoolean(), anyBoolean(), virtual.capture());
            assertThat(virtual.getValue()).containsExactly(restricted);
        }

        @Test
        @DisplayName("an id that is neither a virtual address nor a UUID answers 'root', not an error")
        void garbageIdDegradesToRoot() {
            ResponseEntity<List<FolderCrumbDto>> response =
                    controller.folderTrail("1", "org-1", "MEMBER", "not-a-folder", true, true);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isEmpty();
            verify(explorerService, never()).manualFolderTrail(any(), any(), any());
        }
    }

    private static Page<StorageExplorerDto> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
    }

    /** A computed WORKFLOW folder: no name of its own, resolved by the controller. */
    private static StorageExplorerDto virtualWorkflowFolder(String virtualId, UUID workflowId) {
        return StorageExplorerDto.virtualFolder(virtualId, "WORKFLOW", workflowId.toString(), null,
                null, null, null, 1, List.of(), Instant.parse("2026-07-21T17:51:00Z"));
    }

    /** A persisted (manual) folder row, which carries its own name. */
    private static StorageExplorerDto manualFolder(String name) {
        return new StorageExplorerDto(UUID.randomUUID(), "FOLDER", "FOLDER", name, null, null, "0 B",
                Instant.parse("2026-07-21T17:51:00Z"), null, null, null, null, null, null, null, null,
                true, null, 0, List.of(), null, null, null, null);
    }

    private static StorageExplorerDto file(String name) {
        return new StorageExplorerDto(UUID.randomUUID(), "S3_FILE", "S3_FILE", name, "image/png", 10, "10 B",
                Instant.parse("2026-07-21T17:51:00Z"), null, null, null, null, null, null, "k/1", null,
                false, null, null, null, null, null, null, null);
    }

    /** What the client would LABEL this row with - a workflow folder by its workflow name. */
    private static String displayName(StorageExplorerDto dto) {
        return "WORKFLOW".equals(dto.virtualKind()) ? dto.workflowName() : dto.fileName();
    }

    private static com.apimarketplace.common.storage.domain.StorageEntity folderEntity(UUID id) {
        com.apimarketplace.common.storage.domain.StorageEntity e =
                new com.apimarketplace.common.storage.domain.StorageEntity();
        e.setId(id);
        e.setIsFolder(true);
        e.setFileName("Reports");
        e.setTenantId("1");
        return e;
    }
}
