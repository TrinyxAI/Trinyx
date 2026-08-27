package com.apimarketplace.publication.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One application filed in one folder, for one workspace ({@code
 * publication.application_folder_items}, V452).
 *
 * <p>The filing is keyed by (publication, workspace) rather than living on the publication:
 * the publisher and every acquirer share that row, so "where I keep this app" is the
 * acquirer's business, not the publication's. {@code organizationId} is {@code ""} (never
 * null) for a personal workspace so the composite key stays well-defined - the same
 * convention as {@code user_publication_favorites}.
 */
@Entity
@Table(name = "application_folder_items")
@IdClass(ApplicationFolderItemEntity.PK.class)
public class ApplicationFolderItemEntity {

    @Id
    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;

    @Id
    @Column(name = "organization_id", nullable = false, length = 255)
    private String organizationId;

    @Column(name = "folder_id", nullable = false)
    private UUID folderId;

    @Column(name = "owner_id", nullable = false, length = 255)
    private String ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ApplicationFolderItemEntity() {
    }

    public ApplicationFolderItemEntity(UUID publicationId, String organizationId, UUID folderId, String ownerId) {
        this.publicationId = publicationId;
        this.organizationId = organizationId;
        this.folderId = folderId;
        this.ownerId = ownerId;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getPublicationId() { return publicationId; }
    public void setPublicationId(UUID publicationId) { this.publicationId = publicationId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getFolderId() { return folderId; }
    public void setFolderId(UUID folderId) { this.folderId = folderId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class PK implements Serializable {
        private UUID publicationId;
        private String organizationId;

        public PK() {}
        public PK(UUID publicationId, String organizationId) {
            this.publicationId = publicationId;
            this.organizationId = organizationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(publicationId, pk.publicationId)
                    && Objects.equals(organizationId, pk.organizationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(publicationId, organizationId);
        }
    }
}
