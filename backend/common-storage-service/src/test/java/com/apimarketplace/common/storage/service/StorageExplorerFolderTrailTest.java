package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.dto.FolderCrumbDto;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the folder-trail rebuild that lets the Files page survive a refresh.
 *
 * <p>The page keeps only the CURRENT folder in the URL, so on a reload it has an id and no path.
 * These tests pin what it gets back: the manual ancestry root-first, and - the part that is not
 * readable off the address - a RUN crumb numbered exactly the way the folder tile numbers it.</p>
 */
@DisplayName("StorageExplorerService - folder trail")
class StorageExplorerFolderTrailTest {

    private static final String ORG = "org-1";

    private StorageExplorerRepository repository;
    private StorageExplorerService service;

    @BeforeEach
    void setUp() {
        repository = mock(StorageExplorerRepository.class);
        service = new StorageExplorerService(repository);
    }

    @Nested
    @DisplayName("manual folders")
    class Manual {

        @Test
        @DisplayName("returns the ancestry root-first, ending with the folder itself")
        void returnsAncestryRootFirst() {
            UUID root = UUID.randomUUID();
            UUID child = UUID.randomUUID();
            when(repository.manualFolderAncestry(eq(ORG), eq(child), any())).thenReturn(List.of(
                    new StorageExplorerRepository.ManualCrumb(root, "Reports"),
                    new StorageExplorerRepository.ManualCrumb(child, "2026")));

            List<FolderCrumbDto> trail = service.manualFolderTrail(ORG, child, List.of());

            assertThat(trail).extracting(FolderCrumbDto::fileName).containsExactly("Reports", "2026");
            assertThat(trail).extracting(FolderCrumbDto::id)
                    .containsExactly(root.toString(), child.toString());
            // A manual crumb is not virtual - that is what makes the client label it by name.
            assertThat(trail).allMatch(c -> c.virtualId() == null && c.virtualKind() == null);
        }

        @Test
        @DisplayName("an unknown folder yields an empty trail, which reads as 'you are at the root'")
        void unknownFolderYieldsEmptyTrail() {
            UUID gone = UUID.randomUUID();
            when(repository.manualFolderAncestry(eq(ORG), eq(gone), any())).thenReturn(List.of());
            assertThat(service.manualFolderTrail(ORG, gone, List.of())).isEmpty();
        }

        @Test
        @DisplayName("the member deny-list is forwarded, so a restricted ancestor is never named")
        void forwardsDenyList() {
            // The listing hides a restricted folder; the breadcrumb must not disclose its NAME
            // instead. The repository ends the walk there, so the trail comes back truncated.
            UUID folder = UUID.randomUUID();
            UUID restricted = UUID.randomUUID();
            when(repository.manualFolderAncestry(eq(ORG), eq(folder), any()))
                    .thenReturn(List.of(new StorageExplorerRepository.ManualCrumb(folder, "2026")));

            List<FolderCrumbDto> trail = service.manualFolderTrail(ORG, folder, List.of(restricted));

            assertThat(trail).extracting(FolderCrumbDto::fileName).containsExactly("2026");
            ArgumentCaptor<java.util.Collection<UUID>> excluded = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(repository).manualFolderAncestry(eq(ORG), eq(folder), excluded.capture());
            assertThat(excluded.getValue()).containsExactly(restricted);
        }
    }

    @Nested
    @DisplayName("virtual workflow folders - ownership gate")
    class Ownership {

        @Test
        @DisplayName("a workflow this org owns no storage under yields NO trail, and no name to leak")
        void foreignWorkflowYieldsNothing() {
            // The id comes straight off a request param and the controller turns a WORKFLOW crumb
            // into a workflow NAME, so an id from another tenant must not produce a crumb at all.
            stubOwnedWorkflows("wf-mine");

            assertThat(service.virtualFolderTrail(ORG, VirtualFolderAddress.parse("wf:wf-someone-else"),
                    true, true, List.of())).isEmpty();
        }

        @Test
        @DisplayName("the gate applies at every depth, not just the workflow crumb")
        void foreignWorkflowYieldsNothingAtDepth() {
            stubOwnedWorkflows("wf-mine");

            assertThat(service.virtualFolderTrail(ORG, VirtualFolderAddress.parse("wf:wf-someone-else/e2/s0/i1"),
                    true, true, List.of())).isEmpty();
        }

        @Test
        @DisplayName("the deny-list is carried into the gate, so a restricted member gets no trail either")
        void denyListReachesTheGate() {
            UUID restricted = UUID.randomUUID();
            stubOwnedWorkflows("wf-1");

            service.virtualFolderTrail(ORG, VirtualFolderAddress.parse("wf:wf-1"), true, true, List.of(restricted));

            ArgumentCaptor<java.util.Collection<UUID>> excluded = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(repository).listVirtualGroups(eq(ORG), eq(VirtualFolderAddress.Level.WORKFLOW), isNull(),
                    isNull(), isNull(), isNull(), excluded.capture(), any());
            assertThat(excluded.getValue()).containsExactly(restricted);
        }
    }

    @Nested
    @DisplayName("virtual workflow folders")
    class Virtual {

