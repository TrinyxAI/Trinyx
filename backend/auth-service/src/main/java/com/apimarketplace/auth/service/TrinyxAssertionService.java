package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.Ed25519Jws;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies and signs persistent v2 assertions with independently rotated key domains. */
@Service
public class TrinyxAssertionService {

    private final Map<String, PublicKey> identityVerificationKeys;
    private final Map<String, PublicKey> entitlementVerificationKeys;
    private final String identitySigningKid;
    private final String entitlementSigningKid;
    private final PrivateKey identitySigningKey;
    private final PrivateKey entitlementSigningKey;

    public TrinyxAssertionService(
            @Value("${trinyx.assertions.identity.verification-keys:}") String identityPublicKeys,
            @Value("${trinyx.assertions.entitlement.verification-keys:}") String entitlementPublicKeys,
            @Value("${trinyx.assertions.identity.signing-kid:}") String identitySigningKid,
            @Value("${trinyx.assertions.identity.signing-key:}") String identitySigningKey,
            @Value("${trinyx.assertions.entitlement.signing-kid:}") String entitlementSigningKid,
            @Value("${trinyx.assertions.entitlement.signing-key:}") String entitlementSigningKey) {
        this.identityVerificationKeys = parsePublicKeys(identityPublicKeys);
        this.entitlementVerificationKeys = parsePublicKeys(entitlementPublicKeys);
        this.identitySigningKid = identitySigningKid;
        this.entitlementSigningKid = entitlementSigningKid;
        this.identitySigningKey = parsePrivateKey(identitySigningKey);
        this.entitlementSigningKey = parsePrivateKey(entitlementSigningKey);
    }

    public JsonNode verifyIdentity(String jws, String issuer, String audience) {
        return verify(jws, identityVerificationKeys, issuer, audience);
    }

    public JsonNode verifyEntitlement(String jws, String issuer, String audience) {
        return verify(jws, entitlementVerificationKeys, issuer, audience);
    }

    public String signIdentity(JsonNode claims) {
        requireSigner(identitySigningKid, identitySigningKey, "identity");
        return Ed25519Jws.sign(claims, identitySigningKid, identitySigningKey);
    }

    public String signEntitlement(JsonNode claims) {
        requireSigner(entitlementSigningKid, entitlementSigningKey, "entitlement");
        return Ed25519Jws.sign(claims, entitlementSigningKid, entitlementSigningKey);
    }

    private JsonNode verify(String jws, Map<String, PublicKey> keys, String issuer, String audience) {
        JsonNode claims = Ed25519Jws.verify(jws, keys).claims();
        if (claims.path("schemaVersion").asInt() != 2
                || !issuer.equals(claims.path("iss").asText())
                || !audience.equals(claims.path("aud").asText())) {
            throw new IllegalArgumentException("Assertion issuer, audience or schema is invalid");
        }
        Instant now = Instant.now();
        Instant nbf = Instant.ofEpochSecond(claims.path("nbf").asLong());
        Instant exp = Instant.ofEpochSecond(claims.path("exp").asLong());
        if (now.isBefore(nbf) || !now.isBefore(exp)) {
            throw new IllegalArgumentException("Assertion is not currently valid");
        }
        if (claims.path("jti").asText().isBlank()) {
            throw new IllegalArgumentException("Assertion jti is required");
        }
        return claims;
    }

    private static Map<String, PublicKey> parsePublicKeys(String encoded) {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return keys;
        for (String entry : encoded.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("Invalid verification key ring");
            String kid = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1).trim();
            keys.put(kid, Ed25519Jws.publicKeyFromX509Base64(value));
        }
        return Map.copyOf(keys);
    }

    private static PrivateKey parsePrivateKey(String encoded) {
        return encoded == null || encoded.isBlank()
                ? null : Ed25519Jws.privateKeyFromPkcs8Base64(encoded);
    }

    private static void requireSigner(String kid, PrivateKey key, String domain) {
        if (kid == null || kid.isBlank() || key == null) {
            throw new IllegalStateException("No active " + domain + " assertion signer configured");
        }
    }
}
