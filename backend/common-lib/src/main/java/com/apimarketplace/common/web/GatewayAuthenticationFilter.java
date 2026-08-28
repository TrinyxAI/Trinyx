package com.apimarketplace.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Verifies gateway-authenticated context. Version 1 remains available only for
 * controlled migration; version 2 binds the complete request and prevents replay.
 */
@Order(1)
public class GatewayAuthenticationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticationFilter.class);

    private static final String SECRET = "X-Gateway-Secret";
    private static final String VERSION = "X-Gateway-Signature-Version";
    private static final String TIMESTAMP = "X-Gateway-Timestamp";
    private static final String NONCE = "X-Gateway-Nonce";
    private static final String BODY_HASH = "X-Gateway-Body-SHA256";
    private static final String PROVIDER = "X-Provider-ID";

    private final GatewayFilterProperties properties;
    private final GatewayNonceStore nonceStore;

    /** Test/backward-compatible constructor. Production auto-configuration supplies Redis. */
    public GatewayAuthenticationFilter(GatewayFilterProperties properties) {
        this(properties, new InMemoryGatewayNonceStore());
    }

    public GatewayAuthenticationFilter(GatewayFilterProperties properties, GatewayNonceStore nonceStore) {
        this.properties = properties;
        this.nonceStore = nonceStore;
        if (properties.isVerificationEnabled() && isUnsafeSecret(properties.getSecretKey())) {
            throw new IllegalStateException(
                    "gateway.filter.secret-key must be configured when gateway verification is enabled");
        }
        if (properties.isVerificationEnabled()
                && properties.isRequireDistributedNonceStore()
                && !nonceStore.distributed()) {
            throw new IllegalStateException(
                    "Gateway HMAC v2 requires a distributed nonce store in this environment");
        }
        if (!properties.isVerificationEnabled()) {
            log.warn("SECURITY WARNING: gateway HMAC verification is disabled");
        }
        if (properties.isVerificationEnabled() && properties.isAcceptV1()) {
            log.warn("Gateway HMAC v1 compatibility is enabled; disable it after signer migration");
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse target = (HttpServletResponse) response;

        if (!properties.isVerificationEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = http.getRequestURI();
        if (isPublicEndpoint(path) && !isHmacRequiredEndpoint(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (GatewaySignatureV2.VERSION.equals(http.getHeader(VERSION))) {
            verifyV2(http, target, chain);
            return;
        }

        if (!properties.isAcceptV1()) {
            rejectRequest(target, HttpServletResponse.SC_UNAUTHORIZED,
                    "Gateway signature version 2 is required");
            return;
        }
        verifyV1(http, target, chain);
    }

    private void verifyV2(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String signature = request.getHeader(SECRET);
        String timestamp = request.getHeader(TIMESTAMP);
        String nonce = request.getHeader(NONCE);
        String bodyHash = request.getHeader(BODY_HASH);
        String providerId = request.getHeader(PROVIDER);

        if (blank(signature) || blank(timestamp) || blank(nonce)
                || blank(bodyHash) || blank(providerId)) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing gateway authentication v2 headers");
            return;
        }
        if (!signature.startsWith(GatewaySignatureV2.PREFIX)
                || !timestampWithinAbsoluteSkew(timestamp, properties.getV2TimestampSkewMs())) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Invalid gateway signature or timestamp");
            return;
        }

        String userRoles = request.getHeader("X-User-Roles");
        String organizationRole = request.getHeader("X-Organization-Role");
        if (!GatewaySignatureV2.rolesAreSafe(userRoles)
                || !GatewaySignatureV2.rolesAreSafe(organizationRole)) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid role context");
            return;
        }

        CachedBodyHttpServletRequest cached;
        try {
            cached = new CachedBodyHttpServletRequest(request, properties.getMaxBodyBytes());
        } catch (CachedBodyHttpServletRequest.BodyTooLargeException tooLarge) {
            rejectRequest(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, tooLarge.getMessage());
            return;
        }

        String actualBodyHash = GatewaySignatureV2.sha256Hex(cached.body());
        if (!GatewaySignatureV2.constantTimeEquals(bodyHash.toLowerCase(), actualBodyHash)) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED, "Gateway body hash mismatch");
            return;
        }

        String query = request.getQueryString();
        String requestTarget = request.getRequestURI()
                + (query == null || query.isEmpty() ? "" : "?" + query);
        GatewaySignatureV2.Context context = new GatewaySignatureV2.Context(
                timestamp,
                nonce,
                request.getMethod(),
                requestTarget,
                actualBodyHash,
                providerId,
                request.getHeader("X-User-ID"),
                request.getHeader("X-Principal-ID"),
                request.getHeader("X-Billing-Subject-ID"),
                request.getHeader("X-Organization-ID"),
                organizationRole,
                userRoles,
                request.getHeader("X-Install-ID"));

        String signingSecret = properties.secretFor(providerId, request.getRequestURI());
        if (signingSecret == null
                || !properties.serviceMayCall(providerId, request.getMethod(), request.getRequestURI())) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Invalid service identity or route audience");
            return;
        }
        String expected = GatewaySignatureV2.sign(signingSecret, context);
        if (!GatewaySignatureV2.constantTimeEquals(signature, expected)) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid gateway signature");
            return;
        }

        // Consume only after cryptographic verification so unauthenticated traffic
        // cannot exhaust the replay cache.
        if (!nonceStore.consume(providerId, nonce, Duration.ofMillis(properties.getNonceTtlMs()))) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED, "Replayed gateway nonce");
            return;
        }

        chain.doFilter(cached, response);
    }

    private void verifyV1(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String signature = request.getHeader(SECRET);
        String timestamp = request.getHeader(TIMESTAMP);
        // Preserve v1 query-parameter priority while migration is active.
        String providerId = request.getParameter("providerId");
        if (providerId == null) providerId = request.getHeader(PROVIDER);

        if (blank(signature) || blank(timestamp) || blank(providerId)) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing gateway authentication headers");
            return;
        }

        String userId = request.getHeader("X-User-ID");
        String organizationId = request.getHeader("X-Organization-ID");
        if (!isValidGatewaySecret(signature, providerId, timestamp, userId, organizationId)) {
            rejectRequest(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid gateway secret");
            return;
        }
        chain.doFilter(request, response);
    }

    boolean isPublicEndpoint(String path) {
        return matchesAnyPrefix(path, properties.getPublicPaths());
    }

    boolean isHmacRequiredEndpoint(String path) {
        return matchesAnyPrefix(path, properties.getHmacRequiredPaths());
    }

    private boolean matchesAnyPrefix(String path, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return false;
        for (String prefix : prefixes) {
            if (prefix != null && path.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean isUnsafeSecret(String secret) {
        return secret == null || secret.isBlank()
                || GatewayFilterProperties.DEFAULT_SECRET_KEY.equals(secret);
    }

    boolean isValidGatewaySecret(String receivedSecret, String providerId, String timestamp,
                                 String userId, String organizationId) {
        try {
            if (receivedSecret == null || !receivedSecret.startsWith("gw_")) return false;
            if (!timestampWithinAbsoluteSkew(timestamp, properties.getV1TimestampSkewMs())) return false;
            String expected = generateExpectedSecret(providerId, timestamp, userId, organizationId);
            return MessageDigest.isEqual(
                    receivedSecret.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Gateway v1 validation failed: {}", e.getMessage());
            return false;
        }
    }

    String generateExpectedSecret(String providerId, String timestamp,
                                  String userId, String organizationId) {
        String data = (providerId == null ? "" : providerId) + "|"
                + (userId == null ? "" : userId) + "|"
                + (organizationId == null ? "" : organizationId) + "|" + timestamp;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "gw_" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private boolean timestampWithinAbsoluteSkew(String value, long maximumSkewMs) {
        if (maximumSkewMs < 0) {
            return false;
        }
        try {
            long parsed = Long.parseLong(value);
            long delta = Math.subtractExact(System.currentTimeMillis(), parsed);
            return delta >= -maximumSkewMs && delta <= maximumSkewMs;
        } catch (NumberFormatException | ArithmeticException e) {
            return false;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void rejectRequest(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\""
                + message.replace("\"", "") + "\"}");
    }
}
