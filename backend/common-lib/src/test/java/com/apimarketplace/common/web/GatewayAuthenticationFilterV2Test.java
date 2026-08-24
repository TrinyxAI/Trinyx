package com.apimarketplace.common.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GatewayAuthenticationFilterV2Test {

    private static final String SECRET = "test-gateway-hmac-key-for-v2";
    private GatewayFilterProperties properties;
    private InMemoryGatewayNonceStore nonces;
    private GatewayAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        properties = new GatewayFilterProperties();
        properties.setSecretKey(SECRET);
        properties.setVerificationEnabled(true);
        properties.setAcceptV1(false);
        properties.setV2TimestampSkewMs(60_000);
        nonces = new InMemoryGatewayNonceStore();
        filter = new GatewayAuthenticationFilter(properties, nonces);
    }

    @Test
    void defaultBodyLimitPreservesFiftyMiBStorageContract() {
        assertThat(properties.getMaxBodyBytes()).isEqualTo(50 * 1024 * 1024);
    }

    @Test
    void acceptsBodyAboveLegacyTenMiBLimitAndReplaysExactBytes() throws Exception {
        byte[] body = new byte[11 * 1024 * 1024];
        java.util.Arrays.fill(body, (byte) 0x5a);
        MockHttpServletRequest request = signed(
                "POST", "/api/storage/files", null, body, "large-body");
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.util.concurrent.atomic.AtomicInteger observedBytes =
                new java.util.concurrent.atomic.AtomicInteger();
        FilterChain chain = (wrapped, ignored) ->
                observedBytes.set(wrapped.getInputStream().readAllBytes().length);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(observedBytes.get()).isEqualTo(body.length);
    }

    @Test
    void acceptsExactRequestAndLeavesBodyReplayable() throws Exception {
        byte[] body = "{\"value\":42}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signed("POST", "/api/protected", "a=1", body, "nonce-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(CachedBodyHttpServletRequest.class),
                org.mockito.ArgumentMatchers.eq(response));
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsBodyMethodPathAndQueryMutation() throws Exception {
        assertRejected(mutate(signed("POST", "/api/protected", "a=1", "{}".getBytes(), "body"), r ->
                r.setContent("{\"changed\":true}".getBytes())));
        assertRejected(copyWithMethod(signed("POST", "/api/protected", null, new byte[0], "method"), "PUT"));
        assertRejected(copyWithPath(signed("GET", "/api/one", null, new byte[0], "path"), "/api/two"));
        assertRejected(copyWithQuery(signed("GET", "/api/one", "a=1", new byte[0], "query"), "a=2"));
    }

    @Test
    void rejectsFutureExpiredTimestampAndReplay() throws Exception {
        assertRejected(signedAt(System.currentTimeMillis() + 61_000, "future"));
        assertRejected(signedAt(System.currentTimeMillis() - 61_000, "expired"));

        MockHttpServletRequest first = signed("GET", "/api/protected", null, new byte[0], "same-nonce");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        FilterChain firstChain = mock(FilterChain.class);
        filter.doFilter(first, firstResponse, firstChain);
        verify(firstChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(firstResponse));

        assertRejected(signed("GET", "/api/protected", null, new byte[0], "same-nonce"));
    }

    @Test
    void canonicalizesRolesBeforeSigning() {
        assertThat(GatewaySignatureV2.canonicalRoles("user, ADMIN,USER")).isEqualTo("ADMIN,USER");
        assertThat(GatewaySignatureV2.rolesAreSafe("USER,ORG_ADMIN")).isTrue();
        assertThat(GatewaySignatureV2.rolesAreSafe("USER,not valid!")).isFalse();
    }

    private MockHttpServletRequest signedAt(long timestamp, String nonce) {
        MockHttpServletRequest request = base("GET", "/api/protected", null, new byte[0]);
        applySignature(request, timestamp, nonce);
        return request;
    }

    private MockHttpServletRequest signed(String method, String path, String query, byte[] body, String nonce) {
        MockHttpServletRequest request = base(method, path, query, body);
        applySignature(request, System.currentTimeMillis(), nonce);
        return request;
    }

    private MockHttpServletRequest base(String method, String path, String query, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setQueryString(query);
        request.setContent(body);
        request.addHeader("X-Provider-ID", "provider");
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-Principal-ID", "principal");
        request.addHeader("X-Billing-Subject-ID", "billing");
        request.addHeader("X-Organization-ID", "org");
        request.addHeader("X-Organization-Role", "owner");
        request.addHeader("X-User-Roles", "USER,ADMIN");
        request.addHeader("X-Install-ID", "install");
        return request;
    }

    private void applySignature(MockHttpServletRequest request, long timestamp, String nonce) {
        byte[] body = request.getContentAsByteArray();
        String hash = GatewaySignatureV2.sha256Hex(body);
        String target = request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        GatewaySignatureV2.Context context = new GatewaySignatureV2.Context(
                String.valueOf(timestamp), nonce, request.getMethod(), target, hash,
                "provider", "42", "principal", "billing", "org", "owner", "USER,ADMIN", "install");
        request.addHeader("X-Gateway-Signature-Version", "2");
        request.addHeader("X-Gateway-Timestamp", String.valueOf(timestamp));
        request.addHeader("X-Gateway-Nonce", nonce);
        request.addHeader("X-Gateway-Body-SHA256", hash);
        request.addHeader("X-Gateway-Secret", GatewaySignatureV2.sign(SECRET, context));
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    private MockHttpServletRequest mutate(MockHttpServletRequest request,
                                          java.util.function.Consumer<MockHttpServletRequest> mutation) {
        mutation.accept(request);
        return request;
    }

    private MockHttpServletRequest copyWithMethod(MockHttpServletRequest source, String method) {
        return copy(source, method, source.getRequestURI(), source.getQueryString());
    }

    private MockHttpServletRequest copyWithPath(MockHttpServletRequest source, String path) {
        return copy(source, source.getMethod(), path, source.getQueryString());
    }

    private MockHttpServletRequest copyWithQuery(MockHttpServletRequest source, String query) {
        return copy(source, source.getMethod(), source.getRequestURI(), query);
    }

    private MockHttpServletRequest copy(MockHttpServletRequest source, String method, String path, String query) {
        MockHttpServletRequest target = new MockHttpServletRequest(method, path);
        target.setRequestURI(path);
        target.setQueryString(query);
        target.setContent(source.getContentAsByteArray());
        java.util.Collections.list(source.getHeaderNames()).forEach(name ->
                java.util.Collections.list(source.getHeaders(name)).forEach(value -> target.addHeader(name, value)));
        return target;
    }
}
