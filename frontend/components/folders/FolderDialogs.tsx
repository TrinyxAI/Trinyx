'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { FolderNameDialog } from '@/components/folders/FolderNameDialog';
import { FilesMoveToFolderDialog } from '@/components/files/FilesMoveToFolderDialog';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import type { ListFolders } from '@/hooks/useListFolders';

interface FolderDialogsProps {
  folders: ListFolders;
  /** Ids the "move to..." picker will file when a destination is chosen. */
  selectedIds: ReadonlySet<string>;
}

/**
 * The four dialogs every list with folders needs - create, rename, delete, "move to..." -
 * driven entirely by {@link ListFolders}. Mounting this is all a list has to do; the
 * wording is the shared `folders` namespace, so creating a folder reads the same wherever
 * it happens.
 */
export function FolderDialogs({ folders, selectedIds }: FolderDialogsProps) {
  const t = useTranslations('folders');
  const tCommon = useTranslations('common');

  return (
    <>
      {/* Create - the folder lands where the user is standing. */}
      <FolderNameDialog
        isOpen={folders.showCreateDialog}
        onClose={() => folders.setShowCreateDialog(false)}
        onCreate={folders.createFolder}
      />

      {/* Rename - the same dialog, opened with the current name. */}
      <FolderNameDialog
        isOpen={folders.folderBeingRenamed !== null}
        initialName={folders.folderBeingRenamed?.name ?? ''}
        onClose={() => folders.setFolderBeingRenamed(null)}
        onCreate={folders.renameFolder}
      />

      {/* Deleting a folder is safe and the message says so: what it held goes back to the
          top level, only the filing disappears. */}
      <BulkDeleteModal
        isOpen={folders.folderBeingDeleted !== null}
        title={t('deleteFolder')}
        message={t('deleteFolderConfirm', { name: folders.folderBeingDeleted?.name ?? '' })}
        cancelLabel={tCommon('cancel')}
        confirmLabel={tCommon('delete')}
        onCancel={() => folders.setFolderBeingDeleted(null)}
        onConfirm={folders.confirmDeleteFolder}
      />

      {/* "Move to..." for the current selection - the same picker the Files browser uses. */}
      <FilesMoveToFolderDialog
        isOpen={folders.showMoveDialog}
        allFolders={folders.moveDialogFolders}
        excludeFolderIds={new Set<string>()}
        loading={folders.loadingFolders}
        itemCount={selectedIds.size}
        onClose={() => folders.setShowMoveDialog(false)}
        onMove={async (targetFolderId) => {
          await folders.fileResources(targetFolderId, Array.from(selectedIds));
        }}
      />
    </>
  );
}
