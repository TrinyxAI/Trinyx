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
import java.util.Locale;
import java.util.Set;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

@Component
final class AuthenticatedGatewayFilter implements GlobalFilter, Ordered {

    private static final Set<String> STRIPPED = Set.of(
            "authorization", "x-gateway-secret", "x-gateway-signature-version", "x-gateway-timestamp",
            "x-gateway-nonce", "x-gateway-body-sha256", "x-provider-id", "x-user-id",
            "x-principal-id", "x-billing-subject-id", "x-organization-id",
            "x-organization-role", "x-user-roles", "x-install-id",
            "x-livecontext-install-id", "x-trinyx-install-id",
            "x-trinyx-identity-binding", "x-trinyx-entitlement-projection",
            "x-trinyx-organization-id");

    private final GatewayIdentityClient identityClient;
    private final int maxBodyBytes;

    AuthenticatedGatewayFilter(
            GatewayIdentityClient identityClient,
            @Value("${trinyx.gateway.max-body-bytes:52428800}") int maxBodyBytes) {
        this.identityClient = identityClient;
        this.maxBodyBytes = Math.max(1, maxBodyBytes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI requestUri = exchange.getRequest().getURI();
        if (hasAmbiguousPath(requestUri)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        String path = requestUri.getPath();
        if (path.startsWith("/api/internal/") || path.startsWith("/internal/")) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        if (isPublic(exchange)) {
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
                    String installSelector;
                    try {
                        installSelector = installSelector(exchange.getRequest().getHeaders());
                    } catch (IllegalArgumentException conflictingSelector) {
                        return forbidden(exchange, "conflicting_install_selector");
                    }
                    EntitlementPolicy policy = policyFor(path);

                    Mono<GatewayUserContext> authorized = identityClient
                            .resolve(token, subject, binding, entitlement, installSelector)
                            .flatMap(context -> {
                                if (policy.identityOnly()) {
                                    return Mono.just(context);
                                }
                                return identityClient.authorize(
                                                context, policy.feature(), policy.paidOperation())
                                        .flatMap(decision -> decision.allowed()
                                                ? Mono.just(context)
                                                : Mono.error(new EntitlementDeniedException(
                                                        decision.reason())));
                            });

                    // Only identity/entitlement resolution errors are translated here. Downstream
                    // routing/provider failures happen in withBody() after this boundary and must
                    // retain their real 5xx/error semantics instead of being mislabeled as 401.
                    return authorized
                            .onErrorResume(error -> rejectIdentity(exchange, error))
                            .flatMap(context -> withBody(exchange, chain, subject, context));
                });
    }

    private Mono<GatewayUserContext> rejectIdentity(ServerWebExchange exchange, Throwable error) {
        HttpStatus status;
        String code;
        if (error instanceof EntitlementDeniedException denied) {
            status = HttpStatus.FORBIDDEN;
            code = denied.code();
        } else if (error instanceof GatewayIdentityClient.UnboundIdentityException) {
            status = HttpStatus.FORBIDDEN;
            code = "gateway_identity_rejected";
        } else if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
            status = response.getStatusCode().is5xxServerError()
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
            code = response.getStatusCode().is5xxServerError()
                    ? "identity_authority_unavailable" : "gateway_identity_rejected";
        } else if (error instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            code = "identity_authority_unavailable";
        } else {
            status = HttpStatus.UNAUTHORIZED;
            code = "gateway_identity_rejected";
        }
        return writeError(exchange, status, code).then(Mono.empty());
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code) {
        exchange.getResponse().setStatusCode(status);
        byte[] body = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }

    private static final class EntitlementDeniedException extends RuntimeException {
        private final String code;

        private EntitlementDeniedException(String code) {
            super(code);
            this.code = code == null || code.isBlank() ? "ENTITLEMENT_DENIED" : code;
        }

        private String code() {
            return code;
        }
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
                                    if (name.equalsIgnoreCase("Sec-WebSocket-Protocol")) {
                                        List<String> safeProtocols = websocketProtocolsWithoutJwt(values);
                                        if (!safeProtocols.isEmpty()) {
                                            headers.put(name, safeProtocols);
                                        }
                                    } else {
                                        headers.put(name, values);
                                    }
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
                    List<String> safeProtocols = websocketProtocolsWithoutJwt(
                            headers.getOrEmpty("Sec-WebSocket-Protocol"));
                    if (safeProtocols.isEmpty()) {
                        headers.remove("Sec-WebSocket-Protocol");
                    } else {
                        headers.put("Sec-WebSocket-Protocol", safeProtocols);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private static List<String> websocketProtocolsWithoutJwt(List<String> values) {
        return values.stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(value -> !value.startsWith("lc.jwt."))
                .toList();
    }

    /**
     * Treat installation headers only as untrusted selectors. The selected value is resolved
     * against the actor's ACTIVE signed binding, stripped from the external request, and replaced
     * downstream by the HMAC-bound X-Install-ID from the resolved context.
     */
    private static String installSelector(org.springframework.http.HttpHeaders headers) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (String name : List.of("X-Install-ID", "X-LiveContext-Install-Id",
                "X-Trinyx-Install-ID")) {
            String value = headers.getFirst(name);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException("Conflicting installation selectors");
        }
        return values.isEmpty() ? null : values.iterator().next();
    }

    /**
     * Reject path forms whose interpretation can differ between the edge, route predicates,
     * downstream HTTP stacks, and application routers. Ordinary percent-encoded characters remain
     * valid; encoded separators, double encoding, duplicate separators and dot segments do not.
     */
    private static boolean hasAmbiguousPath(URI uri) {
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isEmpty() || rawPath.charAt(0) != '/') {
            return true;
        }
        String lowerRawPath = rawPath.toLowerCase(Locale.ROOT);
        if (rawPath.contains("//") || rawPath.indexOf('\\') >= 0
                || lowerRawPath.contains("%2f") || lowerRawPath.contains("%5c")
                || lowerRawPath.contains("%25")) {
            return true;
        }
        String decodedPath = uri.getPath();
        if (decodedPath == null) {
            return true;
        }
        for (String segment : decodedPath.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPublic(ServerWebExchange exchange) {
        return GatewayPublicRoutes.matches(
                exchange.getRequest().getMethod(), exchange.getRequest().getURI().getPath());
    }

    private EntitlementPolicy policyFor(String path) {
        if (path.startsWith("/api/ce-llm/")) {
            return new EntitlementPolicy("cloudLlmRelay", true, false);
        }
        if (path.startsWith("/api/ce-websearch/")) {
            return new EntitlementPolicy("cloudWebSearchRelay", true, false);
        }
        if (path.startsWith("/api/skill-bundles/")) {
            return new EntitlementPolicy("skillBundle", false, false);
        }
        if (path.startsWith("/api/catalog-bundles/")
                || path.startsWith("/api/ce-catalog/")) {
            return new EntitlementPolicy("catalogBundle", false, false);
        }
        if (path.startsWith("/api/ce-link/")) {
            // Registration, heartbeat, entitlement repair/read and unlink are control-plane
            // lifecycle operations. Identity remains mandatory, while stale billing state must
            // not prevent repair or revocation. The endpoint itself still returns no effective
            // plan for an expired/revoked projection.
            return new EntitlementPolicy(null, false, true);
        }
        return new EntitlementPolicy(null, false, false);
    }

    private record EntitlementPolicy(String feature, boolean paidOperation, boolean identityOnly) {}

    @Override
    public int getOrder() {
        // After route rewrite filters and before NettyRoutingFilter.
        return 10_050;
    }
}
