package com.apimarketplace.interfaces.repository;

import com.apimarketplace.interfaces.domain.InterfaceFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Folders of the interface list. The whole workspace tree is read at once (tens of rows,
 * and every caller needs more than one level of it), then walked in memory by
 * {@code ResourceFolderCoreService}.
 */
@Repository
public interface InterfaceFolderRepository extends JpaRepository<InterfaceFolderEntity, UUID> {

    /** Org workspace: every folder tagged with that organization. */
    List<InterfaceFolderEntity> findByOrganizationId(String organizationId);

    /** Personal workspace: the caller's own untagged folders (strict scope, see ScopeGuard). */
    List<InterfaceFolderEntity> findByOwnerIdAndOrganizationIdIsNull(String ownerId);
}
