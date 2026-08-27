package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.WorkflowFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Folders of the workflow list. The whole workspace tree is read at once (it is tens of
 * rows and every caller needs more than one level of it), then walked in memory by
 * {@code ResourceFolderCoreService}.
 */
@Repository
public interface WorkflowFolderRepository extends JpaRepository<WorkflowFolderEntity, UUID> {

    /** Org workspace: every folder tagged with that organization. */
    List<WorkflowFolderEntity> findByOrganizationId(String organizationId);

    /** Personal workspace: the caller's own untagged folders (strict scope, see ScopeGuard). */
    List<WorkflowFolderEntity> findByOwnerIdAndOrganizationIdIsNull(String ownerId);
}
