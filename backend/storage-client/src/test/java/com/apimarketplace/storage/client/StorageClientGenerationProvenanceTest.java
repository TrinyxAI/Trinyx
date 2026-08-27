package com.apimarketplace.storage.client;

import com.apimarketplace.common.storage.GenerationProvenanceFields;
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
import org.springframework.web.client.RestClientException;
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
 * Pins the WIRE half of the generation-provenance call: the URL, the body keys, and what comes back.
 *
 * <p>The client and {@code InternalGenerationProvenanceController} agree through a hand-built JSON
 * map, not a shared DTO. Nothing but a test holds the names together across the two modules: a
 * rename on either side compiles, ships, and silently records nothing forever - and the storage-side
 * test stays green, because it never sees the wire.
 * {@code InternalGenerationProvenanceControllerTest} reads these exact keys back.
 *
 * <p>The second property is that it never throws. It is called after a generation has run and been
 * charged, so a storage failure must cost the caller its recipe and nothing else - never the asset,
 * and never a retry that would generate (and bill) a second time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StorageClient.stampGenerationProvenance")
class StorageClientGenerationProvenanceTest {

    @Mock private RestTemplate restTemplate;

    private static final Map<String, Object> RECIPE = Map.of(
            GenerationProvenanceFields.MODEL, "flux-1.1-pro",
            GenerationProvenanceFields.KIND, "image",
            GenerationProvenanceFields.PROMPT, "a lighthouse at dusk");

    private StorageClient client() {
        return new StorageClient(restTemplate, "http://storage:8093");
    }

    private void serverAnswers(Object stamped) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(stamped == null
                        ? Map.of()
                        : Map.of(GenerationProvenanceFields.STAMPED, stamped)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedBody() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entity.capture(), eq(Map.class));
        return entity.getValue().getBody();
    }

    private String capturedUrl() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        return url.getValue();
    }

    @Nested
    @DisplayName("the wire contract")
    class Wire {

        @Test
        @DisplayName("posts to the route the storage service actually mounts")
        void postsToTheRightRoute() {
            serverAnswers(1);

            client().stampGenerationProvenance("tenant-1", "org-1", List.of("f-1"), RECIPE);

            assertThat(capturedUrl()).isEqualTo("http://storage:8093/api/internal/storage/generation-provenance");
        }

        @Test
        @DisplayName("sends the ids and the recipe under the exact keys the controller reads")
        void sendsTheContractKeys() {
            serverAnswers(1);

            client().stampGenerationProvenance("tenant-1", "org-1", List.of("f-1", "f-2"), RECIPE);

            Map<String, Object> body = capturedBody();
            assertThat(body).containsEntry(GenerationProvenanceFields.IDS, List.of("f-1", "f-2"));
            // The recipe travels WHOLE. A client that reshaped it here would replay a different
            // generation than the one that produced the asset.
            assertThat(body).containsEntry(GenerationProvenanceFields.PROVENANCE, RECIPE);
        }

        @Test
        @DisplayName("carries the caller's tenant and workspace, so the row is found and scoped")
        void carriesTheIdentityHeaders() {
            serverAnswers(1);

            client().stampGenerationProvenance("tenant-1", "org-1", List.of("f-1"), RECIPE);

            ArgumentCaptor<HttpEntity<Map<String, Object>>> entity = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entity.capture(), eq(Map.class));
            assertThat(entity.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-1");
            assertThat(entity.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
        }

        @Test
        @DisplayName("reads back how many rows were stamped")
        void readsTheCount() {
            serverAnswers(2);

            assertThat(client().stampGenerationProvenance("t", "o", List.of("f-1", "f-2"), RECIPE))
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("when it must stay out of the way")
    class Degrades {

        @Test
        @DisplayName("asks nothing when there is nothing to stamp")
        void skipsAnEmptyRequest() {
            assertThat(client().stampGenerationProvenance("t", "o", List.of(), RECIPE)).isZero();
            assertThat(client().stampGenerationProvenance("t", "o", List.of("f-1"), Map.of())).isZero();
            assertThat(client().stampGenerationProvenance("t", "o", null, RECIPE)).isZero();

            verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class),
                    any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("answers 0 instead of throwing when storage is unreachable")
        void swallowsATransportFailure() {
            // The generation is already paid for. A throw here would surface as a failed generation
            // and invite a retry that generates - and bills - a second time.
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RestClientException("connection refused"));

            assertThat(client().stampGenerationProvenance("t", "o", List.of("f-1"), RECIPE)).isZero();
        }

        @Test
        @DisplayName("answers 0 on a body that does not say how many were stamped")
        void toleratesAnUnexpectedBody() {
            serverAnswers(null);

            assertThat(client().stampGenerationProvenance("t", "o", List.of("f-1"), RECIPE)).isZero();
        }
    }
}
