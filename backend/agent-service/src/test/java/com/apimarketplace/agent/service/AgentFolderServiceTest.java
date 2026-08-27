package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentFolderEntity;
import com.apimarketplace.agent.repository.AgentFolderRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.common.folder.FolderPreviewItem;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.folder.ResourceFolderDto;
import com.apimarketplace.common.folder.ResourceFolderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * What a folder of AGENTS says about itself. The rules it shares with the other lists are
 * pinned once in {@code ResourceFolderCoreServiceTest}; here we pin what is specific to
 * agents - the tile draws their avatars, an agent has no "last run" to lend a folder, and
 * filing takes the workspace branch.
 */
@DisplayName("AgentFolderService - folder tiles and filing")
class AgentFolderServiceTest {

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final FolderScope ORG_SCOPE = new FolderScope(USER, ORG);
    private static final FolderScope PERSONAL_SCOPE = new FolderScope(USER, null);
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-08-01T00:00:00Z");

    private AgentFolderRepository folderRepository;
    private AgentRepository agentRepository;
    private AgentFolderService service;
    private final List<AgentFolderEntity> folders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        folderRepository = mock(AgentFolderRepository.class);
        agentRepository = mock(AgentRepository.class);
        service = new AgentFolderService(folderRepository, agentRepository);
        folders.clear();
        when(folderRepository.findByOrganizationId(ORG)).thenReturn(folders);
        when(folderRepository.findByOwnerIdAndOrganizationIdIsNull(USER)).thenReturn(folders);
        when(folderRepository.findById(any())).thenAnswer(inv ->
                folders.stream().filter(f -> f.getId().equals(inv.getArgument(0))).findFirst());
    }

    private AgentFolderEntity folder(String name, UUID parentId) {
        AgentFolderEntity folder = new AgentFolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName(name);
        folder.setParentFolderId(parentId);
        folder.setOwnerId(USER);
        folder.setOrganizationId(ORG);
        folders.add(folder);
        return folder;
    }

    private AgentEntity agent(String name, UUID folderId, Instant updatedAt, String avatarUrl) {
        AgentEntity agent = new AgentEntity();
        agent.setId(UUID.randomUUID());
        agent.setTenantId(USER);
        agent.setName(name);
        agent.setFolderId(folderId);
        agent.setUpdatedAt(updatedAt);
        agent.setAvatarUrl(avatarUrl);
        return agent;
    }

    private List<ResourceFolderDto> summaries(UUID parentId, List<AgentEntity> agents, String sort) {
        return service.listFolderSummaries(ORG_SCOPE, parentId, agents, sort);
    }

    @Test
    @DisplayName("counts the whole subtree, so a folder of subfolders never reads as empty")
    void countsDeep() {
        AgentFolderEntity parent = folder("Support", null);
        AgentFolderEntity child = folder("Tier 2", parent.getId());
        List<AgentEntity> agents = List.of(
                agent("direct", parent.getId(), OLD, null),
                agent("nested", child.getId(), OLD, null),
                agent("loose", null, OLD, null));

        ResourceFolderDto tile = summaries(null, agents, "name").get(0);

        assertThat(tile.itemCount()).isEqualTo(2);
        assertThat(tile.subfolderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the tile draws the agents' avatars, the way the agent cards do")
    void previewCarriesAvatars() {
        AgentFolderEntity parent = folder("Support", null);
        AgentEntity agent = agent("Nova", parent.getId(), OLD, "preset:purple");

        FolderPreviewItem item = summaries(null, List.of(agent), "name").get(0).preview().get(0);

        assertThat(item.id()).isEqualTo(agent.getId().toString());
        assertThat(item.name()).isEqualTo("Nova");
        assertThat(item.imageUrl()).isEqualTo("preset:purple");
        assertThat(item.icons()).isNull();
    }

    @Test
    @DisplayName("draws at most one agent per cell of the 3x2 face, newest first")
    void previewIsCappedAtSix() {
        AgentFolderEntity parent = folder("Support", null);
        List<AgentEntity> agents = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            agents.add(agent("agent-" + i, parent.getId(), Instant.parse("2026-0" + i + "-01T00:00:00Z"), null));
        }

        List<FolderPreviewItem> preview = summaries(null, agents, "name").get(0).preview();

        assertThat(preview).hasSize(6);
        assertThat(preview.get(0).name()).isEqualTo("agent-8");
    }

    @Test
    @DisplayName("borrows the freshest change inside it, and has no run history to borrow")
    void aggregatesLastModifiedOnly() {
        AgentFolderEntity parent = folder("Support", null);
        List<AgentEntity> agents = List.of(
                agent("a", parent.getId(), OLD, null),
                agent("b", parent.getId(), RECENT, null));

        ResourceFolderDto tile = summaries(null, agents, "lastModified").get(0);

        assertThat(tile.lastModifiedAt()).isEqualTo(RECENT);
        assertThat(tile.lastActivityAt()).isNull();
        assertThat(tile.activityCount()).isNull();
    }

    @Test
    @DisplayName("an empty folder carries no date, so the ordering can sink it")
    void emptyFolderHasNoDate() {
        folder("Empty", null);

        ResourceFolderDto tile = summaries(null, List.of(), "lastModified").get(0);

        assertThat(tile.itemCount()).isZero();
        assertThat(tile.lastModifiedAt()).isNull();
        assertThat(tile.preview()).isEmpty();
    }

    @Test
    @DisplayName("orders the tiles by the same key the page sorts its rows with")
    void ordersTilesLikeTheList() {
        AgentFolderEntity stale = folder("stale", null);
        AgentFolderEntity fresh = folder("fresh", null);
        List<AgentEntity> agents = List.of(
                agent("a", stale.getId(), OLD, null),
                agent("b", fresh.getId(), RECENT, null));

        assertThat(summaries(null, agents, "lastModified")).extracting(ResourceFolderDto::name)
                .containsExactly("fresh", "stale");
        assertThat(summaries(null, agents, "name")).extracting(ResourceFolderDto::name)
                .containsExactly("fresh", "stale");
    }

    @Test
    @DisplayName("filing takes the workspace branch of the update")
    void filingUsesTheOrganizationBranch() {
        AgentFolderEntity target = folder("Target", null);
        List<UUID> ids = List.of(UUID.randomUUID());

        service.assignAgents(ORG_SCOPE, target.getId(), ids);

        verify(agentRepository).assignFolderForOrganization(ids, target.getId(), ORG);
        verify(agentRepository, never()).assignFolderForOwner(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("filing in a personal workspace matches on the owner instead")
    void filingUsesTheOwnerBranchInPersonalWorkspace() {
        when(folderRepository.findById(any())).thenReturn(Optional.empty());
        List<UUID> ids = List.of(UUID.randomUUID());

        service.assignAgents(PERSONAL_SCOPE, null, ids);

        verify(agentRepository).assignFolderForOwner(ids, null, USER);
        verify(agentRepository, never()).assignFolderForOrganization(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("refuses to file into a folder of another workspace")
    void refusesForeignTargetFolder() {
        AgentFolderEntity foreign = folder("Theirs", null);
        foreign.setOrganizationId("org-2");

        assertThatThrownBy(() -> service.assignAgents(ORG_SCOPE, foreign.getId(), List.of(UUID.randomUUID())))
                .isInstanceOf(ResourceFolderException.class);
        verify(agentRepository, never()).assignFolderForOrganization(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("deleting a folder empties it back to the top level instead of deleting its agents")
    void deleteSendsAgentsBackToTheTopLevel() {
        AgentFolderEntity parent = folder("Parent", null);
        AgentFolderEntity child = folder("Child", parent.getId());

        service.delete(parent.getId(), ORG_SCOPE);

        verify(agentRepository).clearFolderForOrganization(
                org.mockito.ArgumentMatchers.argThat(ids ->
                        ids.containsAll(List.of(parent.getId(), child.getId()))),
                eq(ORG));
        verify(folderRepository).deleteAll(anyCollection());
    }

    @Test
    @DisplayName("existsInScope hides a folder that belongs to another workspace")
    void existsInScopeIsWorkspaceStrict() {
        AgentFolderEntity mine = folder("Mine", null);
        AgentFolderEntity theirs = folder("Theirs", null);
        theirs.setOrganizationId("org-2");

        assertThat(service.existsInScope(mine.getId(), ORG_SCOPE)).isTrue();
        assertThat(service.existsInScope(theirs.getId(), ORG_SCOPE)).isFalse();
    }
}
