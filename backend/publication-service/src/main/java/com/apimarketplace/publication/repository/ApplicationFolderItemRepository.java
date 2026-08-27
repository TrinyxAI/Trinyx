package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.ApplicationFolderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Where each application is filed, per workspace. Read once per list request (the page
 * holds its whole set, so the folder view is computed from it rather than re-queried).
 */
@Repository
public interface ApplicationFolderItemRepository
        extends JpaRepository<ApplicationFolderItemEntity, ApplicationFolderItemEntity.PK> {

    /** Every filing of one workspace. */
    List<ApplicationFolderItemEntity> findByOrganizationId(String organizationId);

    /** Remove the filing of these applications in this workspace (= back to the top level). */
    @Modifying
    @Query("DELETE FROM ApplicationFolderItemEntity i "
            + "WHERE i.organizationId = :organizationId AND i.publicationId IN :publicationIds")
    int deleteByScopeAndPublicationIds(@Param("organizationId") String organizationId,
                                       @Param("publicationIds") Collection<UUID> publicationIds);

    /**
     * Empty the given folders: what they held goes back to the top level. Called when the
     * folders are deleted - a folder never removes an application from the workspace.
     */
    @Modifying
    @Query("DELETE FROM ApplicationFolderItemEntity i "
            + "WHERE i.organizationId = :organizationId AND i.folderId IN :folderIds")
    int deleteByScopeAndFolderIds(@Param("organizationId") String organizationId,
                                  @Param("folderIds") Collection<UUID> folderIds);
}
