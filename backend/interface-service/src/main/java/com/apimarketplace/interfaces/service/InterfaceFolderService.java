package com.apimarketplace.interfaces.service;

import com.apimarketplace.common.folder.FolderPreviewItem;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.common.folder.ResourceFolderDto;
import com.apimarketplace.common.folder.ResourceFolderOrdering;
import com.apimarketplace.common.folder.ResourceFolderStore;
import com.apimarketplace.interfaces.domain.InterfaceFolderEntity;
import com.apimarketplace.interfaces.repository.InterfaceFolderRepository;
import com.apimarketplace.interfaces.repository.InterfaceListView;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Folders of the /app/interface list: the shared folder rules
 * ({@link ResourceFolderCoreService}) plus what is specific to pages - a tile draws a small
 * silhouette per page IN THE PAGE'S OWN SHAPE (a phone-sized page and a dashboard do not
 * look alike), so a folder tells you what kind of pages it holds at a glance.
 *
 * <p>The aggregates are computed from the SAME scoped set the paged endpoint already loaded
 * (the light projection, never the HTML blobs), so a folder can never advertise a count that
 * includes a page the caller is not allowed to see - and previewing costs no extra query.
 */
@Service
public class InterfaceFolderService extends ResourceFolderCoreService<InterfaceFolderEntity> {

    /** Items drawn inside one folder tile: the face is a 3x2 grid, so six of them fill it. */
    private static final int PREVIEW_SIZE = 6;

    private final InterfaceRepository interfaceRepository;

    public InterfaceFolderService(InterfaceFolderRepository folderRepository,
                                  InterfaceRepository interfaceRepository) {
        super(new InterfaceFolderStore(folderRepository, interfaceRepository));
        this.interfaceRepository = interfaceRepository;
    }

    /**
     * The folders shown at one level of the list, each already carrying what its tile needs,
     * ordered by the same key the page sorts its rows with.
     *
     * @param scopedViews every page the caller can see, as the light projection the paged
     *                    endpoint loads
     */
    @Transactional(readOnly = true)
    public List<ResourceFolderDto> listFolderSummaries(FolderScope scope,
                                                       UUID parentFolderId,
                                                       List<InterfaceListView> scopedViews,
                                                       String sortKey) {
        List<InterfaceFolderEntity> all = listAll(scope);
        List<InterfaceFolderEntity> children = childrenOf(all, parentFolderId);
        if (children.isEmpty()) return List.of();

        Map<UUID, List<InterfaceListView>> byFolder = groupByFolder(scopedViews);
        List<ResourceFolderDto> summaries = new ArrayList<>(children.size());
        for (InterfaceFolderEntity folder : children) {
            Set<UUID> subtree = subtreeIds(all, folder.getId());
            List<InterfaceListView> contained = new ArrayList<>();
            for (UUID folderId : subtree) {
                contained.addAll(byFolder.getOrDefault(folderId, List.of()));
            }
            summaries.add(summarize(folder, contained, childrenOf(all, folder.getId()).size()));
        }
        return ResourceFolderOrdering.sort(summaries, ResourceFolderOrdering.keyOf(sortKey));
    }

    /**
     * File pages into {@code folderId}, or back to the top level when it is {@code null}.
     *
     * @return how many pages were actually re-filed
     */
    @Transactional
    public int assignInterfaces(FolderScope scope, UUID folderId, Collection<UUID> interfaceIds) {
        if (interfaceIds == null || interfaceIds.isEmpty()) return 0;
        if (folderId != null) {
            requireInScope(folderId, scope);
        }
        return scope.hasOrganization()
                ? interfaceRepository.assignFolderForOrganization(interfaceIds, folderId, scope.organizationId())
                : interfaceRepository.assignFolderForOwner(interfaceIds, folderId, scope.userId());
    }

    /**
     * Delete a folder and its subfolders, emptying them back to the top level FIRST. Both
     * steps are one transaction: a page pointing at a folder that no longer exists would be
     * invisible at the top level AND invisible in any folder.
     */
    @Override
    @Transactional
    public Set<UUID> delete(UUID folderId, FolderScope scope) {
        return super.delete(folderId, scope);
    }

    /** Whether the folder still exists, so a stale filter can fall back to the top level. */
    @Transactional(readOnly = true)
    public boolean existsInScope(UUID folderId, FolderScope scope) {
        return findInScope(folderId, scope).isPresent();
    }

    private ResourceFolderDto summarize(InterfaceFolderEntity folder,
                                        List<InterfaceListView> contained,
                                        int subfolderCount) {
        Instant lastModified = null;
        for (InterfaceListView view : contained) {
            lastModified = latest(lastModified, view.getUpdatedAt());
        }
        return new ResourceFolderDto(
                folder.getId(),
                folder.getName(),
                folder.getParentFolderId(),
                contained.size(),
                subfolderCount,
                lastModified,
                // A page has no run history of its own, so there is nothing for a folder of
                // pages to borrow for that key; the ordering falls back on the newest change.
                null,
                null,
                previewOf(contained),
                folder.getCreatedAt(),
                folder.getUpdatedAt());
    }

    /**
     * The few pages the tile draws: most recently changed first. The {@code subtitle} carries
     * the page's format, which is what the tile shapes each silhouette from.
     */
    private List<FolderPreviewItem> previewOf(List<InterfaceListView> contained) {
        return contained.stream()
                .sorted(Comparator.comparing(
                        InterfaceListView::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(PREVIEW_SIZE)
                .map(view -> new FolderPreviewItem(
                        view.getId().toString(), view.getName(), null, null, view.getFormat()))
                .toList();
    }

    private static Map<UUID, List<InterfaceListView>> groupByFolder(List<InterfaceListView> views) {
        Map<UUID, List<InterfaceListView>> byFolder = new HashMap<>();
        for (InterfaceListView view : views) {
            UUID folderId = view.getFolderId();
            if (folderId == null) continue;
            byFolder.computeIfAbsent(folderId, k -> new ArrayList<>()).add(view);
        }
        return byFolder;
    }

    private static Instant latest(Instant current, Instant candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    /**
     * Persistence for the shared folder logic. Both read branches are workspace-strict,
     * mirroring {@code ScopeGuard.isInStrictScope}, which the core service re-applies per folder.
     */
    private record InterfaceFolderStore(InterfaceFolderRepository folders, InterfaceRepository interfaces)
            implements ResourceFolderStore<InterfaceFolderEntity> {

        @Override
        public List<InterfaceFolderEntity> findAllInScope(FolderScope scope) {
            return scope.hasOrganization()
                    ? folders.findByOrganizationId(scope.organizationId())
                    : folders.findByOwnerIdAndOrganizationIdIsNull(scope.userId());
        }

        @Override
        public Optional<InterfaceFolderEntity> findById(UUID id) {
            return folders.findById(id);
        }

        @Override
        public InterfaceFolderEntity newFolder() {
            return new InterfaceFolderEntity();
        }

        @Override
        public InterfaceFolderEntity save(InterfaceFolderEntity folder) {
            return folders.save(folder);
        }

        @Override
        public void deleteAll(Collection<InterfaceFolderEntity> toDelete) {
            folders.deleteAll(toDelete);
        }

        @Override
        public void detachResources(Collection<UUID> folderIds, FolderScope scope) {
            if (folderIds.isEmpty()) return;
            if (scope.hasOrganization()) {
                interfaces.clearFolderForOrganization(folderIds, scope.organizationId());
            } else {
                interfaces.clearFolderForOwner(folderIds, scope.userId());
            }
        }
    }
}
