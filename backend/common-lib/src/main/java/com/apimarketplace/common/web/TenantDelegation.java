package com.apimarketplace.common.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Short-lived tenant-context capability minted only by the authenticated edge.
 *
 * <p>Service HMAC authenticates the calling workload. This envelope separately proves that
 * the tenant tuple came through a browser-authenticated Gateway request, so an allowed
 * workload cannot manufacture a different tenant merely by signing self-chosen headers.
 */
public final class TenantDelegation {

    public static final String HEADER = "X-Trinyx-Tenant-Delegation";
    private static final String PREFIX = "td1";
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(2);

    private TenantDelegation() {
    }

    public static String issue(String secret, String userId, String principalId,
                               String billingSubjectId, String organizationId,
                               String installId, Instant now) {
        requireSecret(secret);
        long issuedAt = now.getEpochSecond();
        long expiresAt = now.plus(MAX_LIFETIME).getEpochSecond();
        String claims = String.join("\n",
                Long.toString(issuedAt),
                Long.toString(expiresAt),
                UUID.randomUUID().toString(),
                "tenant-context",
                safe(userId),
                safe(principalId),
                safe(billingSubjectId),
                safe(organizationId),
                safe(installId));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        return PREFIX + "." + payload + "." + sign(secret, payload);
    }

    public static boolean verify(String token, String secret, String userId, String principalId,
                                 String billingSubjectId, String organizationId,
                                 String installId, Instant now) {
        try {
            requireSecret(secret);
            if (token == null || token.isBlank()) return false;
            String[] parts = token.split("[.]", -1);
            if (parts.length != 3 || !PREFIX.equals(parts[0])) return false;
            String expected = sign(secret, parts[1]);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    parts[2].getBytes(StandardCharsets.US_ASCII))) {
                return false;
            }
            String decoded = new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8);
            String[] claims = decoded.split("\n", -1);
            if (claims.length != 9) return false;
            long issuedAt = Long.parseLong(claims[0]);
            long expiresAt = Long.parseLong(claims[1]);
            long nowEpoch = now.getEpochSecond();
            if (issuedAt > nowEpoch + 5 || expiresAt <= nowEpoch
                    || expiresAt - issuedAt <= 0
                    || expiresAt - issuedAt > MAX_LIFETIME.toSeconds()) {
                return false;
            }
            UUID.fromString(claims[2]);
            return constant(claims[3], "tenant-context")
                    && constant(claims[4], userId)
                    && constant(claims[5], principalId)
                    && constant(claims[6], billingSubjectId)
                    && constant(claims[7], organizationId)
                    && constant(claims[8], installId);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static void requireSecret(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("Tenant delegation key must be at least 32 characters");
        }
        String normalized = secret.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("replace-with") || normalized.startsWith("ci-")
                || normalized.contains("changeme")) {
            throw new IllegalStateException("Tenant delegation key must not be a placeholder");
        }
    }

    private static boolean constant(String actual, String expected) {
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                safe(expected).getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception failure) {
            throw new IllegalStateException("HmacSHA256 unavailable", failure);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
