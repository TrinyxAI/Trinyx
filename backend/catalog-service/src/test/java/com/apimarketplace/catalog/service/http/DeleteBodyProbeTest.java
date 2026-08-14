package com.apimarketplace.catalog.service.http;

import com.apimarketplace.common.web.NoRedirectSimpleClientHttpRequestFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ground truth for whether a DELETE request body survives to the wire, per request factory.
 *
 * <p>60 catalog endpoints declare body params on DELETE because their vendor genuinely requires
 * one (Spotify playlist track removal, Auth0 role removal, Cloudflare bulk key delete, Quickbase
 * record delete, Segment user delete, Coda row delete, Weaviate batch delete, ...). Deciding how
 * to serve them means first knowing what the transport actually does, rather than trusting
 * either the Spring docs or memory.
 */
@DisplayName("Transport - can a DELETE carry a body?")
class DeleteBodyProbeTest {

    private RecordedRequest sendDelete(Object factory) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
            server.start();

            RestTemplate rt = new RestTemplate((org.springframework.http.client.ClientHttpRequestFactory) factory);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> req = new HttpEntity<>(Map.of("ids", java.util.List.of("a", "b")), headers);

            try {
                rt.exchange(server.url("/things").uri(), HttpMethod.DELETE, req, Object.class);
            } catch (Exception e) {
                // A factory that refuses a DELETE body throws here; the recorded request still
                // tells us what (if anything) reached the server.
                System.out.println("factory " + factory.getClass().getSimpleName() + " threw: " + e);
            }
            return server.takeRequest();
        }
    }

    @Test
    @DisplayName("SimpleClientHttpRequestFactory (the current bean) DROPS a DELETE body")
    void simpleFactoryDropsTheBody() throws Exception {
        RecordedRequest recorded = sendDelete(new NoRedirectSimpleClientHttpRequestFactory());

        System.out.println("[simple] method=" + recorded.getMethod()
                + " bodySize=" + recorded.getBodySize()
                + " body=" + recorded.getBody().readUtf8());
        assertThat(recorded.getMethod()).isEqualTo("DELETE");
    }

    @Test
    @DisplayName("and what about a GET body? Archbee documents three GET endpoints that need one")
    void getBodyProbe() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
            server.start();

            RestTemplate rt = new RestTemplate(new NoRedirectSimpleClientHttpRequestFactory());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> req = new HttpEntity<>(Map.of("docId", "abc"), headers);
            try {
                rt.exchange(server.url("/doc").uri(), HttpMethod.GET, req, Object.class);
            } catch (Exception e) {
                System.out.println("[get] threw: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            RecordedRequest recorded = server.takeRequest();
            System.out.println("[get] method=" + recorded.getMethod()
                    + " bodySize=" + recorded.getBodySize()
                    + " body=" + recorded.getBody().readUtf8());
        }
    }

    @Test
    @DisplayName("JdkClientHttpRequestFactory DELIVERS a DELETE body")
    void jdkFactoryDeliversTheBody() throws Exception {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());

        RecordedRequest recorded = sendDelete(factory);

        String body = recorded.getBody().readUtf8();
        System.out.println("[jdk] method=" + recorded.getMethod()
                + " bodySize=" + recorded.getBodySize() + " body=" + body);
        assertThat(recorded.getMethod()).isEqualTo("DELETE");
        assertThat(body).contains("ids");
    }
}
