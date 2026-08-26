package com.apimarketplace.publication.service;

import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.common.folder.ResourceFolderStore;
import com.apimarketplace.publication.domain.ApplicationFolderEntity;
import com.apimarketplace.publication.domain.ApplicationFolderItemEntity;
import com.apimarketplace.publication.repository.ApplicationFolderItemRepository;
import com.apimarketplace.publication.repository.ApplicationFolderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Folders of the /app/applications list: the shared folder rules
 * ({@link ResourceFolderCoreService}) plus the one thing that is specific here - an
 * application is a PUBLICATION, a row shared between its publisher and everyone who acquired
 * it, so where it is filed belongs to the workspace that filed it and lives in its own row.
 *
 * <p>The tiles themselves are built by the page, which already holds the whole application
 * set (own published + acquired, merged and enriched client-side); this service hands it the
 * folders and the filing map and keeps the writes honest.
 */
@Service
public class ApplicationFolderService extends ResourceFolderCoreService<ApplicationFolderEntity> {

    private final ApplicationFolderItemRepository itemRepository;

    public ApplicationFolderService(ApplicationFolderRepository folderRepository,
                                    ApplicationFolderItemRepository itemRepository) {
        super(new ApplicationFolderStore(folderRepository, itemRepository));
        this.itemRepository = itemRepository;
    }

    /**
     * Which folder each filed application of the workspace is in
     * ({@code publicationId -> folderId}). An application filed nowhere is simply absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> memberships(FolderScope scope) {
        Map<UUID, UUID> memberships = new HashMap<>();
        for (ApplicationFolderItemEntity item : itemRepository.findByOrganizationId(scopeKey(scope))) {
            memberships.put(item.getPublicationId(), item.getFolderId());
        }
        return memberships;
    }

    /**
     * File applications into {@code folderId}, or back to the top level when it is
     * {@code null}. The filing is the caller's own: it never touches the publication, so an
     * acquirer filing an app they did not publish is perfectly normal.
     *
     * @return how many applications were re-filed
     */
    @Transactional
    public int assignApplications(FolderScope scope, UUID folderId, Collection<UUID> publicationIds) {
        if (publicationIds == null || publicationIds.isEmpty()) return 0;
        String key = scopeKey(scope);
        if (folderId == null) {
            return itemRepository.deleteByScopeAndPublicationIds(key, publicationIds);
        }
        requireInScope(folderId, scope);
        // Re-filing replaces the previous filing, so the delete-then-insert IS the update.
        itemRepository.deleteByScopeAndPublicationIds(key, publicationIds);
        List<ApplicationFolderItemEntity> rows = publicationIds.stream()
                .map(id -> new ApplicationFolderItemEntity(id, key, folderId, scope.userId()))
                .toList();
        itemRepository.saveAll(rows);
        return rows.size();
    }

    /**
     * Delete a folder and its subfolders, dropping their filings FIRST. Both steps are one
     * transaction: a filing pointing at a folder that no longer exists would hide its
     * application from the top level AND from every folder.
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

    /**
     * The workspace as the filing table keys it: the organization, or {@code ""} for a
     * personal workspace (the column is NOT NULL so the composite key stays well-defined).
     */
    static String scopeKey(FolderScope scope) {
        return scope.hasOrganization() ? scope.organizationId() : "";
    }

    /** Persistence for the shared folder logic. */
    private record ApplicationFolderStore(ApplicationFolderRepository folders,
                                          ApplicationFolderItemRepository items)
            implements ResourceFolderStore<ApplicationFolderEntity> {

        @Override
        public List<ApplicationFolderEntity> findAllInScope(FolderScope scope) {
            return scope.hasOrganization()
                    ? folders.findByOrganizationId(scope.organizationId())
                    : folders.findByOwnerIdAndOrganizationIdIsNull(scope.userId());
        }

        @Override
        public Optional<ApplicationFolderEntity> findById(UUID id) {
            return folders.findById(id);
        }

        @Override
        public ApplicationFolderEntity newFolder() {
            return new ApplicationFolderEntity();
        }

        @Override
        public ApplicationFolderEntity save(ApplicationFolderEntity folder) {
            return folders.save(folder);
        }

        @Override
        public void deleteAll(Collection<ApplicationFolderEntity> toDelete) {
            folders.deleteAll(toDelete);
        }

        @Override
        public void detachResources(Collection<UUID> folderIds, FolderScope scope) {
            if (folderIds.isEmpty()) return;
            items.deleteByScopeAndFolderIds(scopeKey(scope), folderIds);
        }
    }
}
