package com.apimarketplace.common.folder;

import com.apimarketplace.common.scope.ScopeGuard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The folder rules, written once for the five list pages (workflows, agents, tables,
 * interfaces, applications). Everything that is the same whatever the resource is -
 * workspace scope, naming, nesting without cycles, deleting a folder without deleting
 * what it holds - lives here; the per-service subclass only supplies a
 * {@link ResourceFolderStore}.
 *
 * <p>The whole folder tree of a workspace is read at once and walked in memory. That is
 * deliberate: the tree is small (tens of rows), every caller needs more than one level of
 * it (the breadcrumb, the "move to..." picker, the subtree a delete has to cascade over),
 * and one query beats a recursive walk that issues one per level.
 *
 * @param <E> the concrete folder entity of the owning service
 */
public class ResourceFolderCoreService<E extends AbstractResourceFolderEntity> {

    private final ResourceFolderStore<E> store;

    public ResourceFolderCoreService(ResourceFolderStore<E> store) {
        this.store = store;
    }

    protected ResourceFolderStore<E> store() {
        return store;
    }

    // ===================== Reads =====================

    /** Every folder of the caller's workspace, name A->Z (the tree is assembled by the caller). */
    public List<E> listAll(FolderScope scope) {
        List<E> folders = new ArrayList<>(store.findAllInScope(scope));
        folders.sort(Comparator.comparing(
                f -> f.getName() == null ? "" : f.getName(), String.CASE_INSENSITIVE_ORDER));
        return folders;
    }

    /** The direct children of {@code parentId} ({@code null} = the top level). */
    public List<E> childrenOf(List<E> allFolders, UUID parentId) {
        return allFolders.stream()
                .filter(f -> java.util.Objects.equals(f.getParentFolderId(), parentId))
                .toList();
    }

    /**
     * One folder, only if it is in the caller's active workspace. An out-of-scope folder
     * is reported as absent so its existence never leaks across workspaces.
     */
    public Optional<E> findInScope(UUID folderId, FolderScope scope) {
        if (folderId == null) return Optional.empty();
        return store.findById(folderId).filter(f -> isInScope(f, scope));
    }

    /** Same, but throwing the NOT_FOUND the REST layer turns into a 404. */
    public E requireInScope(UUID folderId, FolderScope scope) {
        return findInScope(folderId, scope).orElseThrow(() -> new ResourceFolderException(
                ResourceFolderException.Code.NOT_FOUND, "Folder not found: " + folderId));
    }

    /**
     * Root -> ... -> folder, so a page can render the trail it navigated into. A broken
     * chain (a parent that was deleted concurrently) simply ends the trail instead of
     * failing the read.
     */
    public List<E> breadcrumb(List<E> allFolders, UUID folderId) {
        Map<UUID, E> byId = indexById(allFolders);
        Deque<E> trail = new ArrayDeque<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = folderId;
        while (current != null && seen.add(current)) {
            E folder = byId.get(current);
            if (folder == null) break;
            trail.addFirst(folder);
            current = folder.getParentFolderId();
        }
        return new ArrayList<>(trail);
    }

    /**
     * {@code folderId} plus every folder below it. Used to count/preview a folder over its
     * whole subtree, and to cascade a delete.
     */
    public Set<UUID> subtreeIds(List<E> allFolders, UUID folderId) {
        Map<UUID, List<E>> byParent = indexByParent(allFolders);
        Set<UUID> ids = new LinkedHashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(folderId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (!ids.add(current)) continue;
            for (E child : byParent.getOrDefault(current, List.of())) {
                queue.add(child.getId());
            }
        }
        return ids;
    }

    // ===================== Writes =====================

    /** Create a folder under {@code parentId} ({@code null} = top level). */
    public E create(FolderScope scope, String name, UUID parentId) {
        String cleanName = validateName(name);
        if (parentId != null) {
            requireParent(parentId, scope);
        }
        E folder = store.newFolder();
        folder.setName(cleanName);
        folder.setParentFolderId(parentId);
        folder.setOwnerId(scope.userId());
        if (scope.hasOrganization()) {
            folder.setOrganizationId(scope.organizationId());
        }
        return store.save(folder);
    }

    public E rename(UUID folderId, FolderScope scope, String name) {
        String cleanName = validateName(name);
        E folder = requireInScope(folderId, scope);
        folder.setName(cleanName);
        return store.save(folder);
    }

    /**
     * Re-parent a folder. {@code newParentId} {@code null} moves it back to the top level.
     * Refuses to put a folder inside itself or inside one of its own descendants, which
     * would detach that whole branch from the tree.
     */
    public E move(UUID folderId, FolderScope scope, UUID newParentId) {
        E folder = requireInScope(folderId, scope);
        if (newParentId == null) {
            folder.setParentFolderId(null);
            return store.save(folder);
        }
        if (folderId.equals(newParentId)) {
            throw new ResourceFolderException(
                    ResourceFolderException.Code.CYCLE, "A folder cannot be moved into itself");
        }
        requireParent(newParentId, scope);
        if (subtreeIds(listAll(scope), folderId).contains(newParentId)) {
            throw new ResourceFolderException(ResourceFolderException.Code.CYCLE,
                    "A folder cannot be moved into one of its own subfolders");
        }
        folder.setParentFolderId(newParentId);
        return store.save(folder);
    }

    /**
     * Delete a folder and every folder below it. The resources filed in them are NOT
     * deleted: they go back to the top level of the list page. Deleting a way of filing
     * things must never delete the things.
     *
     * @return the ids that were removed (the folder plus its descendants)
     */
    public Set<UUID> delete(UUID folderId, FolderScope scope) {
        requireInScope(folderId, scope);
        List<E> all = listAll(scope);
        Set<UUID> doomed = subtreeIds(all, folderId);
        store.detachResources(doomed, scope);
        store.deleteAll(all.stream().filter(f -> doomed.contains(f.getId())).toList());
        return doomed;
    }

    // ===================== Helpers =====================

    protected boolean isInScope(E folder, FolderScope scope) {
        return ScopeGuard.isInStrictScope(
                scope.userId(), scope.organizationId(), folder.getOwnerId(), folder.getOrganizationId());
    }

    private void requireParent(UUID parentId, FolderScope scope) {
        findInScope(parentId, scope).orElseThrow(() -> new ResourceFolderException(
                ResourceFolderException.Code.PARENT_NOT_FOUND, "Parent folder not found: " + parentId));
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResourceFolderException(
                    ResourceFolderException.Code.INVALID_NAME, "Folder name cannot be empty");
        }
        String trimmed = name.trim();
        if (trimmed.length() > AbstractResourceFolderEntity.MAX_NAME_LENGTH) {
            throw new ResourceFolderException(ResourceFolderException.Code.INVALID_NAME,
                    "Folder name cannot exceed " + AbstractResourceFolderEntity.MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private Map<UUID, E> indexById(List<E> folders) {
        Map<UUID, E> byId = new HashMap<>();
        for (E folder : folders) byId.put(folder.getId(), folder);
        return byId;
    }

    private Map<UUID, List<E>> indexByParent(List<E> folders) {
        Map<UUID, List<E>> byParent = new HashMap<>();
        for (E folder : folders) {
            UUID parent = folder.getParentFolderId();
            if (parent == null) continue;
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(folder);
        }
        return byParent;
    }
}
