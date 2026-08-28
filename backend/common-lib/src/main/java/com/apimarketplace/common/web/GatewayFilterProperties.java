package com.apimarketplace.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "gateway.filter")
public class GatewayFilterProperties {

    public static final String DEFAULT_SECRET_KEY = "";

    private List<String> publicPaths = new ArrayList<>();
    private List<String> hmacRequiredPaths = new ArrayList<>();
    /** Routes that require a cryptographically distinct service identity. */
    private List<String> serviceAuthenticatedPaths = new ArrayList<>();
    /** Per-service HMAC keys. A caller cannot claim another serviceId without its key. */
    private Map<String, String> serviceSecrets = new LinkedHashMap<>();
    /** Exact METHOD:path or METHOD:/prefix/** permissions by serviceId. */
    private Map<String, List<String>> serviceRoutePermissions = new LinkedHashMap<>();
    private boolean verificationEnabled = true;
    private String secretKey = DEFAULT_SECRET_KEY;
    private boolean rejectDefaultSecrets = false;

    /** Temporary migration switch. Cloud production must set this false. */
    private boolean acceptV1 = true;

    /** Absolute clock skew for v2; future timestamps are rejected too. */
    private long v2TimestampSkewMs = 60_000;

    /** Legacy window retained only while v1 clients migrate. */
    private long v1TimestampSkewMs = 300_000;

    /** One-time nonce lifetime. */
    private long nonceTtlMs = 300_000;

    /** Maximum body buffered for exact v2 digest verification. */
    private int maxBodyBytes = 50 * 1024 * 1024;

    /** Fail startup rather than silently using per-process replay protection. */
    private boolean requireDistributedNonceStore = false;

    public List<String> getPublicPaths() { return publicPaths; }
    public void setPublicPaths(List<String> publicPaths) { this.publicPaths = publicPaths; }
    public List<String> getHmacRequiredPaths() { return hmacRequiredPaths; }
    public void setHmacRequiredPaths(List<String> hmacRequiredPaths) { this.hmacRequiredPaths = hmacRequiredPaths; }
    public List<String> getServiceAuthenticatedPaths() { return serviceAuthenticatedPaths; }
    public void setServiceAuthenticatedPaths(List<String> value) { this.serviceAuthenticatedPaths = value; }
    public Map<String, String> getServiceSecrets() { return serviceSecrets; }
    public void setServiceSecrets(Map<String, String> value) {
        this.serviceSecrets = value == null ? new LinkedHashMap<>() : value;
    }
    public Map<String, List<String>> getServiceRoutePermissions() { return serviceRoutePermissions; }
    public void setServiceRoutePermissions(Map<String, List<String>> value) {
        this.serviceRoutePermissions = value == null ? new LinkedHashMap<>() : value;
    }

    public boolean serviceAuthenticationRequired(String path) {
        return matchesPrefix(path, serviceAuthenticatedPaths);
    }

    public String secretFor(String serviceId, String path) {
        if (!serviceAuthenticationRequired(path)) return secretKey;
        String secret = serviceSecrets.get(serviceId);
        return secret == null || secret.isBlank() ? null : secret;
    }

    public boolean serviceMayCall(String serviceId, String method, String path) {
        if (!serviceAuthenticationRequired(path)) return true;
        List<String> permissions = serviceRoutePermissions.get(serviceId);
        if (permissions == null || permissions.isEmpty()) return false;
        String actualMethod = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        for (String permission : permissions) {
            if (permission == null) continue;
            int separator = permission.indexOf(':');
            if (separator <= 0) continue;
            String allowedMethod = permission.substring(0, separator).toUpperCase(java.util.Locale.ROOT);
            String allowedPath = permission.substring(separator + 1);
            if (!"*".equals(allowedMethod) && !allowedMethod.equals(actualMethod)) continue;
            if (pathMatches(allowedPath, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pathMatches(String pattern, String path) {
        if (pattern == null || path == null) return false;
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix);
        }
        String[] allowed = pattern.split("/", -1);
        String[] actual = path.split("/", -1);
        if (allowed.length != actual.length) return false;
        for (int i = 0; i < allowed.length; i++) {
            String segment = allowed[i];
            boolean template = "*".equals(segment)
                    || (segment.startsWith("{") && segment.endsWith("}")
                    && segment.length() > 2);
            if (!template && !segment.equals(actual[i])) return false;
            if (template && actual[i].isEmpty()) return false;
        }
        return true;
    }

    private static boolean matchesPrefix(String path, List<String> prefixes) {
        if (path == null || prefixes == null) return false;
        return prefixes.stream().filter(java.util.Objects::nonNull).anyMatch(path::startsWith);
    }
    public boolean isVerificationEnabled() { return verificationEnabled; }
    public void setVerificationEnabled(boolean verificationEnabled) { this.verificationEnabled = verificationEnabled; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public boolean isRejectDefaultSecrets() { return rejectDefaultSecrets; }
    public void setRejectDefaultSecrets(boolean rejectDefaultSecrets) { this.rejectDefaultSecrets = rejectDefaultSecrets; }
    public boolean isAcceptV1() { return acceptV1; }
    public void setAcceptV1(boolean acceptV1) { this.acceptV1 = acceptV1; }
    public long getV2TimestampSkewMs() { return v2TimestampSkewMs; }
    public void setV2TimestampSkewMs(long value) { this.v2TimestampSkewMs = value; }
    public long getV1TimestampSkewMs() { return v1TimestampSkewMs; }
    public void setV1TimestampSkewMs(long value) { this.v1TimestampSkewMs = value; }
    public long getNonceTtlMs() { return nonceTtlMs; }
    public void setNonceTtlMs(long value) { this.nonceTtlMs = value; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int value) { this.maxBodyBytes = value; }
    public boolean isRequireDistributedNonceStore() { return requireDistributedNonceStore; }
    public void setRequireDistributedNonceStore(boolean value) { this.requireDistributedNonceStore = value; }
}
