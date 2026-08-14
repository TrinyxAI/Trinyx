package com.apimarketplace.storage.web;

import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.service.file.StorageStreamingMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server half of the run-context adoption contract.
 *
 * <p>Client and controller agree through a hand-built JSON map rather than a shared DTO, so these
 * tests read back exactly the keys {@code StorageClientAdoptRunContextTest} writes. Together they
 * are the only thing holding the two modules' spelling together - a drift on either side compiles,
 * ships, and silently stamps a null column.</p>
 *
 * <p>The other property under test is that this route never refuses: the caller is a workflow step
 * that has already succeeded, so an unusable id has to be skipped, not turned into an error.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalFileController - adopt-run-context")
class InternalFileControllerAdoptRunContextTest {

    @Mock private FileStorageService fileStorageService;
    @Mock private StorageStreamingMetrics streamingMetrics;
    @Mock private com.apimarketplace.common.storage.service.StorageService storageIndexService;

    private InternalFileController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalFileController(fileStorageService, streamingMetrics);
        ReflectionTestUtils.setField(controller, "storageIndexService", storageIndexService);
        when(storageIndexService.adoptRunContext(anyString(), any(), anyString(), any(), any(),
                anyInt(), anyInt(), any())).thenReturn(1);
    }

    /** The body the storage-client posts. */
    private static Map<String, Object> body(Object ids, String workflowId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("workflowId", workflowId);
        body.put("runId", "run-7");
        body.put("stepKey", "mcp:elevenlabs_tts");
        body.put("epoch", 4);
        body.put("spawn", 1);
        body.put("itemIndex", 2);
        return body;
    }

    @Nested
    @DisplayName("reads the client's body")
    class ReadsTheBody {

        @Test
        @DisplayName("every key reaches the indexer as the coordinate it names")
        void forwardsEveryCoordinate() {
            UUID id = UUID.randomUUID();

            controller.adoptRunContext(body(List.of(id.toString()), "wf-1"), "tenant-1");

            ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(storageIndexService).adoptRunContext(eq("tenant-1"), ids.capture(), eq("wf-1"),
                    eq("run-7"), eq("mcp:elevenlabs_tts"), eq(4), eq(1), eq(2));
            assertThat(ids.getValue()).containsExactly(id);
        }

        @Test
        @DisplayName("answers with the adopted count under the key the client reads")
        void answersWithTheCount() {
            when(storageIndexService.adoptRunContext(anyString(), any(), anyString(), any(), any(),
                    anyInt(), anyInt(), any())).thenReturn(2);

            ResponseEntity<Map<String, Object>> response =
                    controller.adoptRunContext(body(List.of(UUID.randomUUID().toString()), "wf-1"), "tenant-1");

            assertThat(response.getBody()).containsEntry("adopted", 2);
        }

        @Test
        @DisplayName("a missing runId / itemIndex arrives as null, not as a default")
        void missingOptionalsStayNull() {
            Map<String, Object> body = body(List.of(UUID.randomUUID().toString()), "wf-1");
            body.put("runId", null);
            body.put("itemIndex", null);

            controller.adoptRunContext(body, "tenant-1");

            verify(storageIndexService).adoptRunContext(eq("tenant-1"), any(), eq("wf-1"),
                    eq(null), anyString(), eq(4), eq(1), eq(null));
        }

        @Test
        @DisplayName("an absent epoch / spawn falls back to 0 rather than failing")
        void absentEpochDefaultsToZero() {
            Map<String, Object> body = body(List.of(UUID.randomUUID().toString()), "wf-1");
            body.remove("epoch");
            body.remove("spawn");

            controller.adoptRunContext(body, "tenant-1");

            verify(storageIndexService).adoptRunContext(anyString(), any(), anyString(), any(), any(),
                    eq(0), eq(0), any());
        }
    }

    @Nested
    @DisplayName("never refuses the caller")
    class NeverRefuses {

        @Test
        @DisplayName("a non-UUID id is dropped, the rest of the batch still goes through")
        void dropsUnparseableIds() {
            UUID good = UUID.randomUUID();

            controller.adoptRunContext(body(List.of("not-a-uuid", good.toString()), "wf-1"), "tenant-1");

            ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(storageIndexService).adoptRunContext(anyString(), ids.capture(), anyString(), any(), any(),
                    anyInt(), anyInt(), any());
            assertThat(ids.getValue()).containsExactly(good);
        }

        @Test
        @DisplayName("no usable ids, or no workflow: 200 with adopted=0 and no call")
        void nothingToDo() {
            assertThat(controller.adoptRunContext(body(List.of(), "wf-1"), "t").getBody())
                    .containsEntry("adopted", 0);
            assertThat(controller.adoptRunContext(body(List.of("not-a-uuid"), "wf-1"), "t").getBody())
                    .containsEntry("adopted", 0);
            assertThat(controller.adoptRunContext(body(List.of(UUID.randomUUID().toString()), null), "t").getBody())
                    .containsEntry("adopted", 0);
            assertThat(controller.adoptRunContext(body("not-a-list", "wf-1"), "t").getBody())
                    .containsEntry("adopted", 0);

            verify(storageIndexService, never()).adoptRunContext(anyString(), any(), anyString(), any(), any(),
                    anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("an indexer failure answers 200 with adopted=0, never a 5xx")
        void indexerFailureIsSwallowed() {
            // The caller is a step that already succeeded; a 5xx here would be noise it cannot act on.
            when(storageIndexService.adoptRunContext(anyString(), any(), anyString(), any(), any(),
                    anyInt(), anyInt(), any())).thenThrow(new RuntimeException("db down"));

            ResponseEntity<Map<String, Object>> response =
                    controller.adoptRunContext(body(List.of(UUID.randomUUID().toString()), "wf-1"), "tenant-1");

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).containsEntry("adopted", 0);
        }

        @Test
        @DisplayName("no indexer wired at all still answers 200")
        void noIndexerIsFine() {
            InternalFileController bare = new InternalFileController(fileStorageService, streamingMetrics);

            ResponseEntity<Map<String, Object>> response =
                    bare.adoptRunContext(body(List.of(UUID.randomUUID().toString()), "wf-1"), "tenant-1");

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).containsEntry("adopted", 0);
        }
    }
}
