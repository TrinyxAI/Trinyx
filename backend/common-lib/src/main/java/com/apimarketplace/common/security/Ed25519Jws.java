package com.apimarketplace.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Minimal RFC 8037 compact JWS codec for persistent Trinyx assertions.
 *
 * <p>Private keys are supplied through external configuration; this class never reads files or
 * embeds key material. The protected header is deliberately constrained to EdDSA + kid.
 */
public final class Ed25519Jws {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final Pattern SAFE_KID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private Ed25519Jws() {}

    public static String sign(JsonNode claims, String kid, PrivateKey key) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(key, "key");
        if (kid == null || !SAFE_KID.matcher(kid).matches()) {
            throw new IllegalArgumentException("Invalid signing key id");
        }
        try {
            String header = "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}";
            String protectedPart = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
            String payloadPart = B64.encodeToString(JSON.writeValueAsBytes(claims));
            String signingInput = protectedPart + "." + payloadPart;
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64.encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign Ed25519 assertion", e);
        }
    }

    public static Verified verify(String compactJws, Map<String, PublicKey> verificationKeys) {
        if (compactJws == null || compactJws.length() > 131_072) {
            throw new IllegalArgumentException("Invalid compact JWS");
        }
        try {
            String[] parts = compactJws.split("\\.", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Compact JWS must have three parts");
            }
            JsonNode header = JSON.readTree(B64D.decode(parts[0]));
            if (!"EdDSA".equals(header.path("alg").asText())
                    || !"JWT".equals(header.path("typ").asText())) {
                throw new IllegalArgumentException("Unsupported JWS header");
            }
            String kid = header.path("kid").asText();
            PublicKey key = verificationKeys.get(kid);
            if (key == null) {
                throw new IllegalArgumentException("Unknown JWS key id");
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(B64D.decode(parts[2]))) {
                throw new IllegalArgumentException("Invalid JWS signature");
            }
            return new Verified(kid, JSON.readTree(B64D.decode(parts[1])));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid compact JWS", e);
        }
    }

    public static PrivateKey privateKeyFromPkcs8Base64(String encoded) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(stripPem(encoded))));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ed25519 private key", e);
        }
    }

    public static PublicKey publicKeyFromX509Base64(String encoded) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(stripPem(encoded))));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", e);
        }
    }

    private static String stripPem(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("Missing key");
        return encoded.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }

    public record Verified(String kid, JsonNode claims) {}
}
