package com.apimarketplace.publication.service;

import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderException;
import com.apimarketplace.publication.domain.ApplicationFolderEntity;
import com.apimarketplace.publication.domain.ApplicationFolderItemEntity;
import com.apimarketplace.publication.repository.ApplicationFolderItemRepository;
import com.apimarketplace.publication.repository.ApplicationFolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Folders on the applications list. What is specific here is the filing: an application is a
 * PUBLICATION, a row shared between its publisher and every acquirer, so where it is filed
 * belongs to the workspace that filed it - keyed by (publication, workspace), with the
 * personal workspace stored as {@code ""} so the key stays well-defined.
 */
@DisplayName("ApplicationFolderService - per-workspace filing")
class ApplicationFolderServiceTest {

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final FolderScope ORG_SCOPE = new FolderScope(USER, ORG);
    private static final FolderScope PERSONAL_SCOPE = new FolderScope(USER, null);

    private ApplicationFolderRepository folderRepository;
    private ApplicationFolderItemRepository itemRepository;
    private ApplicationFolderService service;
    private final List<ApplicationFolderEntity> folders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        folderRepository = mock(ApplicationFolderRepository.class);
        itemRepository = mock(ApplicationFolderItemRepository.class);
        service = new ApplicationFolderService(folderRepository, itemRepository);
        folders.clear();
        when(folderRepository.findByOrganizationId(ORG)).thenReturn(folders);
        when(folderRepository.findByOwnerIdAndOrganizationIdIsNull(USER)).thenReturn(folders);
        when(folderRepository.findById(any())).thenAnswer(inv ->
                folders.stream().filter(f -> f.getId().equals(inv.getArgument(0))).findFirst());
    }

    private ApplicationFolderEntity folder(String name, UUID parentId) {
        ApplicationFolderEntity folder = new ApplicationFolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName(name);
        folder.setParentFolderId(parentId);
        folder.setOwnerId(USER);
        folder.setOrganizationId(ORG);
        folders.add(folder);
        return folder;
    }

    @Test
    @DisplayName("reads the filing of the active workspace as publication -> folder")
    void readsMemberships() {
        UUID publicationId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        when(itemRepository.findByOrganizationId(ORG)).thenReturn(List.of(
                new ApplicationFolderItemEntity(publicationId, ORG, folderId, USER)));

        Map<UUID, UUID> memberships = service.memberships(ORG_SCOPE);

        assertThat(memberships).containsExactly(Map.entry(publicationId, folderId));
    }

    @Test
    @DisplayName("a personal workspace is keyed by \"\", never by null")
    void personalWorkspaceUsesTheEmptyKey() {
        when(itemRepository.findByOrganizationId("")).thenReturn(List.of());

        service.memberships(PERSONAL_SCOPE);

        verify(itemRepository).findByOrganizationId("");
    }

    @Test
    @DisplayName("filing replaces the previous filing rather than adding a second one")
    void filingReplaces() {
        ApplicationFolderEntity target = folder("Marketing", null);
        UUID publicationId = UUID.randomUUID();

        int moved = service.assignApplications(ORG_SCOPE, target.getId(), List.of(publicationId));

        assertThat(moved).isEqualTo(1);
        verify(itemRepository).deleteByScopeAndPublicationIds(ORG, List.of(publicationId));
        verify(itemRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("filing at the top level just removes the filing row")
    void filingAtTopLevelDeletes() {
        UUID publicationId = UUID.randomUUID();
        when(itemRepository.deleteByScopeAndPublicationIds(eq(ORG), anyCollection())).thenReturn(1);

        int moved = service.assignApplications(ORG_SCOPE, null, List.of(publicationId));

        assertThat(moved).isEqualTo(1);
        verify(itemRepository).deleteByScopeAndPublicationIds(ORG, List.of(publicationId));
        verify(itemRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("refuses to file into a folder of another workspace")
    void refusesForeignTargetFolder() {
        ApplicationFolderEntity foreign = folder("Theirs", null);
        foreign.setOrganizationId("org-2");

        assertThatThrownBy(() ->
                service.assignApplications(ORG_SCOPE, foreign.getId(), List.of(UUID.randomUUID())))
                .isInstanceOf(ResourceFolderException.class);
        verify(itemRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("an empty selection touches nothing")
    void emptySelectionIsANoOp() {
        assertThat(service.assignApplications(ORG_SCOPE, null, List.of())).isZero();
        verify(itemRepository, never()).deleteByScopeAndPublicationIds(anyString(), anyCollection());
    }

    @Test
    @DisplayName("deleting a folder drops its filings instead of removing the applications")
    void deleteDropsFilings() {
        ApplicationFolderEntity parent = folder("Parent", null);
        ApplicationFolderEntity child = folder("Child", parent.getId());

        service.delete(parent.getId(), ORG_SCOPE);

        verify(itemRepository).deleteByScopeAndFolderIds(eq(ORG),
                org.mockito.ArgumentMatchers.argThat(ids ->
                        ids.containsAll(List.of(parent.getId(), child.getId()))));
        verify(folderRepository).deleteAll(anyCollection());
    }

    @Test
    @DisplayName("existsInScope hides a folder that belongs to another workspace")
    void existsInScopeIsWorkspaceStrict() {
        ApplicationFolderEntity mine = folder("Mine", null);
        ApplicationFolderEntity theirs = folder("Theirs", null);
        theirs.setOrganizationId("org-2");

        assertThat(service.existsInScope(mine.getId(), ORG_SCOPE)).isTrue();
        assertThat(service.existsInScope(theirs.getId(), ORG_SCOPE)).isFalse();
    }
}
