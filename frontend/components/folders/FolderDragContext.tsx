'use client';

import React from 'react';
import { DndContext, DragOverlay, pointerWithin } from '@dnd-kit/core';
import { useTranslations } from 'next-intl';
import type { ListFolders } from '@/hooks/useListFolders';

interface FolderDragContextProps {
  folders: ListFolders;
  /** The dragged resource's display name, resolved by the list that owns the rows. */
  nameOf: (id: string) => string | undefined;
  children: React.ReactNode;
}

/**
 * The drag surface of a list that has folders: filing a card into a folder tile, taking one
 * back out by dropping it on a crumb, and nesting one folder inside another.
 *
 * <p>It wraps the WHOLE list, header included, on purpose. A drop target only exists inside the
 * context that owns the drag, and the folder path is part of the header: with the context
 * around the cards alone, every crumb was a dead target and dragging a card out of a folder -
 * the one move a folder path is there to offer - did nothing at all.
 */
export function FolderDragContext({ folders, nameOf, children }: FolderDragContextProps) {
  const t = useTranslations('folders');

  return (
    <DndContext
      sensors={folders.sensors}
      // The POINTER decides, not the floating preview. dnd-kit's default measures the preview's
      // rectangle against the targets, and the preview is anchored where the card was picked up
      // plus the travel: grab a card near its bottom edge and the preview floats well above the
      // cursor, so the tile that lights up - and the tile a release files into - is not the one
      // being pointed at. Asking the pointer also means releasing over a gap files nothing,
      // instead of resolving to whatever rectangle the offset preview happened to overlap.
      collisionDetection={pointerWithin}
      onDragStart={(event) => folders.handleDragStart(event, nameOf)}
      onDragEnd={folders.handleDragEnd}
      onDragCancel={folders.cancelDrag}
    >
      {children}

      {/* What is being dragged, following the pointer. A multi-selection drag says how many
          cards are travelling, so a drop never moves more than you meant. Tilted and shadowed
          so it reads as picked up rather than as a label stuck to the cursor, and transparent
          to the pointer so nothing on the page can be hovered THROUGH it by accident (which
          target a drop resolves to is decided from coordinates, so this is not what makes the
          drop land).
          No drop animation: the preview is a label, not the card itself, so flying it back to
          where the card sits would point at the wrong thing on a drop that was refused. */}
      <DragOverlay dropAnimation={null}>
        {folders.activeDrag && (
          <div className="pointer-events-none rotate-2 rounded-xl border border-[var(--accent-primary)] bg-theme-secondary px-3 py-2 text-sm text-theme-primary shadow-2xl ring-2 ring-[var(--accent-primary)]">
            <span className="block max-w-[220px] truncate">
              {folders.activeDrag.count > 1
                ? t('draggingCount', { count: folders.activeDrag.count })
                : folders.activeDrag.label}
            </span>
          </div>
        )}
      </DragOverlay>
    </DndContext>
  );
}
