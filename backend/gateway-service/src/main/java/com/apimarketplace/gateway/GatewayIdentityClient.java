package com.apimarketplace.gateway;

import com.apimarketplace.common.web.GatewaySignatureV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
final class GatewayIdentityClient {

    private final WebClient auth;
    private final ObjectMapper mapper;
    private final String secret;

    GatewayIdentityClient(WebClient.Builder builder, ObjectMapper mapper,
                          @Value("${trinyx.gateway.auth-service-url}") String authServiceUrl,
                          @Value("${trinyx.gateway.hmac-secret}") String secret) {
        this.auth = builder.baseUrl(authServiceUrl).build();
        this.mapper = mapper;
        this.secret = secret;
    }

    Mono<GatewayUserContext> resolve(String bearerToken, String providerId, String bindingJws,
                                     String entitlementJws, String installSelector) {
        String target = "/api/users/resolve?providerId="
                + URLEncoder.encode(providerId, StandardCharsets.UTF_8);
        HttpHeaders signed = signed("GET", target, new byte[0], providerId,
                "", "", "", "", "", "", "");
        return auth.get().uri(target)
                .headers(headers -> {
                    headers.addAll(signed);
                    headers.setBearerAuth(bearerToken);
                })
                .retrieve()
                .bodyToMono(UserResolution.class)
                .flatMap(user -> resolveBinding(user, providerId, bindingJws, installSelector))
                .flatMap(context -> applyProjection(context, providerId, entitlementJws)
                        .thenReturn(context));
    }

    private Mono<GatewayUserContext> resolveBinding(
            UserResolution user, String providerId, String bindingJws, String installSelector) {
        // Resolve persistent state first. A bootstrap assertion is consumed only when that exact
        // install is not bound yet; retaining an expired bootstrap header therefore cannot break
        // an already-materialized link.
        String target = "/api/internal/cloud-identity/context?keycloakSubject="
                + URLEncoder.encode(providerId, StandardCharsets.UTF_8);
        if (installSelector != null && !installSelector.isBlank()) {
            try {
                UUID.fromString(installSelector);
            } catch (IllegalArgumentException invalidSelector) {
                return Mono.error(new UnboundIdentityException());
            }
            target += "&installId=" + URLEncoder.encode(installSelector, StandardCharsets.UTF_8);
        }
        HttpHeaders headers = signed("GET", target, new byte[0], providerId,
                String.valueOf(user.userId()), user.principalId(), user.billingSubjectId(),
                user.defaultOrganizationId(), user.defaultOrganizationRole(),
                canonicalRoles(user.roles()), "");
        return auth.get().uri(target)
                .headers(out -> out.addAll(headers))
                .retrieve()
                .bodyToMono(BindingContext.class)
                .map(binding -> merge(user, binding))
                .onErrorResume(org.springframework.web.reactive.function.client.WebClientResponseException.NotFound.class,
                        missing -> bindingJws == null || bindingJws.isBlank()
                                ? Mono.error(new UnboundIdentityException())
                                : bind(user, providerId, bindingJws));
    }

