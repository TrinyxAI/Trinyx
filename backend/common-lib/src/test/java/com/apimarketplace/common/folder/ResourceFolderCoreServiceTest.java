package com.apimarketplace.common.folder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The folder rules shared by the five list pages: workspace isolation, naming, nesting
 * without cycles, and a delete that removes the filing without removing what was filed.
 */
@DisplayName("ResourceFolderCoreService - shared folder rules")
class ResourceFolderCoreServiceTest {

    private static final FolderScope ORG_SCOPE = new FolderScope("user-1", "org-1");
    private static final FolderScope PERSONAL_SCOPE = new FolderScope("user-1", null);

    private InMemoryStore store;
    private ResourceFolderCoreService<TestFolder> service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        service = new ResourceFolderCoreService<>(store);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("stamps the caller's workspace on the new folder")
        void stampsWorkspace() {
            TestFolder folder = service.create(ORG_SCOPE, "Marketing", null);

            assertThat(folder.getName()).isEqualTo("Marketing");
            assertThat(folder.getOwnerId()).isEqualTo("user-1");
            assertThat(folder.getOrganizationId()).isEqualTo("org-1");
            assertThat(folder.getParentFolderId()).isNull();
        }

        @Test
        @DisplayName("leaves the organization null in a personal workspace")
        void personalWorkspaceHasNoOrganization() {
            TestFolder folder = service.create(PERSONAL_SCOPE, "Perso", null);

            assertThat(folder.getOrganizationId()).isNull();
            assertThat(folder.getOwnerId()).isEqualTo("user-1");
        }

        @Test
        @DisplayName("trims the name")
        void trimsName() {
            assertThat(service.create(ORG_SCOPE, "  Spaced  ", null).getName()).isEqualTo("Spaced");
        }

        @Test
        @DisplayName("refuses a blank name")
        void refusesBlankName() {
            assertThatThrownBy(() -> service.create(ORG_SCOPE, "   ", null))
                    .isInstanceOf(ResourceFolderException.class)
                    .extracting(e -> ((ResourceFolderException) e).getCode())
                    .isEqualTo(ResourceFolderException.Code.INVALID_NAME);
        }

        @Test
        @DisplayName("refuses a name longer than the column")
        void refusesOverlongName() {
            String tooLong = "x".repeat(AbstractResourceFolderEntity.MAX_NAME_LENGTH + 1);

            assertThatThrownBy(() -> service.create(ORG_SCOPE, tooLong, null))
                    .isInstanceOf(ResourceFolderException.class)
                    .extracting(e -> ((ResourceFolderException) e).getCode())
                    .isEqualTo(ResourceFolderException.Code.INVALID_NAME);
        }

