package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The CE release advertised by the public feed {@code /api/ce/releases/latest}.
 *
 * <p>Single row, pinned to {@link #SINGLETON_ID} by a DB CHECK, so readers never have to pick a
 * winner. Written by the CE release workflow through the internal announce endpoint rather than by
 * a cloud deploy: announcing a release and deploying the cloud are independent cycles, and coupling
 * them through deploy config is what let the advertised version drift silently for months.
 *
 * <p>Present in CE too (the schema is shared), where the table stays empty and the feed answers
 * "no release" exactly as before.
 */
@Entity
@Table(name = "ce_release", schema = "auth")
public class CeRelease {

    /** The only id the table accepts; enforced by a CHECK constraint. */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Short id = SINGLETON_ID;

    @Column(name = "latest_version")
    private String latestVersion;

    @Column(name = "release_url")
    private String releaseUrl;

    @Column(name = "security_fix", nullable = false)
    private boolean securityFix;

    @Column(name = "published_at")
    private String publishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CeRelease() {
        // for JPA
    }

    public CeRelease(String latestVersion, String releaseUrl, boolean securityFix, String publishedAt) {
        this.id = SINGLETON_ID;
        this.latestVersion = latestVersion;
        this.releaseUrl = releaseUrl;
        this.securityFix = securityFix;
        this.publishedAt = publishedAt;
        this.updatedAt = Instant.now();
    }

    public Short getId() {
        return id;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getReleaseUrl() {
        return releaseUrl;
    }

    public boolean isSecurityFix() {
        return securityFix;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Replaces the advertised release in place; the row identity never changes. */
    public void apply(String newVersion, String newUrl, boolean newSecurityFix, String newPublishedAt) {
        this.latestVersion = newVersion;
        this.releaseUrl = newUrl;
        this.securityFix = newSecurityFix;
        this.publishedAt = newPublishedAt;
        this.updatedAt = Instant.now();
    }
}
