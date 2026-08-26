package com.apimarketplace.orchestrator.services.folder;

import com.apimarketplace.common.folder.FolderPreviewItem;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.common.folder.ResourceFolderDto;
import com.apimarketplace.common.folder.ResourceFolderOrdering;
import com.apimarketplace.common.folder.ResourceFolderStore;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowFolderEntity;
import com.apimarketplace.orchestrator.repository.WorkflowFolderRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowIconExtractor;
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
 * Folders of the /app/workflow list: the shared folder rules
 * ({@link ResourceFolderCoreService}) plus the two things that are specific to workflows -
 * what a folder tile shows (little rows of node icons, like the workflow cards themselves)
 * and where a folder sits in the list's ordering (it borrows the freshest activity inside it).
 *
 * <p>The aggregates are computed from the SAME scoped workflow list the list endpoint
 * already loaded, never from a separate query: a folder can therefore never advertise a
 * count that includes a workflow the caller is not allowed to see.
 */
@Service
public class WorkflowFolderService extends ResourceFolderCoreService<WorkflowFolderEntity> {

    /**
     * Items drawn inside one folder tile: the face is a 3x2 grid, so six of them fill it.
     * Change this and the grid in {@code WorkflowFolderFace} together - a mismatch either
     * leaves dead cells or silently drops workflows the tile had room for.
     */
    private static final int PREVIEW_SIZE = 6;

    private final WorkflowRepository workflowRepository;

    public WorkflowFolderService(WorkflowFolderRepository folderRepository,
                                 WorkflowRepository workflowRepository) {
        super(new WorkflowFolderStore(folderRepository, workflowRepository));
        this.workflowRepository = workflowRepository;
    }

    /**
     * The folders shown at one level of the list, each already carrying what its tile
     * needs, ordered by the same key the page sorts its workflows with.
     *
     * @param scope           the caller's workspace
     * @param parentFolderId  the level being displayed ({@code null} = top level)
     * @param scopedWorkflows every workflow the caller can see (the list endpoint's own set)
     * @param sortKey         the list's sort parameter, mapped by {@link ResourceFolderOrdering}
     * @param runCounts       workflow id -> run count, or {@code null} when the page is not
     *                        sorting by run count (the counts are not worth a query otherwise)
     */
    @Transactional(readOnly = true)
    public List<ResourceFolderDto> listFolderSummaries(FolderScope scope,
                                                       UUID parentFolderId,
                                                       List<WorkflowEntity> scopedWorkflows,
                                                       String sortKey,
                                                       Map<UUID, Long> runCounts) {
        List<WorkflowFolderEntity> all = listAll(scope);
        List<WorkflowFolderEntity> children = childrenOf(all, parentFolderId);
        if (children.isEmpty()) return List.of();

        Map<UUID, List<WorkflowEntity>> byFolder = groupByFolder(scopedWorkflows);
        List<ResourceFolderDto> summaries = new ArrayList<>(children.size());
        for (WorkflowFolderEntity folder : children) {
            Set<UUID> subtree = subtreeIds(all, folder.getId());
            List<WorkflowEntity> contained = new ArrayList<>();
            for (UUID folderId : subtree) {
                contained.addAll(byFolder.getOrDefault(folderId, List.of()));
            }
            int subfolderCount = childrenOf(all, folder.getId()).size();
            summaries.add(summarize(folder, contained, subfolderCount, runCounts));
        }
        return ResourceFolderOrdering.sort(summaries, ResourceFolderOrdering.keyOf(sortKey));
    }

