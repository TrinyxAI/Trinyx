package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.GenerationProvenanceFields;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server half of the generation-provenance contract.
 *
 * <p>catalog-service and this controller agree through a hand-built JSON map rather than a shared
 * DTO, so these read back exactly the keys the client writes - the same reason
 * {@code InternalFileControllerAdoptRunContextTest} exists: a drift on either side compiles, ships,
 * and silently records nothing.
 *
 * <p>The other property under test is that this route NEVER refuses. It is called after a
 * generation has run and been charged, so an unusable id or a storage failure has to be skipped
 * rather than turned into an error the caller could act on by re-running - which would charge the
 * customer twice for one asset.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalGenerationProvenanceController")
class InternalGenerationProvenanceControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String ASSET = "8b0e5f5e-0000-4000-8000-000000000001";

    @Mock private StorageService storageIndexService;

    private InternalGenerationProvenanceController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalGenerationProvenanceController();
        ReflectionTestUtils.setField(controller, "storageIndexService", storageIndexService);
        when(storageIndexService.stampGenerationProvenance(anyString(), any(), any())).thenReturn(1);
    }

    private static Map<String, Object> body(Object ids, Object provenance) {
        Map<String, Object> body = new HashMap<>();
        body.put(GenerationProvenanceFields.IDS, ids);
        body.put(GenerationProvenanceFields.PROVENANCE, provenance);
        return body;
    }

    private static Map<String, Object> recipe() {
        return Map.of(GenerationProvenanceFields.MODEL, "flux-1.1-pro",
                      GenerationProvenanceFields.KIND, "image");
    }

    @Test
    @DisplayName("passes the ids and the recipe through, under the caller's tenant")
    void passesTheRecipeThrough() {
        ResponseEntity<Map<String, Object>> response =
                controller.stamp(body(List.of(ASSET), recipe()), TENANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(storageIndexService).stampGenerationProvenance(eq(TENANT), ids.capture(), eq(recipe()));
        assertThat(ids.getValue()).containsExactly(UUID.fromString(ASSET));
        assertThat(response.getBody()).containsEntry(GenerationProvenanceFields.STAMPED, 1);
    }

    @Test
    @DisplayName("skips an id that is not a UUID instead of failing the batch")
    void skipsAnUnparseableId() {
        controller.stamp(body(List.of("not-a-uuid", ASSET), recipe()), TENANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(storageIndexService).stampGenerationProvenance(anyString(), ids.capture(), any());
        assertThat(ids.getValue()).containsExactly(UUID.fromString(ASSET));
    }

    @Test
    @DisplayName("answers 'nothing stamped' rather than erroring on an empty request")
    void answersZeroOnAnEmptyRequest() {
        assertThat(controller.stamp(body(List.of(), recipe()), TENANT).getBody())
                .containsEntry(GenerationProvenanceFields.STAMPED, 0);
        assertThat(controller.stamp(body(List.of(ASSET), Map.of()), TENANT).getBody())
                .containsEntry(GenerationProvenanceFields.STAMPED, 0);
        assertThat(controller.stamp(body("not a list", recipe()), TENANT).getBody())
                .containsEntry(GenerationProvenanceFields.STAMPED, 0);
        verify(storageIndexService, never()).stampGenerationProvenance(anyString(), any(), any());
    }

    @Test
    @DisplayName("swallows a storage failure: the generation has already been paid for")
    void swallowsAStorageFailure() {
        when(storageIndexService.stampGenerationProvenance(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("db is down"));

        ResponseEntity<Map<String, Object>> response =
                controller.stamp(body(List.of(ASSET), recipe()), TENANT);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry(GenerationProvenanceFields.STAMPED, 0);
    }

    @Test
    @DisplayName("does nothing when no indexer is wired, as some profiles leave it")
    void survivesWithoutAnIndexer() {
        InternalGenerationProvenanceController bare = new InternalGenerationProvenanceController();

        assertThat(bare.stamp(body(List.of(ASSET), recipe()), TENANT).getBody())
                .containsEntry(GenerationProvenanceFields.STAMPED, 0);
    }
}
