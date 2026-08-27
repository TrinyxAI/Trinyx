package com.apimarketplace.common.folder;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared mapping for the folder tables that organise a resource LIST page
 * (workflows, agents, tables, interfaces, applications). Each service owns its own
 * concrete table - {@code orchestrator.workflow_folders}, {@code agent.agent_folders},
 * ... - because the list endpoint that has to filter on the folder is served by that
 * service and may only query its own schema. This superclass is what keeps the five
 * tables one single shape.
 *
 * <p>A folder is nothing but a name and a parent: the membership lives on the resource
 * row itself (a {@code folder_id} column), so filing a resource never rewrites the
 * resource, and deleting a folder can re-file its content at the top level without ever
 * touching the resource's own lifecycle.
 *
 * <p>Scope: {@code ownerId} is the creator, {@code organizationId} the active workspace
 * (auto-stamped by {@link OrgScopedEntityListener} when a caller forgets). Reads go
 * through {@code ScopeGuard.isInStrictScope} exactly like every other user-scoped row,
 * so a folder is visible to the workspace, not just to its creator.
 *
 * <p>Concrete subclasses add nothing but {@code @Entity} + {@code @Table(name = "...")}.
 */
@MappedSuperclass
@EntityListeners(OrgScopedEntityListener.class)
public abstract class AbstractResourceFolderEntity implements OrgScopedEntity {

    /** Longest accepted folder name. The columns are VARCHAR(120). */
    public static final int MAX_NAME_LENGTH = 120;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    /** Parent folder, or {@code null} for a top-level folder. No FK: the cascade is app-managed. */
    @Column(name = "parent_folder_id")
    private UUID parentFolderId;

    @Column(name = "owner_id", nullable = false, length = 255)
    private String ownerId;

    @Column(name = "organization_id", length = 255)
    private String organizationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getParentFolderId() { return parentFolderId; }
    public void setParentFolderId(UUID parentFolderId) { this.parentFolderId = parentFolderId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    @Override
    public String getOrganizationId() { return organizationId; }

    @Override
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
