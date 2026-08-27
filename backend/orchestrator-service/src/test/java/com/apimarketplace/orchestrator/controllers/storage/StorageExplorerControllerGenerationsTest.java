package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.dto.GenerationHistoryDto;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two reads behind the generation history.
 *
 * <p>Both hand back CONTENT - a recipe carries the prompt - so both are gated exactly like the
 * ordinary file listing: the workspace scope, and the member deny-list. A second way of listing or
 * opening files that skipped either would be a second way of reading files a member cannot open,
 * and it would look like a feature rather than a hole.
 *
 * <p>The single-asset read answers 404 for a file that was not generated here. That is the ORDINARY
 * case, not an error: almost every file in a workspace was uploaded or written by a workflow, and
 * the viewer asks about all of them.
 */
@DisplayName("StorageExplorerController - the generation history")
class StorageExplorerControllerGenerationsTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-9";

    private StorageExplorerService explorerService;
    private StorageService storageService;
    private OrgAccessGuard orgAccessGuard;
    private StorageExplorerController controller;

    @BeforeEach
    void setUp() {
        explorerService = mock(StorageExplorerService.class);
        storageService = mock(StorageService.class);
        orgAccessGuard = mock(OrgAccessGuard.class);
        lenient().when(orgAccessGuard.canAccess(any(), any(), eq("file"), any(), any())).thenReturn(true);
        lenient().when(orgAccessGuard.getRestrictedResourceIds(any(), any(), eq("file"), any()))
                .thenReturn(Set.of());
        controller = new StorageExplorerController(
                explorerService, storageService, orgAccessGuard, mock(WorkflowRepository.class));
        lenient().when(explorerService.listGenerations(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 12), false));
    }

    private static StorageEntity asset(UUID id, String metadata) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setTenantId(TENANT);
        e.setStorageType("S3_FILE");
        e.setFileName("flux.png");
        e.setMetadata(metadata);
        return e;
    }

    @Nested
    @DisplayName("listing what has been generated")
    class Listing {

        @Test
        @DisplayName("reads the caller's workspace and applies the member deny-list")
        void scopesAndFilters() {
            UUID denied = UUID.randomUUID();
            when(orgAccessGuard.getRestrictedResourceIds(eq(ORG), eq(TENANT), eq("file"), any()))
                    .thenReturn(Set.of(denied.toString()));

            controller.generations(TENANT, ORG, "MEMBER", 0, 12, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> excluded = ArgumentCaptor.forClass(Collection.class);
            verify(explorerService).listGenerations(eq(ORG), eq(null), excluded.capture(), any());
            assertThat(excluded.getValue()).containsExactly(denied);
        }

        @Test
        @DisplayName("passes the format filter through")
        void passesTheFormatThrough() {
            controller.generations(TENANT, ORG, "MEMBER", 0, 12, "voice");

            verify(explorerService).listGenerations(eq(ORG), eq("voice"), any(), any());
        }

        @Test
        @DisplayName("caps the page size a caller can ask for")
        void capsThePageSize() {
            // The page carries a recipe per row, and the query fetches one MORE than it is asked
            // for. An unbounded size turns one request into a dump of every prompt the workspace
            // has ever written.
            controller.generations(TENANT, ORG, "MEMBER", 0, 5000, null);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(explorerService).listGenerations(any(), any(), any(), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isLessThanOrEqualTo(100);
        }
    }

    @Nested
    @DisplayName("the recipe of one asset")
    class OneAsset {

        @Test
        @DisplayName("hands back the recipe a generated asset carries")
        void returnsTheRecipe() {
            UUID id = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(id, TENANT, ORG)).thenReturn(
                    Optional.of(asset(id, "{\"generation\":{\"model\":\"flux-1.1-pro\"}}")));

            ResponseEntity<Map<String, Object>> response =
                    controller.generation(TENANT, ORG, "MEMBER", id);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("model", "flux-1.1-pro");
        }

        @Test
        @DisplayName("answers 404 for a file that was simply not generated here")
        void notGeneratedIsNotAnError() {
            UUID id = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(id, TENANT, ORG))
                    .thenReturn(Optional.of(asset(id, null)));

            assertThat(controller.generation(TENANT, ORG, "MEMBER", id).getStatusCode().value())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("answers 404 rather than 500 on metadata it cannot read")
        void unreadableMetadataDoesNotBreakTheViewer() {
            // The column is shared and a row may carry anything another producer wrote. The file
            // viewer asks about every file it opens and must not be taken down by one of them.
            UUID id = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(id, TENANT, ORG))
                    .thenReturn(Optional.of(asset(id, "not json at all")));

            assertThat(controller.generation(TENANT, ORG, "MEMBER", id).getStatusCode().value())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("refuses a file this member may not open")
        void refusesADeniedFile() {
            // A recipe carries the prompt, which is content: the gate is the one the preview uses.
            UUID id = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(id, TENANT, ORG)).thenReturn(
                    Optional.of(asset(id, "{\"generation\":{\"model\":\"flux-1.1-pro\"}}")));
            when(orgAccessGuard.canAccess(eq(ORG), eq(TENANT), eq("file"), eq(id.toString()), any()))
                    .thenReturn(false);

            assertThat(controller.generation(TENANT, ORG, "MEMBER", id).getStatusCode().value())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("answers 404 for a row outside the caller's scope")
        void refusesAnotherWorkspacesRow() {
            UUID id = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(id, TENANT, ORG)).thenReturn(Optional.empty());

            assertThat(controller.generation(TENANT, ORG, "MEMBER", id).getStatusCode().value())
                    .isEqualTo(404);
        }
    }
}
