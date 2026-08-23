package com.apimarketplace.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "gateway.filter")
public class GatewayFilterProperties {

    public static final String DEFAULT_SECRET_KEY = "";

    private List<String> publicPaths = new ArrayList<>();
    private List<String> hmacRequiredPaths = new ArrayList<>();
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
    private int maxBodyBytes = 10 * 1024 * 1024;

    /** Fail startup rather than silently using per-process replay protection. */
    private boolean requireDistributedNonceStore = false;

    public List<String> getPublicPaths() { return publicPaths; }
    public void setPublicPaths(List<String> publicPaths) { this.publicPaths = publicPaths; }
    public List<String> getHmacRequiredPaths() { return hmacRequiredPaths; }
    public void setHmacRequiredPaths(List<String> hmacRequiredPaths) { this.hmacRequiredPaths = hmacRequiredPaths; }
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
