package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The microservice half of run-context adoption: the orchestrator adapter must forward to the
 * storage client rather than inherit the interface's do-nothing default.
 *
 * <p>Mirror of the CE test on {@code MonolithFileStorageServiceAdapter}, and for the same reason:
 * dropping this override still compiles and every other suite stays green, while catalog-produced
 * files quietly go back to sitting at the root of the Files browser.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageClientAdapter.adoptRunContext")
class StorageClientAdapterAdoptRunContextTest {

    @Mock private StorageClient storageClient;

    private StorageClientAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StorageClientAdapter(storageClient);
    }

    @Test
    @DisplayName("forwards every run coordinate to the storage client and returns its count")
    void forwardsToTheClient() {
        when(storageClient.adoptRunContext(anyString(), any(), any(), anyString(), any(), any(),
                anyInt(), anyInt(), any())).thenReturn(2);

        int adopted = adapter.adoptRunContext("tenant-1", List.of("f-1", "f-2"),
                "wf-1", "run-7", "mcp:elevenlabs_tts", 4, 1, 2);

        assertThat(adopted).isEqualTo(2);
        verify(storageClient).adoptRunContext(eq("tenant-1"), isNull(), eq(List.of("f-1", "f-2")),
                eq("wf-1"), eq("run-7"), eq("mcp:elevenlabs_tts"), eq(4), eq(1), eq(2));
    }

    @Test
    @DisplayName("passes a null organizationId, like every other upload on this adapter")
    void passesNullOrganizationId() {
        // Null on purpose: the StorageClient then falls back to the request-context forwarder, so
        // the row stays in the caller's workspace instead of being pinned to a guessed one.
        when(storageClient.adoptRunContext(anyString(), any(), any(), anyString(), any(), any(),
                anyInt(), anyInt(), any())).thenReturn(1);

        adapter.adoptRunContext("tenant-1", List.of("f-1"), "wf-1", "run-7", "step", 1, 0, null);

        verify(storageClient).adoptRunContext(anyString(), isNull(), any(), anyString(), any(), any(),
                anyInt(), anyInt(), any());
    }
}