    /**
     * File workflows into {@code folderId}, or back to the top level when it is
     * {@code null}. The folder must be one of the caller's; the workflows are filtered by
     * workspace in the update itself, so ids the caller cannot touch are simply not moved.
     *
     * @return how many workflows were actually re-filed
     */
    @Transactional
    public int assignWorkflows(FolderScope scope, UUID folderId, Collection<UUID> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) return 0;
        if (folderId != null) {
            requireInScope(folderId, scope);
        }
        return scope.hasOrganization()
                ? workflowRepository.assignFolderForOrganization(workflowIds, folderId, scope.organizationId())
                : workflowRepository.assignFolderForOwner(workflowIds, folderId, scope.userId());
    }

    /**
     * Delete a folder and its subfolders, emptying them back to the top level FIRST. Both
     * steps have to be one transaction: if the folder rows went away while the workflows
     * still pointed at them, those workflows would be filed under a folder that no longer
     * exists - invisible at the top level and invisible in any folder.
     */
    @Override
    @Transactional
    public Set<UUID> delete(UUID folderId, FolderScope scope) {
        return super.delete(folderId, scope);
    }

    /** Folder ids that no longer exist, so a stale filter can fall back to the top level. */
    @Transactional(readOnly = true)
    public boolean existsInScope(UUID folderId, FolderScope scope) {
        return findInScope(folderId, scope).isPresent();
    }

    private ResourceFolderDto summarize(WorkflowFolderEntity folder,
                                        List<WorkflowEntity> contained,
                                        int subfolderCount,
                                        Map<UUID, Long> runCounts) {
        Instant lastModified = null;
        Instant lastActivity = null;
        Long activityCount = runCounts == null ? null : 0L;
        for (WorkflowEntity workflow : contained) {
            lastModified = latest(lastModified, workflow.getUpdatedAt());
            lastActivity = latest(lastActivity, workflow.getLastExecutedAt());
            if (runCounts != null) {
                activityCount += runCounts.getOrDefault(workflow.getId(), 0L);
            }
        }
        return new ResourceFolderDto(
                folder.getId(),
                folder.getName(),
                folder.getParentFolderId(),
                contained.size(),
                subfolderCount,
                lastModified,
                lastActivity,
                activityCount,
                previewOf(contained),
                folder.getCreatedAt(),
                folder.getUpdatedAt());
    }

    /**
     * The few workflows the tile draws: most recently changed first, so the tile shows the
     * ones the user is actually working on rather than whatever happens to be first.
     */
    private List<FolderPreviewItem> previewOf(List<WorkflowEntity> contained) {
        return contained.stream()
                .sorted(Comparator.comparing(
                        WorkflowEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(PREVIEW_SIZE)
                .map(workflow -> FolderPreviewItem.withIcons(
                        workflow.getId().toString(), workflow.getName(), nodeIconsOf(workflow)))
                .toList();
    }

    /**
     * A workflow saved before node icons were extracted has none stored; the list computes
     * them from the plan on the fly and the tile has to agree with the card next to it.
     */
    private List<Map<String, Object>> nodeIconsOf(WorkflowEntity workflow) {
        List<Map<String, Object>> icons = workflow.getNodeIcons();
        if (icons != null) return icons;
        if (workflow.getPlan() == null) return List.of();
        List<Map<String, Object>> extracted = WorkflowIconExtractor.extractNodeIcons(workflow.getPlan());
        return extracted != null ? extracted : List.of();
    }

    private static Map<UUID, List<WorkflowEntity>> groupByFolder(List<WorkflowEntity> workflows) {
        Map<UUID, List<WorkflowEntity>> byFolder = new HashMap<>();
        for (WorkflowEntity workflow : workflows) {
            UUID folderId = workflow.getFolderId();
            if (folderId == null) continue;
            byFolder.computeIfAbsent(folderId, k -> new ArrayList<>()).add(workflow);
        }
        return byFolder;
    }

    private static Instant latest(Instant current, Instant candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    /**
     * Persistence for the shared folder logic. Reads are workspace-strict on both branches
     * (org rows by organization, personal rows by owner AND untagged), mirroring
     * {@code ScopeGuard.isInStrictScope} - which the core service re-applies on every
     * single-folder lookup.
     */
    private record WorkflowFolderStore(WorkflowFolderRepository folders, WorkflowRepository workflows)
            implements ResourceFolderStore<WorkflowFolderEntity> {

        @Override
        public List<WorkflowFolderEntity> findAllInScope(FolderScope scope) {
            return scope.hasOrganization()
                    ? folders.findByOrganizationId(scope.organizationId())
                    : folders.findByOwnerIdAndOrganizationIdIsNull(scope.userId());
        }

        @Override
        public Optional<WorkflowFolderEntity> findById(UUID id) {
            return folders.findById(id);
        }

        @Override
        public WorkflowFolderEntity newFolder() {
            return new WorkflowFolderEntity();
        }

        @Override
        public WorkflowFolderEntity save(WorkflowFolderEntity folder) {
            return folders.save(folder);
        }

        @Override
        public void deleteAll(Collection<WorkflowFolderEntity> toDelete) {
            folders.deleteAll(toDelete);
        }

        @Override
        public void detachResources(Collection<UUID> folderIds, FolderScope scope) {
            if (folderIds.isEmpty()) return;
            if (scope.hasOrganization()) {
                workflows.clearFolderForOrganization(folderIds, scope.organizationId());
            } else {
                workflows.clearFolderForOwner(folderIds, scope.userId());
            }
        }
    }
}
