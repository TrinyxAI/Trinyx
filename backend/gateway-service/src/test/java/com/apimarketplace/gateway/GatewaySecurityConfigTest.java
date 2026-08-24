package com.apimarketplace.gateway;

import org.junit.jupiter.api.Test;
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
