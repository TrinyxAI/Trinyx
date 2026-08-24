package com.apimarketplace.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import reactor.core.publisher.Mono;

import java.util.Arrays;

@Configuration
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health", "/healthz").permitAll()
                        .pathMatchers("/webhooks/stripe", "/cdp/**").permitAll()
                        .pathMatchers("/api/catalog/public/bundles/**", "/api/ce/releases/latest").permitAll()
                        .pathMatchers("/api/internal/**", "/internal/**").denyAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenConverter(gatewayBearerTokenConverter())
                        .jwt(jwt -> {}))
                .build();
    }


    /**
     * Browsers cannot set an Authorization header during a WebSocket upgrade. The existing
     * LiveContext client deliberately transports its Keycloak token in the lc.jwt.* subprotocol;
     * authenticate that exact /ws handshake while preserving normal bearer-header precedence.
     * Query-string bearer tokens stay disabled to avoid token leakage in proxy logs.
     */
    static ServerAuthenticationConverter gatewayBearerTokenConverter() {
        ServerBearerTokenAuthenticationConverter header =
                new ServerBearerTokenAuthenticationConverter();
        header.setAllowUriQueryParameter(false);
        return exchange -> header.convert(exchange).switchIfEmpty(Mono.defer(() -> {
            String path = exchange.getRequest().getURI().getPath();
            if (!(path.equals("/ws") || path.startsWith("/ws/"))) return Mono.empty();
            return exchange.getRequest().getHeaders().getOrEmpty("Sec-WebSocket-Protocol").stream()
                    .flatMap(value -> Arrays.stream(value.split(",")))
                    .map(String::trim)
                    .filter(protocol -> protocol.startsWith("lc.jwt."))
                    .map(protocol -> protocol.substring("lc.jwt.".length()))
                    .filter(token -> !token.isBlank())
                    .findFirst()
                    .<Mono<org.springframework.security.core.Authentication>>map(
                            token -> Mono.just(new BearerTokenAuthenticationToken(token)))
                    .orElseGet(Mono::empty);
        }));
    }

    @Bean
    NimbusReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${trinyx.gateway.jwt.issuer}") String issuer,
            @Value("${trinyx.gateway.jwt.audience}") String audience) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = audienceValidator(audience);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Required audience is missing", null));
    }
}
