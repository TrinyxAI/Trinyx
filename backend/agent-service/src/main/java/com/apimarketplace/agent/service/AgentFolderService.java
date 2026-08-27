package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentFolderEntity;
import com.apimarketplace.agent.repository.AgentFolderRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.common.folder.FolderPreviewItem;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderCoreService;
import com.apimarketplace.common.folder.ResourceFolderDto;
import com.apimarketplace.common.folder.ResourceFolderOrdering;
import com.apimarketplace.common.folder.ResourceFolderStore;
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
 * Folders of the /app/agent list: the shared folder rules
 * ({@link ResourceFolderCoreService}) plus what is specific to agents - a tile shows the
 * avatars of the agents it holds, the way the agent cards do, and it takes its place in
 * the list's ordering from the freshest thing inside it.
 *
 * <p>The aggregates are computed from the SAME scoped agent list the paged endpoint
 * already loaded, so a folder can never advertise a count that includes an agent the
 * caller is not allowed to see.
 */
@Service
public class AgentFolderService extends ResourceFolderCoreService<AgentFolderEntity> {

    /** Items drawn inside one folder tile: the face is a 3x2 grid, so six of them fill it. */
    private static final int PREVIEW_SIZE = 6;

    private final AgentRepository agentRepository;

    public AgentFolderService(AgentFolderRepository folderRepository, AgentRepository agentRepository) {
        super(new AgentFolderStore(folderRepository, agentRepository));
        this.agentRepository = agentRepository;
    }

    /**
     * The folders shown at one level of the list, each already carrying what its tile needs,
     * ordered by the same key the page sorts its agents with.
     *
     * @param scope        the caller's workspace
     * @param parentFolderId the level being displayed ({@code null} = top level)
     * @param scopedAgents every agent the caller can see (the paged endpoint's own set)
     * @param sortKey      the list's sort parameter, mapped by {@link ResourceFolderOrdering}
     */
    @Transactional(readOnly = true)
    public List<ResourceFolderDto> listFolderSummaries(FolderScope scope,
                                                       UUID parentFolderId,
                                                       List<AgentEntity> scopedAgents,
                                                       String sortKey) {
        List<AgentFolderEntity> all = listAll(scope);
        List<AgentFolderEntity> children = childrenOf(all, parentFolderId);
        if (children.isEmpty()) return List.of();

        Map<UUID, List<AgentEntity>> byFolder = groupByFolder(scopedAgents);
        List<ResourceFolderDto> summaries = new ArrayList<>(children.size());
        for (AgentFolderEntity folder : children) {
            Set<UUID> subtree = subtreeIds(all, folder.getId());
            List<AgentEntity> contained = new ArrayList<>();
            for (UUID folderId : subtree) {
                contained.addAll(byFolder.getOrDefault(folderId, List.of()));
            }
            summaries.add(summarize(folder, contained, childrenOf(all, folder.getId()).size()));
        }
        return ResourceFolderOrdering.sort(summaries, ResourceFolderOrdering.keyOf(sortKey));
    }

    /**
     * File agents into {@code folderId}, or back to the top level when it is {@code null}.
     *
     * @return how many agents were actually re-filed
     */
    @Transactional
    public int assignAgents(FolderScope scope, UUID folderId, Collection<UUID> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return 0;
        if (folderId != null) {
            requireInScope(folderId, scope);
        }
        return scope.hasOrganization()
                ? agentRepository.assignFolderForOrganization(agentIds, folderId, scope.organizationId())
                : agentRepository.assignFolderForOwner(agentIds, folderId, scope.userId());
    }

    /**
     * Delete a folder and its subfolders, emptying them back to the top level FIRST. Both
     * steps are one transaction: agents pointing at a folder that no longer exists would be
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

    private ResourceFolderDto summarize(AgentFolderEntity folder, List<AgentEntity> contained, int subfolderCount) {
        Instant lastModified = null;
        for (AgentEntity agent : contained) {
            lastModified = latest(lastModified, agent.getUpdatedAt());
        }
        return new ResourceFolderDto(
                folder.getId(),
                folder.getName(),
                folder.getParentFolderId(),
                contained.size(),
                subfolderCount,
                lastModified,
                // An agent has no "last run" on its row, so a folder of agents has nothing to
                // borrow for that key; the ordering falls back on the newest change.
                null,
                null,
                previewOf(contained),
                folder.getCreatedAt(),
                folder.getUpdatedAt());
    }

    /** The few agents the tile draws: most recently changed first, by their avatar. */
    private List<FolderPreviewItem> previewOf(List<AgentEntity> contained) {
        return contained.stream()
                .sorted(Comparator.comparing(
                        AgentEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(PREVIEW_SIZE)
                .map(agent -> FolderPreviewItem.withImage(
                        agent.getId().toString(), agent.getName(), agent.getAvatarUrl()))
                .toList();
    }

    private static Map<UUID, List<AgentEntity>> groupByFolder(List<AgentEntity> agents) {
        Map<UUID, List<AgentEntity>> byFolder = new HashMap<>();
        for (AgentEntity agent : agents) {
            UUID folderId = agent.getFolderId();
            if (folderId == null) continue;
            byFolder.computeIfAbsent(folderId, k -> new ArrayList<>()).add(agent);
        }
        return byFolder;
    }

    private static Instant latest(Instant current, Instant candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    /**
     * Persistence for the shared folder logic. Both read branches are workspace-strict (org
     * rows by organization, personal rows by owner AND untagged), mirroring
     * {@code ScopeGuard.isInStrictScope}, which the core service re-applies per folder.
     */
    private record AgentFolderStore(AgentFolderRepository folders, AgentRepository agents)
            implements ResourceFolderStore<AgentFolderEntity> {

        @Override
        public List<AgentFolderEntity> findAllInScope(FolderScope scope) {
            return scope.hasOrganization()
                    ? folders.findByOrganizationId(scope.organizationId())
                    : folders.findByOwnerIdAndOrganizationIdIsNull(scope.userId());
        }

        @Override
        public Optional<AgentFolderEntity> findById(UUID id) {
            return folders.findById(id);
        }

        @Override
        public AgentFolderEntity newFolder() {
            return new AgentFolderEntity();
        }

        @Override
        public AgentFolderEntity save(AgentFolderEntity folder) {
            return folders.save(folder);
        }

        @Override
        public void deleteAll(Collection<AgentFolderEntity> toDelete) {
            folders.deleteAll(toDelete);
        }

        @Override
        public void detachResources(Collection<UUID> folderIds, FolderScope scope) {
            if (folderIds.isEmpty()) return;
            if (scope.hasOrganization()) {
                agents.clearFolderForOrganization(folderIds, scope.organizationId());
            } else {
                agents.clearFolderForOwner(folderIds, scope.userId());
            }
        }
    }
}
