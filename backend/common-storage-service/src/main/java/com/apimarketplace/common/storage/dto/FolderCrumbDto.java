package com.apimarketplace.common.storage.dto;

/**
 * One breadcrumb of the Files browser's folder trail, root-first.
 *
 * <p>The Files page keeps its current folder in the URL, so a refresh or a pasted link has only a
 * navigation key ({@code "wf:<id>/r<run>/e<epoch>"} or a manual folder UUID) and no memory of how
 * the user got there. This DTO rebuilds that path server-side.</p>
 *
 * <p>The field set is deliberately the SUBSET of {@link StorageExplorerDto} the client already
 * labels folders with ({@code virtualId}, {@code virtualKind}, {@code fileName},
 * {@code workflowName}, {@code epoch}, {@code spawn}, {@code itemIndex}), so the browser reuses its
 * one folder-label helper for a crumb and for a folder tile - the two can never drift.</p>
 *
 * @param id          navigation key of this crumb: the manual folder UUID, or the virtual address.
 *                    Feeding it back as {@code parentFolderId} lists exactly this folder.
 * @param virtualId   the same virtual address for a computed folder, null for a manual one. This is
 *                    what marks a crumb "virtual" client-side.
 * @param virtualKind WORKFLOW / RUN / EPOCH / SPAWN / ITERATION, or null for a manual folder.
 * @param fileName    a manual folder's stored name; null for virtual crumbs.
 * @param workflowName resolved workflow name (WORKFLOW crumbs only).
 * @param epoch       the RUN crumb's 1-based run number, or the EPOCH/SPAWN/ITERATION crumb's real
 *                    stored epoch - the exact same overloading {@link StorageExplorerDto} uses, so
 *                    the labels match the folder tiles.
 * @param spawn       0-based spawn index (SPAWN / ITERATION crumbs).
 * @param itemIndex   0-based iteration index (ITERATION crumbs).
 */
public record FolderCrumbDto(
        String id,
        String virtualId,
        String virtualKind,
        String fileName,
        String workflowName,
        Integer epoch,
        Integer spawn,
        Integer itemIndex) {

    /** A manual (persisted) folder crumb. */
    public static FolderCrumbDto manual(String id, String name) {
        return new FolderCrumbDto(id, null, null, name, null, null, null, null);
    }

    /** A computed workflow-tree crumb; {@code workflowName} is resolved by the controller. */
    public static FolderCrumbDto virtual(String address, String kind, Integer epoch, Integer spawn, Integer itemIndex) {
        return new FolderCrumbDto(address, address, kind, null, null, epoch, spawn, itemIndex);
    }

    /** Copy carrying a resolved workflow name (records are immutable). */
    public FolderCrumbDto withWorkflowName(String name) {
        return new FolderCrumbDto(id, virtualId, virtualKind, fileName, name, epoch, spawn, itemIndex);
    }
}
