package com.apimarketplace.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedGatewayFilterTest {

    @Test
    void internalApisAreNeverExposedAtTheEdge() {
        AuthenticatedGatewayFilter filter = new AuthenticatedGatewayFilter(null, 1024);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/internal/entitlements/v2/projections").build());

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(
                        new AssertionError("chain must not run"))))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void workloadInternalApisAreNeverExposedAtTheEdge() {
        AuthenticatedGatewayFilter filter = new AuthenticatedGatewayFilter(null, 1024);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.put("/internal/v1/identity-bindings/revocations").build());

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(
                        new AssertionError("chain must not run"))))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void publicRoutesStripEverySpoofableIdentityHeader() {
        AuthenticatedGatewayFilter filter = new AuthenticatedGatewayFilter(null, 1024);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/catalog/public/bundles/v1")
                        .header("X-User-ID", "999")
                        .header("X-Principal-ID", "forged")
                        .header("X-Billing-Subject-ID", "forged")
                        .header("X-Organization-ID", "forged")
                        .header("X-Trinyx-Organization-ID", "forged")
                        .header("X-User-Roles", "ADMIN")
                        .header("X-Gateway-Secret", "forged")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = candidate -> {
            forwarded.set(candidate);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        var headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-ID")).isNull();
        assertThat(headers.getFirst("X-Principal-ID")).isNull();
        assertThat(headers.getFirst("X-Billing-Subject-ID")).isNull();
        assertThat(headers.getFirst("X-Organization-ID")).isNull();
        assertThat(headers.getFirst("X-Trinyx-Organization-ID")).isNull();
        assertThat(headers.getFirst("X-User-Roles")).isNull();
        assertThat(headers.getFirst("X-Gateway-Secret")).isNull();
    }

    @Test
    void selectedOrganizationMustBeARealMembership() {
        GatewayUserContext context = new GatewayUserContext(
                1L, "subject", Set.of("USER"), "org-a", "OWNER",
                List.of(new GatewayUserContext.Membership("org-a", "OWNER"),
                        new GatewayUserContext.Membership("org-b", "MEMBER")),
                "principal", "payer", "install");

        assertThat(context.roleFor("org-b")).isEqualTo("MEMBER");
        assertThat(context.roleFor("org-forged")).isNull();
        assertThat(context.roleFor(null)).isEqualTo("OWNER");
    }
}
