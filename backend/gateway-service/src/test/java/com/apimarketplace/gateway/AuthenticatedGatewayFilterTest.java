package com.apimarketplace.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                        .header("X-LiveContext-Install-Id", "forged-install")
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
        assertThat(headers.getFirst("X-LiveContext-Install-Id")).isNull();
        assertThat(headers.getFirst("X-User-Roles")).isNull();
        assertThat(headers.getFirst("X-Gateway-Secret")).isNull();
    }

    @Test
    void onlyStripeWebhookIsPublic() {
        AuthenticatedGatewayFilter filter = new AuthenticatedGatewayFilter(null, 1024);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        MockServerWebExchange stripe = MockServerWebExchange.from(
                MockServerHttpRequest.post("/webhooks/stripe").build());
        StepVerifier.create(filter.filter(stripe, candidate -> {
            forwarded.set(candidate);
            return Mono.empty();
        })).verifyComplete();
        assertThat(forwarded.get()).isNotNull();

        MockServerWebExchange unknown = MockServerWebExchange.from(
                MockServerHttpRequest.post("/webhooks/unverified").build());
        StepVerifier.create(filter.filter(unknown, ignored -> Mono.error(
                new AssertionError("unknown webhook must require authentication"))))
                .verifyComplete();
        assertThat(unknown.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void cdpUpgradeUsesItsOwnTokenProtocolButNeverTrustsBrowserIdentityHeaders() {
        AuthenticatedGatewayFilter filter = new AuthenticatedGatewayFilter(null, 1024);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/cdp/session-token/devtools/page/1")
                        .header("Upgrade", "websocket")
                        .header("Sec-WebSocket-Protocol", "trinyx.events, lc.jwt.browser-secret")
                        .header("Authorization", "Bearer browser-secret")
                        .header("X-User-ID", "999")
                        .header("X-Organization-ID", "forged")
                        .header("X-Gateway-Secret", "forged")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, candidate -> {
            forwarded.set(candidate);
            return Mono.empty();
        })).verifyComplete();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("Upgrade"))
                .isEqualTo("websocket");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-ID")).isNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Organization-ID")).isNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Gateway-Secret")).isNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("Authorization")).isNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("Sec-WebSocket-Protocol"))
                .isEqualTo("trinyx.events");
    }

    @Test
    void downstreamFailureIsNotRewrittenAsAuthenticationFailure() {
        GatewayIdentityClient identity = mock(GatewayIdentityClient.class);
        AuthenticatedGatewayFilter filter = new AuthenticatedGatewayFilter(identity, 1024);
        GatewayUserContext context = new GatewayUserContext(
                1L, "subject", Set.of("USER"), "org", "OWNER",
                List.of(new GatewayUserContext.Membership("org", "OWNER")),
                "principal", "payer", "install");
        Jwt jwt = Jwt.withTokenValue("jwt")
                .header("alg", "none")
                .subject("subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/profile")
                        .header("X-LiveContext-Install-Id", "install")
                        .build())
                .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();

        when(identity.resolve(eq("jwt"), eq("subject"), isNull(), isNull(), eq("install")))
                .thenReturn(Mono.just(context));
        when(identity.authorize(eq(context), isNull(), eq(false)))
                .thenReturn(Mono.just(new GatewayIdentityClient.EntitlementDecision(
                        true, "AUTHORIZED", 1L, Instant.now().plusSeconds(60))));
        when(identity.signed(anyString(), anyString(), any(byte[].class),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(new HttpHeaders());

        StepVerifier.create(filter.filter(exchange,
                        ignored -> Mono.error(new IllegalStateException("downstream unavailable"))))
                .expectErrorMessage("downstream unavailable")
                .verify();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void legacyInstallHeaderIsOnlyASelectorAndDownstreamGetsTrustedSignedHeader() {
        GatewayIdentityClient identity = mock(GatewayIdentityClient.class);
        AuthenticatedGatewayFilter filter = new AuthenticatedGatewayFilter(identity, 1024);
        String install = "40000000-0000-0000-0000-000000000004";
        GatewayUserContext context = new GatewayUserContext(
                1L, "subject", Set.of("USER"), "org", "OWNER",
                List.of(new GatewayUserContext.Membership("org", "OWNER")),
                "principal", "payer", install);
        Jwt jwt = Jwt.withTokenValue("jwt")
                .header("alg", "none")
                .subject("subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/profile")
                        .header("Authorization", "Bearer jwt")
                        .header("X-LiveContext-Install-Id", install)
                        .build())
                .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();

        when(identity.resolve("jwt", "subject", null, null, install))
                .thenReturn(Mono.just(context));
        when(identity.authorize(context, null, false))
                .thenReturn(Mono.just(new GatewayIdentityClient.EntitlementDecision(
                        true, "AUTHORIZED", 1L, Instant.now().plusSeconds(60))));
        HttpHeaders signed = new HttpHeaders();
        signed.set("X-Install-ID", install);
        when(identity.signed(anyString(), anyString(), any(byte[].class),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(signed);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, candidate -> {
            forwarded.set(candidate);
            return Mono.empty();
        })).verifyComplete();

        assertThat(forwarded.get().getRequest().getHeaders()
                .getFirst("Authorization")).isNull();
        assertThat(forwarded.get().getRequest().getHeaders()
                .getFirst("X-LiveContext-Install-Id")).isNull();
        assertThat(forwarded.get().getRequest().getHeaders()
                .getFirst("X-Install-ID")).isEqualTo(install);
    }

    @Test
    void conflictingExternalInstallSelectorsFailClosedBeforeIdentityResolution() {
        GatewayIdentityClient identity = mock(GatewayIdentityClient.class);
        AuthenticatedGatewayFilter filter = new AuthenticatedGatewayFilter(identity, 1024);
        Jwt jwt = Jwt.withTokenValue("jwt")
                .header("alg", "none")
                .subject("subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/profile")
                        .header("X-LiveContext-Install-Id",
                                "40000000-0000-0000-0000-000000000004")
                        .header("X-Install-ID",
                                "50000000-0000-0000-0000-000000000005")
                        .build())
                .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(
                        new AssertionError("chain must not run"))))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        org.mockito.Mockito.verifyNoInteractions(identity);
    }

    @Test
    void membersKeepDistinctActorsWhileSharingOneBillingSubjectAndOrganization() {
        GatewayUserContext memberB = new GatewayUserContext(
                2L, "subject-b", Set.of("USER"), "org-a", "MEMBER",
                List.of(new GatewayUserContext.Membership("org-a", "MEMBER")),
                "principal-b", "payer-owner", "install");
        GatewayUserContext memberC = new GatewayUserContext(
                3L, "subject-c", Set.of("USER"), "org-a", "MEMBER",
                List.of(new GatewayUserContext.Membership("org-a", "MEMBER")),
                "principal-c", "payer-owner", "install");

        assertThat(memberB.principalId()).isNotEqualTo(memberC.principalId());
        assertThat(memberB.billingSubjectId()).isEqualTo(memberC.billingSubjectId());
        assertThat(memberB.defaultOrganizationId()).isEqualTo(memberC.defaultOrganizationId());
        assertThat(memberB.roleFor("org-a")).isEqualTo("MEMBER");
        assertThat(memberC.roleFor("org-a")).isEqualTo("MEMBER");
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
