package com.apimarketplace.gateway;

import com.apimarketplace.common.web.GatewaySignatureV2;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

@Component
final class AuthenticatedGatewayFilter implements GlobalFilter, Ordered {

    private static final Set<String> STRIPPED = Set.of(
            "x-gateway-secret", "x-gateway-signature-version", "x-gateway-timestamp",
            "x-gateway-nonce", "x-gateway-body-sha256", "x-provider-id", "x-user-id",
            "x-principal-id", "x-billing-subject-id", "x-organization-id",
            "x-organization-role", "x-user-roles", "x-install-id",
            "x-trinyx-identity-binding", "x-trinyx-entitlement-projection",
            "x-trinyx-organization-id");

    private final GatewayIdentityClient identityClient;
    private final int maxBodyBytes;

    AuthenticatedGatewayFilter(
            GatewayIdentityClient identityClient,
            @Value("${trinyx.gateway.max-body-bytes:10485760}") int maxBodyBytes) {
        this.identityClient = identityClient;
        this.maxBodyBytes = Math.max(1, maxBodyBytes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/api/internal/")) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        if (isPublic(path)) {
            return chain.filter(stripSpoofable(exchange));
        }

        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .switchIfEmpty(Mono.error(new IllegalStateException("JWT principal missing")))
                .flatMap(authentication -> {
                    String token = authentication.getToken().getTokenValue();
                    String subject = authentication.getToken().getSubject();
                    String binding = exchange.getRequest().getHeaders()
                            .getFirst("X-Trinyx-Identity-Binding");
                    String entitlement = exchange.getRequest().getHeaders()
                            .getFirst("X-Trinyx-Entitlement-Projection");
                    return identityClient.resolve(token, subject, binding, entitlement)
                            .flatMap(context -> {
                                EntitlementPolicy policy = policyFor(path);
                                return identityClient.authorize(
                                                context, policy.feature(), policy.paidOperation())
                                        .flatMap(decision -> decision.allowed()
                                                ? withBody(exchange, chain, subject, context)
                                                : forbidden(exchange, decision.reason()));
                            });
                })
                .onErrorResume(error -> {
                    exchange.getResponse().setStatusCode(
                            error instanceof GatewayIdentityClient.UnboundIdentityException
                                    ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED);
                    byte[] body = ("{\"error\":\"gateway_identity_rejected\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    return exchange.getResponse().writeWith(Mono.just(
                            exchange.getResponse().bufferFactory().wrap(body)));
                });
    }

    private Mono<Void> withBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                String providerId, GatewayUserContext context) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), maxBodyBytes)
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(buffer -> {
                    byte[] body = new byte[buffer.readableByteCount()];
                    buffer.read(body);
                    DataBufferUtils.release(buffer);

                    String requestedOrg = exchange.getRequest().getHeaders()
                            .getFirst("X-Trinyx-Organization-ID");
                    if (requestedOrg != null && !requestedOrg.isBlank()
                            && !requestedOrg.equals(context.defaultOrganizationId())) {
                        return forbidden(exchange, "identity_binding_scope_mismatch");
                    }
                    String organizationId = context.defaultOrganizationId();
                    String organizationRole = context.roleFor(organizationId);
                    if (organizationId != null && organizationRole == null) {
                        return forbidden(exchange, "organization_membership_required");
                    }

                    URI downstream = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
                    if (downstream == null) {
                        downstream = exchange.getRequest().getURI();
                    }
                    String rawPath = downstream.getRawPath();
                    String rawQuery = downstream.getRawQuery();
                    String target = rawPath + (rawQuery == null ? "" : "?" + rawQuery);
                    String roles = GatewaySignatureV2.canonicalRoles(
                            context.roles() == null ? "" : String.join(",", context.roles()));
                    var signed = identityClient.signed(
                            exchange.getRequest().getMethod().name(), target, body, providerId,
                            String.valueOf(context.userId()), context.principalId(),
                            context.billingSubjectId(), organizationId, organizationRole,
                            roles, context.installId());

                    ServerHttpRequest request = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public org.springframework.http.HttpHeaders getHeaders() {
                            org.springframework.http.HttpHeaders headers =
                                    new org.springframework.http.HttpHeaders();
                            exchange.getRequest().getHeaders().forEach((name, values) -> {
                                if (!STRIPPED.contains(name.toLowerCase())
                                        && !name.equalsIgnoreCase("X-Trinyx-Identity-Binding")
                                        && !name.equalsIgnoreCase("X-Trinyx-Entitlement-Projection")
                                        && !name.equalsIgnoreCase("X-Trinyx-Organization-ID")) {
                                    headers.put(name, values);
                                }
                            });
                            headers.putAll(signed);
                            headers.setContentLength(body.length);
                            return headers;
                        }

                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.defer(() -> Flux.just(
                                    exchange.getResponse().bufferFactory().wrap(body)));
                        }
                    };
                    return chain.filter(exchange.mutate().request(request).build());
                })
                .onErrorResume(DataBufferLimitException.class, error -> {
                    exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                    return exchange.getResponse().setComplete();
                });
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String code) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory().wrap(("{\"error\":\"" + code + "\"}")
                        .getBytes(StandardCharsets.UTF_8))));
    }

    private ServerWebExchange stripSpoofable(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    List<String> names = List.copyOf(headers.keySet());
                    names.stream().filter(name -> STRIPPED.contains(name.toLowerCase()))
                            .forEach(headers::remove);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private boolean isPublic(String path) {
        return path.equals("/healthz")
                || path.startsWith("/actuator/health")
                || path.startsWith("/cdp/")
                || path.startsWith("/webhooks/")
                || path.startsWith("/api/catalog/public/bundles/");
    }

    private EntitlementPolicy policyFor(String path) {
        if (path.startsWith("/api/ce-llm/")) {
            return new EntitlementPolicy("cloudLlmRelay", true);
        }
        if (path.startsWith("/api/ce-websearch/") || path.startsWith("/cdp/")) {
            return new EntitlementPolicy("cloudWebSearchRelay", true);
        }
        if (path.startsWith("/api/skill-bundles/")) {
            return new EntitlementPolicy("skillBundle", false);
        }
        if (path.startsWith("/api/catalog-bundles/")
                || path.startsWith("/api/ce-catalog/")) {
            return new EntitlementPolicy("catalogBundle", false);
        }
        return new EntitlementPolicy(null, false);
    }

    private record EntitlementPolicy(String feature, boolean paidOperation) {}

    @Override
    public int getOrder() {
        // After route rewrite filters and before NettyRoutingFilter.
        return 10_050;
    }
}