        @Test
        @DisplayName("a null address (the root) has no trail and costs no query")
        void nullAddressHasNoTrail() {
            assertThat(service.virtualFolderTrail(ORG, null, true, true, List.of())).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("workflow → run → epoch comes back root-first, each crumb addressable")
        void buildsFullChain() {
            stubOwnedWorkflows("wf-1");
            stubRuns("run-a", "run-b", "run-c");
            VirtualFolderAddress address = VirtualFolderAddress.parse("wf:wf-1/rrun-c/e4");

            List<FolderCrumbDto> trail = service.virtualFolderTrail(ORG, address, true, true, List.of());

            assertThat(trail).extracting(FolderCrumbDto::virtualKind)
                    .containsExactly("WORKFLOW", "RUN", "EPOCH");
            assertThat(trail).extracting(FolderCrumbDto::id)
                    .containsExactly("wf:wf-1", "wf:wf-1/rrun-c", "wf:wf-1/rrun-c/e4");
        }

        @Test
        @DisplayName("the RUN crumb carries its POSITION, so the breadcrumb says the same 'Run N' as the tile")
        void runCrumbCarriesItsPosition() {
            // Runs come back oldest-first; run-c is the third, so the folder is labelled "Run 3".
            stubOwnedWorkflows("wf-1");
            stubRuns("run-a", "run-b", "run-c");
            VirtualFolderAddress address = VirtualFolderAddress.parse("wf:wf-1/rrun-c");

            List<FolderCrumbDto> trail = service.virtualFolderTrail(ORG, address, true, true, List.of());

            assertThat(trail).hasSize(2);
            assertThat(trail.get(1).virtualKind()).isEqualTo("RUN");
            assertThat(trail.get(1).epoch()).isEqualTo(3);
        }

        @Test
        @DisplayName("a run that no longer matches still renders, rather than blanking the breadcrumb")
        void unmatchedRunStillRenders() {
            stubOwnedWorkflows("wf-1");
            stubRuns("run-a");
            VirtualFolderAddress address = VirtualFolderAddress.parse("wf:wf-1/rrun-z");

            List<FolderCrumbDto> trail = service.virtualFolderTrail(ORG, address, true, true, List.of());

            assertThat(trail.get(1).epoch()).isEqualTo(1);
        }

        @Test
        @DisplayName("an EPOCH crumb shows the REAL epoch, not a position - same number as the tile")
        void epochCrumbCarriesRealEpoch() {
            stubOwnedWorkflows("wf-1");
            VirtualFolderAddress address = VirtualFolderAddress.parse("wf:wf-1/e7");

            List<FolderCrumbDto> trail = service.virtualFolderTrail(ORG, address, true, true, List.of());

            assertThat(trail).extracting(FolderCrumbDto::virtualKind).containsExactly("WORKFLOW", "EPOCH");
            assertThat(trail.get(1).epoch()).isEqualTo(7);
            // No run level, so the RUN listing is never queried - only the ownership gate ran.
            verify(repository, never()).listVirtualGroups(any(), eq(VirtualFolderAddress.Level.RUN),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a spawn / iteration crumb carries every coordinate the label needs")
        void iterationCrumbCarriesCoordinates() {
            stubOwnedWorkflows("wf-1");
            VirtualFolderAddress address = VirtualFolderAddress.parse("wf:wf-1/e2/s1/i3");

            List<FolderCrumbDto> trail = service.virtualFolderTrail(ORG, address, true, true, List.of());

            assertThat(trail).extracting(FolderCrumbDto::virtualKind)
                    .containsExactly("WORKFLOW", "EPOCH", "SPAWN", "ITERATION");
            FolderCrumbDto iteration = trail.get(3);
            assertThat(iteration.epoch()).isEqualTo(2);
            assertThat(iteration.spawn()).isEqualTo(1);
            assertThat(iteration.itemIndex()).isEqualTo(3);
        }
    }

    /** Runs of wf-1, oldest first - the order the "Run N" numbering is derived from. */
    private void stubRuns(String... runIds) {
        List<StorageExplorerRepository.VirtualGroup> groups = java.util.Arrays.stream(runIds)
                .map(id -> new StorageExplorerRepository.VirtualGroup(id, 1L, Instant.parse("2026-07-21T17:51:00Z")))
                .toList();
        when(repository.listVirtualGroups(eq(ORG), eq(VirtualFolderAddress.Level.RUN), anyString(),
                isNull(), isNull(), isNull(), any(), any())).thenReturn(groups);
    }

    /** The workflows this org owns visible storage under - the trail's ownership gate. */
    private void stubOwnedWorkflows(String... workflowIds) {
        List<StorageExplorerRepository.VirtualGroup> groups = java.util.Arrays.stream(workflowIds)
                .map(id -> new StorageExplorerRepository.VirtualGroup(id, 1L, Instant.parse("2026-07-21T17:51:00Z")))
                .toList();
        when(repository.listVirtualGroups(eq(ORG), eq(VirtualFolderAddress.Level.WORKFLOW), isNull(),
                isNull(), isNull(), isNull(), any(), any())).thenReturn(groups);
    }
}
