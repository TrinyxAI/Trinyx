package com.apimarketplace.common.folder;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A folder as a list page renders it: the folder itself plus everything the tile needs
 * to look like the resources it holds and to take its place in the page's ordering.
 *
 * <p>The aggregates are computed over the folder's WHOLE subtree, not just its direct
 * children: a folder whose items all sit one level down would otherwise read as empty
 * and sort last, which is exactly backwards.
 *
 * @param itemCount      resources in this folder and every folder below it
 * @param subfolderCount direct subfolders (the tile shows them as a secondary count)
 * @param lastModifiedAt newest {@code updatedAt} in the subtree, or null when empty
 * @param lastActivityAt newest "last used" moment in the subtree (a workflow's last run,
 *                       an agent's last execution), or null when nothing ever ran
 * @param activityCount  how much the subtree has been used (total workflow runs), or null
 *                       for resource types that have no such notion
 * @param preview        the first few items, newest first, for the tile's mosaic
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceFolderDto(
        UUID id,
        String name,
        UUID parentFolderId,
        long itemCount,
        int subfolderCount,
        Instant lastModifiedAt,
        Instant lastActivityAt,
        Long activityCount,
        List<FolderPreviewItem> preview,
        Instant createdAt,
        Instant updatedAt) {

    /** Folder row with no aggregate yet - used by the "move to..." picker and the breadcrumb. */
    public static ResourceFolderDto bare(AbstractResourceFolderEntity folder) {
        return new ResourceFolderDto(
                folder.getId(),
                folder.getName(),
                folder.getParentFolderId(),
                0L,
                0,
                null,
                null,
                null,
                List.of(),
                folder.getCreatedAt(),
                folder.getUpdatedAt());
    }
}
