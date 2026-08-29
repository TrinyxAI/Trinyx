'use client';

import React from 'react';
import { Folder, Pencil, Trash2 } from 'lucide-react';
import { useDroppable, useDraggable } from '@dnd-kit/core';
import { useTranslations } from 'next-intl';
import { formatRelativeDate } from '@/lib/utils/dateFormatters';
import { DRAG_GUARD_PROPS } from '@/lib/dnd/dragGuards';
import type { ResourceFolderTile as FolderTileData } from '@/lib/api/orchestrator/resource-folder.service';

interface ResourceFolderTileProps {
  folder: FolderTileData;
  /** The folder's face, drawn in the style of whatever the list holds. */
  face: React.ReactNode;
  /** How many resources it holds, already localized for that resource ("3 workflows"). */
  countLabel: string;
  onOpen: (folder: FolderTileData) => void;
  /** Omitted on a read-only surface (a VIEWER in an org workspace). */
  onRename?: (folder: FolderTileData) => void;
  onDelete?: (folder: FolderTileData) => void;
  /** Dropping cards on the tile files them here; off when the caller cannot write. */
  droppable?: boolean;
  /** The tile can itself be dragged into another folder. */
  draggable?: boolean;
}

/**
 * A folder, on a resource list page. The frame is the frame of the cards it sits among -
 * same corner, same border, same footer - so the grid stays one surface; only the face
 * changes with what is inside (see {@code WorkflowFolderFace} and its siblings).
 *
 * <p>It is a drop target (drag cards onto it to file them) and itself draggable (drop it on
 * another folder to nest it). The backend refuses a folder dropped into its own subtree.
 */
export function ResourceFolderTile({
  folder,
  face,
  countLabel,
  onOpen,
  onRename,
  onDelete,
  droppable = true,
  draggable = true,
}: ResourceFolderTileProps) {
  const t = useTranslations('folders');

  const { setNodeRef: setDropRef, isOver } = useDroppable({
    id: `folder:${folder.id}`,
    data: { type: 'folder', folderId: folder.id },
    disabled: !droppable,
  });
  const { setNodeRef: setDragRef, attributes, listeners, isDragging } = useDraggable({
    id: `folder-drag:${folder.id}`,
    // The name travels with the drag so the floating preview can say what is being moved -
    // nothing else knows a folder's name once the tile is the thing under the pointer.
    data: { type: 'folder', folderId: folder.id, name: folder.name },
    disabled: !draggable,
  });

  const setRefs = React.useCallback(
    (node: HTMLElement | null) => {
      setDropRef(node);
      setDragRef(node);
    },
    [setDropRef, setDragRef],
  );

  const subtitle = folder.subfolderCount > 0
    ? `${countLabel} · ${t('subfolderCount', { count: folder.subfolderCount })}`
    : countLabel;

  return (
    <div
      ref={setRefs}
      {...attributes}
      {...listeners}
      role="button"
      tabIndex={0}
      aria-label={folder.name}
      // `touch-manipulation`: see DraggableResourceCard - the hold that starts a drag must not
      // compete with a gesture the browser might still claim.
      className={`group relative touch-manipulation rounded-[18px] border overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 hover:shadow-md transition-shadow cursor-pointer ${
        isOver
          ? 'border-[var(--accent-primary)] ring-2 ring-[var(--accent-primary)]'
          : 'border-theme'
      } ${isDragging ? 'opacity-50' : ''}`}
      onClick={() => onOpen(folder)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen(folder);
        }
      }}
      title={folder.name}
    >
      {face}

      {/* Rename / delete, on hover - the same corner the cards put their actions in. */}
      {(onRename || onDelete) && (
        <div className="absolute top-2 right-2 z-10 flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
          {onRename && (
            <button
              type="button"
              aria-label={t('renameFolder')}
              title={t('renameFolder')}
              className="rounded-md p-1 bg-theme-secondary/80 text-theme-muted hover:text-theme-primary"
              onClick={(e) => {
                e.stopPropagation();
                onRename(folder);
              }}
              {...DRAG_GUARD_PROPS}
            >
              <Pencil className="h-3.5 w-3.5" />
            </button>
          )}
          {onDelete && (
            <button
              type="button"
              aria-label={t('deleteFolder')}
              title={t('deleteFolder')}
              className="rounded-md p-1 bg-theme-secondary/80 text-theme-muted hover:text-red-500"
              onClick={(e) => {
                e.stopPropagation();
                onDelete(folder);
              }}
              {...DRAG_GUARD_PROPS}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      )}

      {/* Footer - same block as the resource cards, with the folder mark leading. */}
      <div className="bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-4 py-3">
        <div className="flex items-center gap-2">
          <Folder className="h-4 w-4 shrink-0 text-[var(--accent-primary)]" />
          <span className="text-sm font-medium text-theme-primary truncate">{folder.name}</span>
        </div>
        <div className="flex items-center gap-1 mt-1 text-xs text-theme-muted">
          <span className="truncate">{subtitle}</span>
          {folder.lastModifiedAt && (
            <>
              <span className="text-slate-300 dark:text-slate-600">·</span>
              <span className="truncate">{formatRelativeDate(folder.lastModifiedAt)}</span>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
