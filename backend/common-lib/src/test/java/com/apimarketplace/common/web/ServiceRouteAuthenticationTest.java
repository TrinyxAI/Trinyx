package com.apimarketplace.common.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ServiceRouteAuthenticationTest {
    private static final String SECRET = "catalog-service-secret-at-least-32-characters";

    @Test
    void missingWrongServiceReplayAndWrongTenantAreDeniedButExactCallerSucceeds() throws Exception {
        GatewayFilterProperties properties = properties();
        GatewayNonceStore nonces = mock(GatewayNonceStore.class);
        when(nonces.consume(anyString(), anyString(), any())).thenReturn(true, false, true);
        GatewayAuthenticationFilter filter = new GatewayAuthenticationFilter(properties, nonces);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest missing = request("victim", null, null);
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        filter.doFilter(missing, missingResponse, chain);
        assertThat(missingResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest wrongService = request("victim", "websearch-service", SECRET);
        MockHttpServletResponse wrongServiceResponse = new MockHttpServletResponse();
        filter.doFilter(wrongService, wrongServiceResponse, chain);
        assertThat(wrongServiceResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest valid = request("victim", "catalog-service", SECRET);
        MockHttpServletResponse validResponse = new MockHttpServletResponse();
        filter.doFilter(valid, validResponse, chain);
        assertThat(validResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest replay = cloneWithHeaders(valid);
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        filter.doFilter(replay, replayResponse, chain);
        assertThat(replayResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest wrongTenant = request("victim", "catalog-service", SECRET);
        wrongTenant.removeHeader("X-User-ID");
        wrongTenant.addHeader("X-User-ID", "other-tenant");
        MockHttpServletResponse wrongTenantResponse = new MockHttpServletResponse();
        filter.doFilter(wrongTenant, wrongTenantResponse, chain);
        assertThat(wrongTenantResponse.getStatus()).isEqualTo(401);

        verify(chain, times(1)).doFilter(any(), any());
    }

    private static GatewayFilterProperties properties() {
        GatewayFilterProperties p = new GatewayFilterProperties();
        p.setSecretKey("gateway-secret-at-least-32-characters");
        p.setAcceptV1(false);
        p.setPublicPaths(List.of("/api/internal/"));
        p.setHmacRequiredPaths(List.of("/api/internal/credentials/"));
        p.setServiceAuthenticatedPaths(List.of("/api/internal/credentials/"));
        p.setServiceSecrets(Map.of("catalog-service", SECRET));
        p.setServiceRoutePermissions(Map.of("catalog-service",
                List.of("GET:/api/internal/credentials/access-token")));
        return p;
    }

    private static MockHttpServletRequest request(String tenant, String service, String secret) {
        String path = "/api/internal/credentials/access-token";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.addHeader("X-User-ID", tenant);
        if (service == null) return request;
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String hash = GatewaySignatureV2.sha256Hex(new byte[0]);
        GatewaySignatureV2.Context context = new GatewaySignatureV2.Context(
                timestamp, nonce, "GET", path, hash, service, tenant,
                null, null, null, null, null, null);
        request.addHeader("X-Provider-ID", service);
        request.addHeader("X-Gateway-Signature-Version", "2");
        request.addHeader("X-Gateway-Timestamp", timestamp);
        request.addHeader("X-Gateway-Nonce", nonce);
        request.addHeader("X-Gateway-Body-SHA256", hash);
        request.addHeader("X-Gateway-Secret", GatewaySignatureV2.sign(secret, context));
        return request;
    }

    private static MockHttpServletRequest cloneWithHeaders(MockHttpServletRequest source) {
        MockHttpServletRequest copy = new MockHttpServletRequest("GET", source.getRequestURI());
        copy.setRequestURI(source.getRequestURI());
        var names = source.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            copy.addHeader(name, source.getHeader(name));
        }
        return copy;
    }
}
