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
                                     String entitlementJws) {
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
                .flatMap(user -> resolveBinding(user, providerId, bindingJws))
                .flatMap(context -> applyProjection(context, providerId, entitlementJws)
                        .thenReturn(context));
    }

    private Mono<GatewayUserContext> resolveBinding(
            UserResolution user, String providerId, String bindingJws) {
        String target = "/api/internal/cloud-identity/context?keycloakSubject="
                + URLEncoder.encode(providerId, StandardCharsets.UTF_8);
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
            String target = "/api/internal/v1/entitlement-projections";
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

    private GatewayUserContext merge(UserResolution user, BindingContext binding) {
        String boundRole = user.memberships() == null ? null : user.memberships().stream()
                .filter(membership -> binding.organizationId().equals(membership.orgId()))
                .map(GatewayUserContext.Membership::role)
                .findFirst().orElse(null);
        if (boundRole == null) {
            throw new IllegalStateException("Bound organization membership is missing");
        }
        return new GatewayUserContext(user.userId(), user.providerId(), user.roles(),
                binding.organizationId(), boundRole, user.memberships(),
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
                                  String installId, long bindingRevision, String status) {}

    static final class UnboundIdentityException extends RuntimeException {
        UnboundIdentityException() { super("Cloud identity is not bound"); }
    }
}
