package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.Ed25519Jws;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Dedicated Cloud workload JWT authentication. It never accepts browser or gateway credentials. */
@Service
public class WorkloadAuthenticationService {

    private final Map<String, PublicKey> verificationKeys;
    private final StringRedisTemplate redis;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final String verificationIssuer;
    private final String verificationAudience;
    private final String signingIssuer;
    private final String signingAudience;
    private final String signingKid;
    private final PrivateKey signingKey;

    public WorkloadAuthenticationService(
            StringRedisTemplate redis,
            com.fasterxml.jackson.databind.ObjectMapper json,
            @Value("${trinyx.s2s.verification-keys:}") String encodedKeys,
            @Value("${trinyx.s2s.verification-issuer:trinyx-cloud}") String verificationIssuer,
            @Value("${trinyx.s2s.verification-audience:trinyx-billing-authority}") String verificationAudience,
            @Value("${trinyx.s2s.signing-issuer:trinyx-cloud}") String signingIssuer,
            @Value("${trinyx.s2s.signing-audience:trinyx-billing-authority}") String signingAudience,
            @Value("${trinyx.s2s.signing-kid:}") String signingKid,
            @Value("${trinyx.s2s.signing-key:}") String encodedSigningKey) {
        this.redis = redis;
        this.json = json;
        this.verificationKeys = parseKeys(encodedKeys);
        this.verificationIssuer = verificationIssuer;
        this.verificationAudience = verificationAudience;
        this.signingIssuer = signingIssuer;
        this.signingAudience = signingAudience;
        this.signingKid = signingKid;
        this.signingKey = encodedSigningKey == null || encodedSigningKey.isBlank()
                ? null : Ed25519Jws.privateKeyFromPkcs8Base64(encodedSigningKey);
    }

    public String issue(String serviceId) {
        if (serviceId == null || !serviceId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid workload service identity");
        }
        if (signingKid == null || signingKid.isBlank() || signingKey == null) {
            throw new IllegalStateException("No active workload signing key configured");
        }
        Instant now = Instant.now();
        var claims = json.createObjectNode();
        claims.put("iss", signingIssuer);
        claims.put("aud", signingAudience);
        claims.put("serviceId", serviceId);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.minusSeconds(2).getEpochSecond());
        claims.put("exp", now.plusSeconds(60).getEpochSecond());
        return Ed25519Jws.sign(claims, signingKid, signingKey);
    }

    public WorkloadIdentity authenticate(String authorization) {
        return authenticate(authorization, verificationIssuer, verificationAudience);
    }

    public WorkloadIdentity authenticate(
            String authorization, String expectedIssuer, String expectedAudience) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new WorkloadAuthenticationException("Missing workload bearer token");
        }

        final JsonNode claims;
        try {
            claims = Ed25519Jws.verify(
                    authorization.substring(7), verificationKeys).claims();
        } catch (IllegalArgumentException | SecurityException invalidCredential) {
            throw new WorkloadAuthenticationException(
                    "Invalid workload bearer token", invalidCredential);
        }

        Instant now = Instant.now();
        Instant issued = Instant.ofEpochSecond(claims.path("iat").asLong());
        Instant notBefore = Instant.ofEpochSecond(claims.path("nbf").asLong());
        Instant expires = Instant.ofEpochSecond(claims.path("exp").asLong());
        if (!expectedIssuer.equals(claims.path("iss").asText())
                || !expectedAudience.equals(claims.path("aud").asText())
                || now.isBefore(issued.minusSeconds(5)) || now.isBefore(notBefore)
                || !now.isBefore(expires)
                || Duration.between(issued, expires).compareTo(Duration.ofMinutes(2)) > 0) {
            throw new WorkloadAuthenticationException("Invalid workload token claims");
        }
        String serviceId = claims.path("serviceId").asText();
        final UUID jti;
        try {
            jti = UUID.fromString(claims.path("jti").asText());
        } catch (IllegalArgumentException invalidJti) {
            throw new WorkloadAuthenticationException(
                    "Invalid workload token identifier", invalidJti);
        }
        if (serviceId.isBlank()) {
            throw new WorkloadAuthenticationException(
                    "Missing workload service identity");
        }
        Boolean consumed = redis.opsForValue().setIfAbsent(
                "trinyx:s2s:jti:" + jti, serviceId, Duration.ofMinutes(3));
        if (!Boolean.TRUE.equals(consumed)) {
            throw new WorkloadAuthenticationException("Replayed workload token");
        }
        return new WorkloadIdentity(serviceId, jti, expires);
    }

    private static Map<String, PublicKey> parseKeys(String encoded) {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return keys;
        for (String entry : encoded.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("Invalid workload verification key ring");
            keys.put(entry.substring(0, separator).trim(),
                    Ed25519Jws.publicKeyFromX509Base64(entry.substring(separator + 1).trim()));
        }
        return Map.copyOf(keys);
    }

    public static final class WorkloadAuthenticationException extends SecurityException {
        public WorkloadAuthenticationException(String message) {
            super(message);
        }

        public WorkloadAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record WorkloadIdentity(String serviceId, UUID jti, Instant expiresAt) {}
}
