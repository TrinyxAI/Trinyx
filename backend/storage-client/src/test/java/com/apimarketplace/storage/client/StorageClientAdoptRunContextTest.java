package com.apimarketplace.storage.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the request BODY of the run-context adoption call, key by key.
 *
 * <p>The client and {@code InternalFileController} agree through a hand-built JSON map, not a
 * shared DTO, so nothing but a test can hold the seven key names together across the two modules.
 * The risk is concrete: the orchestrator interface calls the parameter {@code stepAlias} while the
 * wire calls it {@code stepKey}. A drift on either side still compiles, still ships, and silently
 * stamps a null column forever - the storage-side test would stay green because it never sees the
 * wire. {@code StorageServiceAdoptRunContextTest} is the other half of this contract, and
 * {@code InternalFileControllerAdoptRunContextTest} reads these exact keys back.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StorageClient.adoptRunContext")
class StorageClientAdoptRunContextTest {

    @Mock private RestTemplate restTemplate;

    private StorageClient client() {
        return new StorageClient(restTemplate, "http://storage:8093");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedBody() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entity.capture(), eq(Map.class));
        return entity.getValue().getBody();
    }

    private void serverAnswers(Object adopted) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(adopted == null ? Map.of() : Map.of("adopted", adopted)));
    }

    @Nested
    @DisplayName("the wire contract")
    class Wire {

        @Test
        @DisplayName("posts every run coordinate under the exact key the controller reads")
        void postsEveryCoordinate() {
            serverAnswers(1);

            client().adoptRunContext("tenant-1", "org-1", List.of("f-1", "f-2"),
                    "wf-1", "run-7", "mcp:elevenlabs_tts", 4, 1, 2);

            Map<String, Object> body = capturedBody();
            assertThat(body).containsEntry("ids", List.of("f-1", "f-2"));
            assertThat(body).containsEntry("workflowId", "wf-1");
            assertThat(body).containsEntry("runId", "run-7");
            // Named stepKey on the wire even though the caller's parameter is stepAlias.
            assertThat(body).containsEntry("stepKey", "mcp:elevenlabs_tts");
            assertThat(body).containsEntry("epoch", 4);
            assertThat(body).containsEntry("spawn", 1);
            assertThat(body).containsEntry("itemIndex", 2);
        }

        @Test
        @DisplayName("targets the internal adoption route")
        void targetsTheInternalRoute() {
            serverAnswers(1);

            client().adoptRunContext("tenant-1", null, List.of("f-1"), "wf-1", "run-7", "step", 1, 0, null);

            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).exchange(url.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            assertThat(url.getValue()).isEqualTo("http://storage:8093/api/internal/storage/adopt-run-context");
        }

        @Test
        @DisplayName("carries a null runId / itemIndex rather than blowing up on them")
        void carriesNulls() {
            // A step outside a split has no itemIndex; the body is a HashMap precisely so these
            // do not throw the way Map.of would.
            serverAnswers(1);

            client().adoptRunContext("tenant-1", null, List.of("f-1"), "wf-1", null, null, 0, 0, null);

            Map<String, Object> body = capturedBody();
            assertThat(body).containsKey("runId").containsKey("itemIndex");
            assertThat(body.get("runId")).isNull();
            assertThat(body.get("itemIndex")).isNull();
        }

        @Test
        @DisplayName("reads the adopted count back out of the response")
        void readsTheCountBack() {
            serverAnswers(3);

            assertThat(client().adoptRunContext("tenant-1", null, List.of("a", "b", "c"),
                    "wf-1", "run-7", "step", 1, 0, null)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("degrades instead of throwing")
    class Degrades {

        @Test
        @DisplayName("a transport failure returns 0 - filing must not fail the caller's step")
        void transportFailureReturnsZero() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("storage unreachable"));

            assertThat(client().adoptRunContext("tenant-1", null, List.of("f-1"),
                    "wf-1", "run-7", "step", 1, 0, null)).isZero();
        }

        @Test
        @DisplayName("a response without the count returns 0")
        void missingCountReturnsZero() {
            serverAnswers(null);

            assertThat(client().adoptRunContext("tenant-1", null, List.of("f-1"),
                    "wf-1", "run-7", "step", 1, 0, null)).isZero();
        }

        @Test
        @DisplayName("no ids, or no workflow, costs no request at all")
        void noWorkNoRequest() {
            StorageClient client = client();

            assertThat(client.adoptRunContext("t", null, List.of(), "wf-1", "r", "s", 1, 0, null)).isZero();
            assertThat(client.adoptRunContext("t", null, null, "wf-1", "r", "s", 1, 0, null)).isZero();
            assertThat(client.adoptRunContext("t", null, List.of("f-1"), null, "r", "s", 1, 0, null)).isZero();
            assertThat(client.adoptRunContext("t", null, List.of("f-1"), "  ", "r", "s", 1, 0, null)).isZero();

            verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(), eq(Map.class));
        }
    }
}