        @Test
        @DisplayName("refuses a parent that lives in another workspace")
        void refusesForeignParent() {
            TestFolder foreign = service.create(new FolderScope("user-2", "org-2"), "Theirs", null);

            assertThatThrownBy(() -> service.create(ORG_SCOPE, "Mine", foreign.getId()))
                    .isInstanceOf(ResourceFolderException.class)
                    .extracting(e -> ((ResourceFolderException) e).getCode())
                    .isEqualTo(ResourceFolderException.Code.PARENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("a folder of another workspace reads as not found, never as forbidden")
        void foreignFolderIsNotFound() {
            TestFolder foreign = service.create(new FolderScope("user-2", "org-2"), "Theirs", null);

            assertThat(service.findInScope(foreign.getId(), ORG_SCOPE)).isEmpty();
            assertThatThrownBy(() -> service.requireInScope(foreign.getId(), ORG_SCOPE))
                    .isInstanceOf(ResourceFolderException.class)
                    .extracting(e -> ((ResourceFolderException) e).getCode())
                    .isEqualTo(ResourceFolderException.Code.NOT_FOUND);
        }

        @Test
        @DisplayName("an org folder is invisible from the same user's personal workspace")
        void orgFolderHiddenFromPersonalWorkspace() {
            TestFolder orgFolder = service.create(ORG_SCOPE, "Team", null);

            assertThat(service.findInScope(orgFolder.getId(), PERSONAL_SCOPE)).isEmpty();
        }

        @Test
        @DisplayName("listAll returns the workspace's folders, name A->Z")
        void listsAlphabetically() {
            service.create(ORG_SCOPE, "beta", null);
            service.create(ORG_SCOPE, "Alpha", null);
            service.create(new FolderScope("user-2", "org-2"), "Foreign", null);

            assertThat(service.listAll(ORG_SCOPE)).extracting(TestFolder::getName)
                    .containsExactly("Alpha", "beta");
        }
    }

    @Nested
    @DisplayName("move")
    class Move {

        @Test
        @DisplayName("re-parents under another folder")
        void reparents() {
            TestFolder parent = service.create(ORG_SCOPE, "Parent", null);
            TestFolder child = service.create(ORG_SCOPE, "Child", null);

            assertThat(service.move(child.getId(), ORG_SCOPE, parent.getId()).getParentFolderId())
                    .isEqualTo(parent.getId());
        }

        @Test
        @DisplayName("a null parent brings the folder back to the top level")
        void movesToTopLevel() {
            TestFolder parent = service.create(ORG_SCOPE, "Parent", null);
            TestFolder child = service.create(ORG_SCOPE, "Child", parent.getId());

            assertThat(service.move(child.getId(), ORG_SCOPE, null).getParentFolderId()).isNull();
        }

        @Test
        @DisplayName("refuses to move a folder into itself")
        void refusesSelfParent() {
            TestFolder folder = service.create(ORG_SCOPE, "Folder", null);

            assertThatThrownBy(() -> service.move(folder.getId(), ORG_SCOPE, folder.getId()))
                    .isInstanceOf(ResourceFolderException.class)
                    .extracting(e -> ((ResourceFolderException) e).getCode())
                    .isEqualTo(ResourceFolderException.Code.CYCLE);
        }

        @Test
        @DisplayName("refuses to move a folder into its own descendant (which would detach the branch)")
        void refusesDescendantParent() {
            TestFolder grandParent = service.create(ORG_SCOPE, "A", null);
            TestFolder parent = service.create(ORG_SCOPE, "B", grandParent.getId());
            TestFolder child = service.create(ORG_SCOPE, "C", parent.getId());

            assertThatThrownBy(() -> service.move(grandParent.getId(), ORG_SCOPE, child.getId()))
                    .isInstanceOf(ResourceFolderException.class)
                    .extracting(e -> ((ResourceFolderException) e).getCode())
                    .isEqualTo(ResourceFolderException.Code.CYCLE);
            assertThat(store.byId(grandParent.getId()).getParentFolderId()).isNull();
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("removes the folder and every folder below it")
        void cascadesOverSubtree() {
            TestFolder root = service.create(ORG_SCOPE, "A", null);
            TestFolder child = service.create(ORG_SCOPE, "B", root.getId());
            TestFolder grandChild = service.create(ORG_SCOPE, "C", child.getId());
            TestFolder sibling = service.create(ORG_SCOPE, "D", null);

            Set<UUID> deleted = service.delete(root.getId(), ORG_SCOPE);

            assertThat(deleted).containsExactlyInAnyOrder(root.getId(), child.getId(), grandChild.getId());
            assertThat(service.listAll(ORG_SCOPE)).extracting(TestFolder::getId).containsExactly(sibling.getId());
        }

        @Test
        @DisplayName("sends the content back to the top level instead of deleting it")
        void detachesContentRatherThanDeletingIt() {
            TestFolder root = service.create(ORG_SCOPE, "A", null);
            TestFolder child = service.create(ORG_SCOPE, "B", root.getId());

            service.delete(root.getId(), ORG_SCOPE);

            assertThat(store.detached).containsExactlyInAnyOrder(root.getId(), child.getId());
            assertThat(store.detachedScope).isEqualTo(ORG_SCOPE);
        }

        @Test
        @DisplayName("refuses a folder from another workspace")
        void refusesForeignFolder() {
            TestFolder foreign = service.create(new FolderScope("user-2", "org-2"), "Theirs", null);

            assertThatThrownBy(() -> service.delete(foreign.getId(), ORG_SCOPE))
                    .isInstanceOf(ResourceFolderException.class);
            assertThat(store.detached).isEmpty();
        }
    }

    @Nested
    @DisplayName("tree helpers")
    class TreeHelpers {

        @Test
        @DisplayName("breadcrumb runs root -> folder")
        void breadcrumbIsOrdered() {
            TestFolder a = service.create(ORG_SCOPE, "A", null);
            TestFolder b = service.create(ORG_SCOPE, "B", a.getId());
            TestFolder c = service.create(ORG_SCOPE, "C", b.getId());

            assertThat(service.breadcrumb(service.listAll(ORG_SCOPE), c.getId()))
                    .extracting(TestFolder::getName)
                    .containsExactly("A", "B", "C");
        }

        @Test
        @DisplayName("a parent deleted from under a folder ends the trail instead of failing the read")
        void breadcrumbToleratesBrokenChain() {
            TestFolder a = service.create(ORG_SCOPE, "A", null);
            TestFolder b = service.create(ORG_SCOPE, "B", a.getId());
            store.remove(a.getId());

            assertThat(service.breadcrumb(service.listAll(ORG_SCOPE), b.getId()))
                    .extracting(TestFolder::getName)
                    .containsExactly("B");
        }

        @Test
        @DisplayName("childrenOf(null) is the top level")
        void childrenOfRoot() {
            TestFolder top = service.create(ORG_SCOPE, "Top", null);
            service.create(ORG_SCOPE, "Nested", top.getId());

            assertThat(service.childrenOf(service.listAll(ORG_SCOPE), null))
                    .extracting(TestFolder::getName).containsExactly("Top");
        }

        @Test
        @DisplayName("subtreeIds includes the folder itself and every descendant")
        void subtreeIncludesSelf() {
            TestFolder a = service.create(ORG_SCOPE, "A", null);
            TestFolder b = service.create(ORG_SCOPE, "B", a.getId());
            service.create(ORG_SCOPE, "Elsewhere", null);

            assertThat(service.subtreeIds(service.listAll(ORG_SCOPE), a.getId()))
                    .containsExactlyInAnyOrder(a.getId(), b.getId());
        }
    }

    // ===================== Fixtures =====================

    /** A concrete folder, standing in for the per-service entities. */
    static class TestFolder extends AbstractResourceFolderEntity {
    }

    /** The store the rules run against, so they can be tested without a database. */
    static class InMemoryStore implements ResourceFolderStore<TestFolder> {
        private final Map<UUID, TestFolder> rows = new LinkedHashMap<>();
        final List<UUID> detached = new ArrayList<>();
        FolderScope detachedScope;

        @Override
        public List<TestFolder> findAllInScope(FolderScope scope) {
            return rows.values().stream()
                    .filter(f -> scope.hasOrganization()
                            ? scope.organizationId().equals(f.getOrganizationId())
                            : f.getOrganizationId() == null && scope.userId().equals(f.getOwnerId()))
                    .toList();
        }

        @Override
        public Optional<TestFolder> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public TestFolder newFolder() {
            return new TestFolder();
        }

        @Override
        public TestFolder save(TestFolder folder) {
            if (folder.getId() == null) folder.setId(UUID.randomUUID());
            rows.put(folder.getId(), folder);
            return folder;
        }

        @Override
        public void deleteAll(Collection<TestFolder> folders) {
            folders.forEach(f -> rows.remove(f.getId()));
        }

        @Override
        public void detachResources(Collection<UUID> folderIds, FolderScope scope) {
            detached.addAll(folderIds);
            detachedScope = scope;
        }

        TestFolder byId(UUID id) {
            return rows.get(id);
        }

        void remove(UUID id) {
            rows.remove(id);
        }
    }
}
