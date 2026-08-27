package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.ApplicationFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Folders of the applications list. The whole workspace tree is read at once. */
@Repository
public interface ApplicationFolderRepository extends JpaRepository<ApplicationFolderEntity, UUID> {

    List<ApplicationFolderEntity> findByOrganizationId(String organizationId);

    List<ApplicationFolderEntity> findByOwnerIdAndOrganizationIdIsNull(String ownerId);
}
