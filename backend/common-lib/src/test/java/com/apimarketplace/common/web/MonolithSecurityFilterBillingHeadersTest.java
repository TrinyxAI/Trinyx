package com.apimarketplace.common.web;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CE monolith is the SECOND door into {@code /catalog/v1/**}, and it has to
 * strip the billing headers exactly as the cloud gateway does.
 *
 * <p>Those six headers decide how much a call costs: a caller able to set them
 * names a cheap model, a quantity of one for a ten second video, a unit that
 * makes the call look small against the published rate, or an existing billing
 * scope whose pin bypasses the delinquent-account refusal. The gateway strips
 * them for that reason; {@code /catalog/v1/**} is not under
 * {@code /api/internal/}, so on CE the same request reached the endpoint with
 * the caller's own values intact.
 *
 * <p>It was bounded only by CE shipping {@code markup.enabled=false}, in a file
 * that invites an operator to flip it three lines above. A guard whose safety
 * rests on a default nobody promised to keep is not a guard.
 *
 * <p><b>The loopback half matters just as much.</b> On CE the catalog module
 * calls its own execute endpoint over localhost and carries these headers on
 * purpose. Stripping THAT would not harden anything, it would delete the
 * billing context of every generation, so both halves are pinned here.
 */
@DisplayName("MonolithSecurityFilter billing-context header strip")
class MonolithSecurityFilterBillingHeadersTest {

    private static final String EXTERNAL_IP = "203.0.113.10";
    private static final String EXECUTE_PATH = "/catalog/v1/tools/some-tool/execute";

    private static FilterChain capturing(AtomicReference<ServletRequest> captured) {
        return (ServletRequest req, ServletResponse res) -> captured.set(req);
    }

    private static MockHttpServletRequest withBillingHeaders(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", EXECUTE_PATH);
        request.setRemoteAddr(remoteAddr);
        request.addHeader("X-Lc-Generation-Model", "a-cheap-model");
        request.addHeader("X-Lc-Generation-Quantity", "0.000001");
        request.addHeader("X-Lc-Generation-Unit", "call");
        request.addHeader("X-Lc-Billing-Scope-Kind", "RUN");
        request.addHeader("X-Lc-Billing-Scope-Id", "someone-elses-run");
        request.addHeader("X-Lc-Billing-Step-Id", "step-1");
        return request;
    }

    @Test
    @DisplayName("an EXTERNAL caller cannot name the model, the size or the scope its call is billed on")
    void externalCallerCannotSetTheBillingContext() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = withBillingHeaders(EXTERNAL_IP);
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturing(captured));

        HttpServletRequest seen = (HttpServletRequest) captured.get();
        assertThat(seen).as("the request must reach the chain, stripped rather than refused").isNotNull();
        for (String header : BillingContextHeaders.ALL) {
            assertThat(seen.getHeader(header))
                    .as("%s decides an amount and must never survive from an external caller", header)
                    .isNull();
        }
    }

    @Test
    @DisplayName("casing does not smuggle one through, because HTTP header names are case-insensitive")
    void aDifferentlyCasedHeaderIsStrippedToo() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", EXECUTE_PATH);
        request.setRemoteAddr(EXTERNAL_IP);
        request.addHeader("x-lc-generation-quantity", "0.000001");
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturing(captured));

        assertThat(((HttpServletRequest) captured.get()).getHeader("X-Lc-Generation-Quantity")).isNull();
    }

    @Test
    @DisplayName("an in-process LOOPBACK call keeps them, or every generation loses its billing context")
    void aLoopbackCallKeepsTheBillingContext() throws Exception {
        // The catalog module calls its own execute endpoint over localhost and
        // sets these itself. Stripping here would not harden anything: it would
        // delete the model, the size and the scope from every generation, and a
        // scope-less generation is refused outright.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = withBillingHeaders("127.0.0.1");
        request.addHeader("X-User-ID", "7");
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturing(captured));

        HttpServletRequest seen = (HttpServletRequest) captured.get();
        assertThat(seen).isNotNull();
        assertThat(seen.getHeader("X-Lc-Generation-Model")).isEqualTo("a-cheap-model");
        assertThat(seen.getHeader("X-Lc-Generation-Quantity")).isEqualTo("0.000001");
        assertThat(seen.getHeader("X-Lc-Generation-Unit")).isEqualTo("call");
    }

    @Test
    @DisplayName("the two edges strip the SAME set, because they are two doors into one endpoint")
    void bothEdgesShareOneList() {
        // Restated in one of them is drifted in one of them, and the half that
        // drifts is the half that stops stripping.
        assertThat(BillingContextHeaders.ALL)
                .containsExactlyInAnyOrder(
                        "X-Lc-Billing-Scope-Kind",
                        "X-Lc-Billing-Scope-Id",
                        "X-Lc-Billing-Step-Id",
                        "X-Lc-Generation-Model",
                        "X-Lc-Generation-Quantity",
                        "X-Lc-Generation-Unit");
    }
}
