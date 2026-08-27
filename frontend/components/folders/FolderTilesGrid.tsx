'use client';

import React from 'react';
import { ResourceFolderTile } from '@/components/folders/ResourceFolderTile';
import type { ResourceFolderTile as FolderTileData } from '@/lib/api/orchestrator/resource-folder.service';
import type { ListFolders } from '@/hooks/useListFolders';

interface FolderTilesGridProps {
  folders: ListFolders;
  /** The face for this list's folders - what a folder of THESE resources looks like. */
  renderFace: (folder: FolderTileData) => React.ReactNode;
  /** "3 workflows" / "3 agents" - each list names what it holds. */
  countLabel: (count: number) => string;
  /** Tailwind grid classes, so the tiles line up with the cards below them. */
  gridClassName?: string;
}

/**
 * The row of folder tiles above a list's cards. Rendered inside the list's own DndContext,
 * so a card dropped on a tile is filed there and a tile dropped on another is nested.
 * Renders nothing when the level has no folders.
 */
export function FolderTilesGrid({
  folders,
  renderFace,
  countLabel,
  gridClassName = 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 mb-4',
}: FolderTilesGridProps) {
  if (folders.tiles.length === 0) return null;

  return (
    <div className={gridClassName}>
      {folders.tiles.map((folder) => (
        <ResourceFolderTile
          key={folder.id}
          folder={folder}
          face={renderFace(folder)}
          countLabel={countLabel(folder.itemCount)}
          onOpen={folders.openFolder}
          onRename={folders.canOrganize ? folders.setFolderBeingRenamed : undefined}
          onDelete={folders.canOrganize ? folders.setFolderBeingDeleted : undefined}
          droppable={folders.canOrganize}
          draggable={folders.canOrganize}
        />
      ))}
    </div>
  );
}
