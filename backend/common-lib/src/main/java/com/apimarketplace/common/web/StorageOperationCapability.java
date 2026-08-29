package com.apimarketplace.common.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Short-lived, resource-scoped authority for destructive storage jobs.
 *
 * <p>Unlike {@link TenantDelegation}, this token is not a browser request
 * delegation. A trusted authority regenerates it from durable job state for
 * every delivery attempt. The storage service accepts it only on the matching
 * dedicated operation and exact resource tuple.
 */
public final class StorageOperationCapability {

    public static final String HEADER = "X-Trinyx-Storage-Capability";
    private static final String PREFIX = "sc1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final long MAX_LIFETIME_SECONDS = 300;

    private StorageOperationCapability() {
    }

    public static String issueWorkspaceErasure(
            String secret, UUID eventId, String organizationId,
            String tenantId, String storageKey, Instant now) {
        requireSecret(secret);
        require(eventId == null ? null : eventId.toString(), "eventId");
        require(organizationId, "organizationId");
        require(tenantId, "tenantId");
        require(storageKey, "storageKey");
        Instant issuedAt = now == null ? Instant.now() : now;
        long issued = issuedAt.getEpochSecond();
        long expires = issued + MAX_LIFETIME_SECONDS;
        String payload = join(
                "workspace-erasure", eventId.toString(), organizationId,
                tenantId, storageKey, Long.toString(issued),
                Long.toString(expires), UUID.randomUUID().toString());
        return PREFIX + "." + encode(payload.getBytes(StandardCharsets.UTF_8))
                + "." + encode(hmac(secret, payload));
    }

    public static boolean verifyWorkspaceErasure(
            String token, String secret, UUID eventId, String organizationId,
            String tenantId, String storageKey, Instant now) {
        try {
            requireSecret(secret);
            if (token == null || token.isBlank()) {
                return false;
            }
            String[] tokenParts = token.split("\\.", -1);
            if (tokenParts.length != 3 || !PREFIX.equals(tokenParts[0])) {
                return false;
            }
            String payload = new String(
                    Base64.getUrlDecoder().decode(tokenParts[1]),
                    StandardCharsets.UTF_8);
            byte[] expected = hmac(secret, payload);
            byte[] actual = Base64.getUrlDecoder().decode(tokenParts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return false;
            }
            String[] claims = payload.split("\\|", -1);
            if (claims.length != 9
                    || !constantEquals("workspace-erasure", claims[0])
                    || !constantEquals(eventId == null ? "" : eventId.toString(), claims[1])
                    || !constantEquals(value(organizationId), claims[2])
                    || !constantEquals(value(tenantId), claims[3])
                    || !constantEquals(value(storageKey), claims[4])) {
                return false;
            }
            long issued = Long.parseLong(claims[5]);
            long expires = Long.parseLong(claims[6]);
            long epoch = (now == null ? Instant.now() : now).getEpochSecond();
            return expires >= issued
                    && expires - issued <= MAX_LIFETIME_SECONDS
                    && issued <= epoch + 30
                    && epoch <= expires
                    && UUID.fromString(claims[7]) != null
                    && claims[8].isEmpty();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static void requireSecret(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "Storage operation capability secret must contain at least 32 characters");
        }
        String lower = secret.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("replace-with")
                || lower.startsWith("ci-")
                || lower.contains("changeme")) {
            throw new IllegalStateException(
                    "Storage operation capability secret must not be a public placeholder");
        }
    }

    private static byte[] hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    private static String join(String... values) {
        for (String value : values) {
            if (value != null && value.contains("|")) {
                throw new IllegalArgumentException(
                        "Storage capability claim contains an invalid separator");
            }
        }
        return String.join("|", values) + "|";
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                value(expected).getBytes(StandardCharsets.UTF_8),
                value(actual).getBytes(StandardCharsets.UTF_8));
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