    private Mono<GatewayUserContext> bind(
            UserResolution user, String providerId, String bindingJws) {
        try {
            byte[] body = mapper.writeValueAsBytes(java.util.Map.of("identityBinding", bindingJws));
            String target = "/api/internal/cloud-identity/bind";
            HttpHeaders headers = signed("POST", target, body, providerId,
                    String.valueOf(user.userId()), user.principalId(), user.billingSubjectId(),
                    user.defaultOrganizationId(), user.defaultOrganizationRole(),
                    canonicalRoles(user.roles()), "");
            return auth.post().uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(out -> out.addAll(headers))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(BindingContext.class)
                    .map(binding -> merge(user, binding));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private Mono<Void> applyProjection(GatewayUserContext context, String providerId,
                                       String entitlementJws) {
        if (entitlementJws == null || entitlementJws.isBlank()) {
            return Mono.empty();
        }
        try {
            byte[] body = mapper.writeValueAsBytes(java.util.Map.of("assertion", entitlementJws));
            String target = "/api/internal/v1/entitlement-projections/repair";
            HttpHeaders headers = signed("PUT", target, body, providerId,
                    String.valueOf(context.userId()), context.principalId(),
                    context.billingSubjectId(), context.defaultOrganizationId(),
                    context.defaultOrganizationRole(), canonicalRoles(context.roles()),
                    context.installId());
            return auth.put().uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(out -> out.addAll(headers))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class);
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    Mono<EntitlementDecision> authorize(GatewayUserContext context, String feature,
                                         boolean paidOperation) {
        StringBuilder target = new StringBuilder("/api/internal/v1/entitlement-projections/decision")
                .append("?installId=").append(encode(context.installId()))
                .append("&organizationId=").append(encode(context.defaultOrganizationId()))
                .append("&billingSubjectId=").append(encode(context.billingSubjectId()))
                .append("&paidOperation=").append(paidOperation);
        if (feature != null && !feature.isBlank()) {
            target.append("&feature=").append(encode(feature));
        }
        String requestTarget = target.toString();
        HttpHeaders headers = signed("GET", requestTarget, new byte[0], context.providerId(),
                String.valueOf(context.userId()), context.principalId(),
                context.billingSubjectId(), context.defaultOrganizationId(),
                context.defaultOrganizationRole(), canonicalRoles(context.roles()),
                context.installId());
        return auth.get().uri(requestTarget)
                .headers(out -> out.addAll(headers))
                .retrieve()
                .bodyToMono(EntitlementDecision.class);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private GatewayUserContext merge(UserResolution user, BindingContext binding) {
        String boundRole = GatewaySignatureV2.canonicalRole(binding.organizationRole());
        if (!Set.of("OWNER", "ADMIN", "MEMBER", "VIEWER").contains(boundRole)) {
            throw new IllegalStateException("Signed organization role is invalid");
        }
        // The paid authority owns the cross-system organization scope. A just-in-time
        // Cloud user initially has only a local personal workspace, so requiring a pre-existing
        // membership here would make first link impossible. Materialize the signed membership in
        // gateway context; downstream services receive it only through HMAC v2 headers.
        List<GatewayUserContext.Membership> memberships = new ArrayList<>(
                user.memberships() == null ? List.of() : user.memberships());
        memberships.removeIf(existing -> binding.organizationId().equals(existing.orgId()));
        memberships.add(new GatewayUserContext.Membership(binding.organizationId(), boundRole));
        return new GatewayUserContext(user.userId(), user.providerId(), user.roles(),
                binding.organizationId(), boundRole, List.copyOf(memberships),
                binding.principalId(), binding.billingSubjectId(), binding.installId());
    }

    HttpHeaders signed(String method, String target, byte[] body,
                       String providerId, String userId, String principalId,
                       String billingSubjectId, String organizationId,
                       String organizationRole, String userRoles, String installId) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String hash = GatewaySignatureV2.sha256Hex(body);
        GatewaySignatureV2.Context context = new GatewaySignatureV2.Context(
                timestamp, nonce, method, target, hash, providerId, userId,
                principalId, billingSubjectId, organizationId, organizationRole,
                userRoles, installId);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Gateway-Signature-Version", "2");
        headers.set("X-Gateway-Timestamp", timestamp);
        headers.set("X-Gateway-Nonce", nonce);
        headers.set("X-Gateway-Body-SHA256", hash);
        headers.set("X-Gateway-Secret", GatewaySignatureV2.sign(secret, context));
        headers.set("X-Provider-ID", providerId);
        set(headers, "X-User-ID", userId);
        set(headers, "X-Principal-ID", principalId);
        set(headers, "X-Billing-Subject-ID", billingSubjectId);
        set(headers, "X-Organization-ID", organizationId);
        set(headers, "X-Organization-Role", GatewaySignatureV2.canonicalRole(organizationRole));
        set(headers, "X-User-Roles", GatewaySignatureV2.canonicalRoles(userRoles));
        set(headers, "X-Install-ID", installId);
        return headers;
    }

    private String canonicalRoles(Set<String> roles) {
        return GatewaySignatureV2.canonicalRoles(roles == null ? "" : String.join(",", roles));
    }

    private static void set(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) headers.set(name, value);
    }

    private record UserResolution(Long userId, String providerId, Set<String> roles,
                                  String defaultOrganizationId, String defaultOrganizationRole,
                                  List<GatewayUserContext.Membership> memberships,
                                  String principalId, String billingSubjectId) {}

    private record BindingContext(Long userId, String providerId, String principalId,
                                  String billingSubjectId, String organizationId,
                                  String organizationRole, String installId,
                                  long bindingRevision, String status) {}

    record EntitlementDecision(boolean allowed, String reason, long sequence,
                               java.time.Instant expiresAt) {}

    static final class UnboundIdentityException extends RuntimeException {
        UnboundIdentityException() { super("Cloud identity is not bound"); }
    }
}
