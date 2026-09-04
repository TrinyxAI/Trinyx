package com.apimarketplace.common.web;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MonolithSecurityFilterBillingWorkloadTest {

    private final MonolithSecurityFilter filter =
            new MonolithSecurityFilter(() -> null, List.of());

    @Test
    void privateLoopbackWalletRequestReachesWorkloadControllerWithoutEmbeddedJwtParsing() throws Exception {
        MockHttpServletRequest request = request(
                "/internal/v1/credit-reservations", "127.0.0.1");
        request.addHeader("Authorization", "Bearer ed25519.workload.token");
        request.addHeader("X-User-ID", "spoofed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> captured.set(req));

        assertThat(captured.get()).isNotNull();
        assertThat(((MockHttpServletRequest) request).getHeader("Authorization"))
                .isEqualTo("Bearer ed25519.workload.token");
        assertThat(((jakarta.servlet.http.HttpServletRequest) captured.get()).getHeader("X-User-ID"))
                .isNull();
    }

    @Test
    void privateDispatchAckUsesWorkloadIdentityInsteadOfEmbeddedJwt() throws Exception {
        MockHttpServletRequest request = request(
                "/internal/v1/credit-reservations/"
                        + "11111111-1111-1111-1111-111111111111/dispatching",
                "127.0.0.1");
        request.addHeader("Authorization", "Bearer ed25519.workload.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> captured.set(req));

        assertThat(captured.get()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void privateUnknownOutcomeUsesWorkloadIdentityInsteadOfEmbeddedJwt() throws Exception {
        MockHttpServletRequest request = request(
                "/internal/v1/credit-reservations/"
                        + "11111111-1111-1111-1111-111111111111/outcome-unknown",
                "127.0.0.1");
        request.addHeader("Authorization", "Bearer ed25519.workload.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> captured.set(req));

        assertThat(captured.get()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void externalWalletRequestIsHiddenBeforeWorkloadController() throws Exception {
        assertHidden(request("/internal/v1/credit-reservations", "203.0.113.10"));
        assertHidden(request("/internal/v1/credit-reservations/"
                + "11111111-1111-1111-1111-111111111111/dispatching",
                "203.0.113.10"));
    }

    @Test
    void publicSameHostProxyCannotInheritLoopbackTrust() throws Exception {
        MockHttpServletRequest request = request(
                "/internal/v1/credit-reservations/11111111-1111-1111-1111-111111111111/commit",
                "127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        request.addHeader("Authorization", "Bearer attacker");
        assertHidden(request);
    }

    @Test
    void nonPostRequestNeverGetsPrivateWalletBypass() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/internal/v1/credit-reservations");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer ed25519.workload.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> captured.set(req));

        assertThat(captured.get()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void onlyExactWalletSurfaceGetsPrivateWorkloadBypass() throws Exception {
        MockHttpServletRequest unrelatedExternal =
                request("/internal/v1/entitlement-projections", "203.0.113.10");
        unrelatedExternal.addHeader("Authorization", "Bearer attacker");
        MockHttpServletResponse unrelatedResponse = new MockHttpServletResponse();
        AtomicReference<ServletRequest> unrelatedCaptured = new AtomicReference<>();
        filter.doFilter(unrelatedExternal, unrelatedResponse,
                (req, res) -> unrelatedCaptured.set(req));
        assertThat(unrelatedCaptured.get()).isNull();
        assertThat(unrelatedResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest loopback = request("/internal/v1/admin", "127.0.0.1");
        loopback.addHeader("Authorization", "Bearer ed25519.workload.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        filter.doFilter(loopback, response, (req, res) -> captured.set(req));

        assertThat(captured.get()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private void assertHidden(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> captured.set(req));
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Not Found\"}");
        assertThat(captured.get()).isNull();
    }

    private static MockHttpServletRequest request(String path, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
