package com.apimarketplace.monolith.storage;

import com.apimarketplace.storage.domain.FileRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonolithFileStorageServiceAdapter")
class MonolithFileStorageServiceAdapterTest {

    @Mock
    private com.apimarketplace.storage.service.file.FileStorageService storageFileStorageService;

    private MonolithFileStorageServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MonolithFileStorageServiceAdapter(storageFileStorageService);
    }

    @Test
    @DisplayName("Delegates deleteRunFiles in-process so CE monolith avoids the disabled internal HTTP route")
    void deleteRunFilesUsesInProcessStorageServiceInMonolith() {
        when(storageFileStorageService.deleteRunFiles("tenant-1", "workflow-1", "run-1")).thenReturn(2);

        int deleted = adapter.deleteRunFiles("tenant-1", "workflow-1", "run-1");

        assertThat(deleted).isEqualTo(2);
        verify(storageFileStorageService).deleteRunFiles("tenant-1", "workflow-1", "run-1");
    }

    @Test
    @DisplayName("Maps storage-service FileRef to orchestrator FileRef without changing file metadata")
    void uploadMapsStorageFileRefToOrchestratorFileRef() {
        byte[] content = "ce-storage".getBytes(StandardCharsets.UTF_8);
        when(storageFileStorageService.upload(
                "tenant-1",
                "workflow-1",
                "run-1",
                "step",
                "report.txt",
                "text/plain",
                content))
                .thenReturn(FileRef.of("tenant-1/workflow-1/run-1/step/report.txt", "report.txt", "text/plain", 10));

        com.apimarketplace.orchestrator.domain.file.FileRef mapped = adapter.upload(
                "tenant-1",
                "workflow-1",
                "run-1",
                "step",
                "report.txt",
                "text/plain",
                content);

        assertThat(mapped.type()).isEqualTo(com.apimarketplace.orchestrator.domain.file.FileRef.TYPE_FILE);
        assertThat(mapped.path()).isEqualTo("tenant-1/workflow-1/run-1/step/report.txt");
        assertThat(mapped.name()).isEqualTo("report.txt");
        assertThat(mapped.mimeType()).isEqualTo("text/plain");
        assertThat(mapped.size()).isEqualTo(10);
    }

    @Test
    @DisplayName("byte[] upload threads epoch/spawn/itemIndex/sourceType through (not dropped to epoch 0)")
    void byteUploadThreadsRunCoordinatesToStorage() {
        byte[] content = "rec".getBytes(StandardCharsets.UTF_8);
        when(storageFileStorageService.upload(
                "tenant-1", "workflow-1", "run-1", "core:make_file", "rec.json", "application/json",
                content, 7, 2, 3, "STEP_OUTPUT"))
                .thenReturn(FileRef.of("k", "rec.json", "application/json", 3));

        com.apimarketplace.orchestrator.domain.file.FileRef mapped = adapter.upload(
                "tenant-1", "workflow-1", "run-1", "core:make_file", "rec.json", "application/json",
                content, 7, 2, 3, "STEP_OUTPUT");

        assertThat(mapped.path()).isEqualTo("k");
        // The epoch-aware storage overload MUST receive the REAL coordinates. Pre-fix, the orchestrator
        // FileStorageService interface default dropped them and fell back to the no-context
        // upload(... content) -> every monolith workflow file landed at epoch 0 (one "Epoch 0" folder).
        verify(storageFileStorageService).upload(
                "tenant-1", "workflow-1", "run-1", "core:make_file", "rec.json", "application/json",
                content, 7, 2, 3, "STEP_OUTPUT");
        verify(storageFileStorageService, never()).upload(
                "tenant-1", "workflow-1", "run-1", "core:make_file", "rec.json", "application/json", content);
    }

    @Test
    @DisplayName("InputStream upload threads epoch/spawn/itemIndex/sourceType through (not dropped to epoch 0)")
    void inputStreamUploadThreadsRunCoordinatesToStorage() {
        InputStream content = new ByteArrayInputStream("rec".getBytes(StandardCharsets.UTF_8));
        when(storageFileStorageService.upload(
                "tenant-1", "workflow-1", "run-1", "core:make_file", "rec.json", "application/json",
                content, 3L, 5, 1, 4, "STEP_OUTPUT"))
                .thenReturn(FileRef.of("k", "rec.json", "application/json", 3));

        com.apimarketplace.orchestrator.domain.file.FileRef mapped = adapter.upload(
                "tenant-1", "workflow-1", "run-1", "core:make_file", "rec.json", "application/json",
                content, 3L, 5, 1, 4, "STEP_OUTPUT");

        assertThat(mapped.path()).isEqualTo("k");
        verify(storageFileStorageService).upload(
                "tenant-1", "workflow-1", "run-1", "core:make_file", "rec.json", "application/json",
                content, 3L, 5, 1, 4, "STEP_OUTPUT");
        verify(storageFileStorageService, never()).upload(
                "tenant-1", "workflow-1", "run-1", "core:make_file", "rec.json", "application/json", content, 3L);
    }

    @Test
    @DisplayName("adoptRunContext files catalog-produced files in-process - the CE half of the fix")
    void adoptRunContextDelegatesInProcess() {
        // This test exists because deleting the override leaves the code COMPILING: it falls back
        // to the interface's default, which returns 0 and does nothing. CE would then keep showing
        // every text-to-speech / image-generation output at the ROOT of the Files browser instead
        // of inside its run - the very bug this change fixes, present in CE only. Same trap the
        // epoch-0 overrides above guard against.
        com.apimarketplace.common.storage.service.StorageService indexService =
                org.mockito.Mockito.mock(com.apimarketplace.common.storage.service.StorageService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "storageIndexService", indexService);
        java.util.UUID id = java.util.UUID.randomUUID();
        when(indexService.adoptRunContext(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);

        int adopted = adapter.adoptRunContext("tenant-1", java.util.List.of(id.toString()),
                "workflow-1", "run-1", "mcp:elevenlabs_tts", 4, 1, 2);

        assertThat(adopted).isEqualTo(1);
        org.mockito.ArgumentCaptor<java.util.Collection<java.util.UUID>> ids =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(indexService).adoptRunContext(org.mockito.ArgumentMatchers.eq("tenant-1"), ids.capture(),
                org.mockito.ArgumentMatchers.eq("workflow-1"), org.mockito.ArgumentMatchers.eq("run-1"),
                org.mockito.ArgumentMatchers.eq("mcp:elevenlabs_tts"), org.mockito.ArgumentMatchers.eq(4),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(2));
        assertThat(ids.getValue()).containsExactly(id);
    }

    @Test
    @DisplayName("adoptRunContext skips a non-UUID id instead of failing the batch")
    void adoptRunContextSkipsUnparseableIds() {
        com.apimarketplace.common.storage.service.StorageService indexService =
                org.mockito.Mockito.mock(com.apimarketplace.common.storage.service.StorageService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "storageIndexService", indexService);
        java.util.UUID good = java.util.UUID.randomUUID();

        adapter.adoptRunContext("tenant-1", java.util.List.of("not-a-uuid", good.toString()),
                "workflow-1", "run-1", "step", 1, 0, null);

        org.mockito.ArgumentCaptor<java.util.Collection<java.util.UUID>> ids =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(indexService).adoptRunContext(org.mockito.ArgumentMatchers.anyString(), ids.capture(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        assertThat(ids.getValue()).containsExactly(good);
    }

    @Test
    @DisplayName("adoptRunContext without an indexer wired returns 0 rather than throwing")
    void adoptRunContextWithoutIndexer() {
        assertThat(adapter.adoptRunContext("tenant-1", java.util.List.of(java.util.UUID.randomUUID().toString()),
                "workflow-1", "run-1", "step", 1, 0, null)).isZero();
    }
}
