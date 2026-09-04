package com.apimarketplace.common.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Byte-level contract shared by the authenticated gateway and downstream services.
 * Keep this class in parity with non-Java signers through the shared fixtures.
 */
public final class GatewaySignatureV2 {

    public static final String VERSION = "2";
    public static final String PREFIX = "gw_";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private GatewaySignatureV2() {
    }

    public record Context(
            String timestamp,
            String nonce,
            String method,
            String requestTarget,
            String bodySha256,
            String providerId,
            String userId,
            String principalId,
            String billingSubjectId,
            String organizationId,
            String organizationRole,
            String userRoles,
            String installId
    ) {
    }

    public static String canonicalPayload(Context context) {
        return String.join("\n",
                "TRINYX-HMAC-V2",
                value(context.timestamp()),
                value(context.nonce()),
                value(context.method()).toUpperCase(Locale.ROOT),
                value(context.requestTarget()),
                value(context.bodySha256()).toLowerCase(Locale.ROOT),
                value(context.providerId()),
                value(context.userId()),
                value(context.principalId()),
                value(context.billingSubjectId()),
                value(context.organizationId()),
                canonicalRole(context.organizationRole()),
                canonicalRoles(context.userRoles()),
                value(context.installId()));
    }

    public static String sign(String secretKey, Context context) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Gateway HMAC secret is required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] bytes = mac.doFinal(canonicalPayload(context).getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute gateway HMAC v2", e);
        }
    }

    public static boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body == null ? new byte[0] : body);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String canonicalRole(String role) {
        return value(role).trim().toUpperCase(Locale.ROOT);
    }

    public static String canonicalRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return "";
        }
        return Arrays.stream(roles.split(","))
                .map(GatewaySignatureV2::canonicalRole)
                .filter(role -> !role.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    public static boolean rolesAreSafe(String roles) {
        String canonical = canonicalRoles(roles);
        return canonical.isEmpty() || Arrays.stream(canonical.split(","))
                .allMatch(role -> role.matches("[A-Z0-9_:-]{1,64}"));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
