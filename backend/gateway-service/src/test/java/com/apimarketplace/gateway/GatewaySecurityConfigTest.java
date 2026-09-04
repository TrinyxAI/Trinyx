package com.apimarketplace.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityConfigTest {

    private static final String ISSUER = "https://auth.trinyx.fr/realms/trinyx";

    @Test
    void requiresTrinyxFrontendAudience() {
        var validator = GatewaySecurityConfig.audienceValidator("trinyx-frontend");

        assertThat(validator.validate(jwt(ISSUER, List.of("trinyx-frontend"),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(60))).hasErrors())
                .isFalse();
        assertThat(validator.validate(jwt(ISSUER, List.of("another-client"),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(60))).hasErrors())
                .isTrue();
    }

    @Test
    void standardIssuerAndTimeValidationRejectsWrongIssuerAndExpiredJwt() {
        var validator = JwtValidators.createDefaultWithIssuer(ISSUER);
        Instant now = Instant.now();

        assertThat(validator.validate(jwt(ISSUER, List.of("trinyx-frontend"),
                now.minusSeconds(10), now.plusSeconds(60))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt("https://attacker.invalid/realms/trinyx",
                List.of("trinyx-frontend"), now.minusSeconds(10), now.plusSeconds(60))).hasErrors())
                .isTrue();
        assertThat(validator.validate(jwt(ISSUER, List.of("trinyx-frontend"),
                now.minusSeconds(120), now.minusSeconds(60))).hasErrors()).isTrue();
    }

    @Test
    void workloadJwtClaimsCannotSatisfyTheBrowserUserJwtContract() {
        Instant now = Instant.now();
        Jwt workload = jwt("trinyx-cloud", List.of("trinyx-billing-authority"),
                now.minusSeconds(1), now.plusSeconds(60));

        assertThat(JwtValidators.createDefaultWithIssuer(ISSUER)
                .validate(workload).hasErrors()).isTrue();
        assertThat(GatewaySecurityConfig.audienceValidator("trinyx-frontend")
                .validate(workload).hasErrors()).isTrue();
    }

    @Test
    void phaseTwoApplicationSurfaceRequiresUserAuthentication() {
        for (String path : List.of(
                "/api/v2/workflows/dag/execute",
                "/api/v2/workflows/dag/runs/run/signals",
                "/api/agents",
                "/api/interfaces",
                "/api/data-sources",
                "/api/publications",
                "/api/publications/remote/marketplace",
                "/api/application-folders",
                "/api/agent-folders",
                "/api/table-folders",
                "/api/interface-folders",
                "/api/workflow-folders",
                "/api/credentials",
                "/api/conversations",
                "/api/generation/models",
                "/api/storage",
                "/api/storage/explorer/generations",
                "/api/model-config/catalog-sync",
                "/api/mcp/tools/list",
                "/api/ce-websearch/search",
                "/api/browser-agent/llm/v1/chat/completions",
                "/api/webhooks",
                "/api/organizations/00000000-0000-0000-0000-000000000001",
                "/ws/conversations")) {
            assertThat(GatewayPublicRoutes.matches(HttpMethod.POST, path))
                    .as("POST %s", path)
                    .isFalse();
        }

        assertThat(GatewayPublicRoutes.matches(
                HttpMethod.GET, "/app/public/capability-token/config")).isTrue();
        assertThat(GatewayPublicRoutes.matches(
                HttpMethod.GET, "/api/publications/remote/marketplace")).isFalse();
        assertThat(GatewayPublicRoutes.matches(
                HttpMethod.DELETE, "/api/organizations/00000000-0000-0000-0000-000000000001"))
                .isFalse();
    }

    @Test
    void websocketUpgradeAuthenticatesExistingJwtSubprotocol() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ws")
                .header("Sec-WebSocket-Protocol",
                        "lc.auth, lc.jwt.header.payload.signature, lc.org.organization-id")
                .build());

        var authentication = GatewaySecurityConfig.gatewayBearerTokenConverter()
                .convert(exchange).block();

        assertThat(authentication).isInstanceOf(BearerTokenAuthenticationToken.class);
        assertThat(authentication.getCredentials()).isEqualTo("header.payload.signature");
    }

    @Test
    void queryTokenAndNonWebsocketSubprotocolAreNeverAccepted() {
        var query = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ws?access_token=leaked").build());
        var unrelated = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me")
                .header("Sec-WebSocket-Protocol", "lc.jwt.attacker").build());

        assertThat(GatewaySecurityConfig.gatewayBearerTokenConverter()
                .convert(query).block()).isNull();
        assertThat(GatewaySecurityConfig.gatewayBearerTokenConverter()
                .convert(unrelated).block()).isNull();
    }

    @Test
    void publicAllowlistCoversOnlyAuditedSelfAuthenticatingRoutes() {
        assertThat(GatewayPublicRoutes.matches(HttpMethod.POST, "/webhooks/stripe")).isTrue();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/actuator/health")).isTrue();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/actuator/health/readiness")).isTrue();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/api/public")).isTrue();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/api/public/showcase")).isTrue();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/api/shared/token")).isTrue();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/api/files/proxy-signed")).isTrue();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET,
                "/api/ce-marketplace/00000000-0000-0000-0000-000000000001/snapshot")).isTrue();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.POST,
                "/api/ce-marketplace/00000000-0000-0000-0000-000000000001/snapshot")).isFalse();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.POST,
                "/api/ce-marketplace/00000000-0000-0000-0000-000000000001/acquire-with-auth")).isFalse();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET,
                "/api/ce-marketplace/00000000-0000-0000-0000-000000000001/snapshot/extra")).isFalse();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/api/cloud-link/callback")).isFalse();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/api/webhooks")).isFalse();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.POST, "/api/webhooks/arbitrary")).isFalse();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/api/v2/workflows/dag")).isFalse();
        assertThat(GatewayPublicRoutes.matches(HttpMethod.GET, "/api/internal/credentials")).isFalse();
        assertThat(String.join(",", GatewayPublicRoutes.securityMatchers()))
                .doesNotContain("/webhooks/**")
                .doesNotContain("/actuator/health**")
                .doesNotContain("/api/ce-marketplace/**")
                .contains("/actuator/health/**");
        assertThat(GatewayPublicRoutes.getSecurityMatchers())
                .containsExactly("/api/ce-marketplace/*/snapshot");
    }

    private Jwt jwt(String issuer, List<String> audience, Instant issuedAt, Instant expiresAt) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("subject")
                .issuer(issuer)
                .audience(audience)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
    }
}
