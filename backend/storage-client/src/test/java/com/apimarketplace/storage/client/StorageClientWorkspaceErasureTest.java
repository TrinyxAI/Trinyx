package com.apimarketplace.storage.client;

import com.apimarketplace.common.web.StorageOperationCapability;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StorageClientWorkspaceErasureTest {

    @Test
    void scheduledErasureProducesExactResourceScopedHttpAuthority() {
        String secret = "storage-authority-secret-32-chars!!";
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        StorageClient client = new StorageClient(rest, "http://storage.test", secret);
        UUID erasureId = UUID.randomUUID();

        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/api/internal/storage/workspace-erasure"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getFirst("X-User-ID"))
                            .isEqualTo("tenant-1");
                    assertThat(request.getHeaders().getFirst("X-Organization-ID"))
                            .isEqualTo("org-1");
                    String token = request.getHeaders().getFirst(
                            StorageOperationCapability.HEADER);
                    assertThat(StorageOperationCapability.verifyWorkspaceErasure(
                            token, secret, erasureId, "org-1", "tenant-1",
                            "tenant-1/object", Instant.now())).isTrue();
                })
                .andRespond(withSuccess("{\"deleted\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.deleteWorkspaceErasure(
                erasureId, "org-1", "tenant-1", "tenant-1/object")).isTrue();
        server.verify();
    }
}
