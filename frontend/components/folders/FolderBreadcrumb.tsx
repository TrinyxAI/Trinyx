'use client';

import React from 'react';
import { ChevronRight, ArrowLeft, Folder } from 'lucide-react';
import { useDroppable } from '@dnd-kit/core';
import type { ResourceFolder } from '@/lib/api/orchestrator/resource-folder.service';

interface FolderBreadcrumbProps {
  /** Root -> ... -> current folder. Empty at the top level, where nothing is shown. */
  trail: ResourceFolder[];
  /** Label of the top level ("All workflows"), which is a crumb like any other. */
  rootLabel: string;
  /** Navigate to a level; null = back to the top. */
  onNavigate: (folderId: string | null) => void;
  /** Label for the up-one-level button. */
  backLabel: string;
  /** What this level holds, e.g. "6 workflows". Shown under the path. */
  subtitle?: string;
  /** Crumbs accept dropped cards, so dragging one out of a folder files it a level up. */
  droppable?: boolean;
}

/**
 * The header of a list while the user is inside a folder: an up-one-level arrow, the folder
 * mark, the path itself as the page title, and what this level holds underneath. It is the
 * same shape as the Files browser's header, deliberately, so one folder path is learned
 * once and read the same way everywhere.
 *
 * <p>Each crumb is also a drop target: dragging a card onto a parent crumb takes it back out
 * of the folder, which is the move a file browser makes you expect.
 */
export function FolderBreadcrumb({
  trail,
  rootLabel,
  onNavigate,
  backLabel,
  subtitle,
  droppable = true,
}: FolderBreadcrumbProps) {
  if (trail.length === 0) return null;

  const parentId = trail.length > 1 ? trail[trail.length - 2].id : null;

  return (
    <div className="flex items-center gap-2 min-w-0">
      <button
        type="button"
        aria-label={backLabel}
        title={backLabel}
        className="p-1.5 rounded-lg hover:bg-theme-secondary text-theme-secondary flex-shrink-0"
        onClick={() => onNavigate(parentId)}
      >
        <ArrowLeft className="h-4 w-4" />
      </button>

      <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center flex-shrink-0">
        <Folder className="w-5 h-5 text-theme-primary" />
      </div>

      <div className="min-w-0">
        <h1 className="flex items-center gap-1 text-lg font-semibold text-theme-primary min-w-0">
          <Crumb label={rootLabel} folderId={null} onNavigate={onNavigate} droppable={droppable} isCurrent={false} />
          {trail.map((folder, index) => {
            const isCurrent = index === trail.length - 1;
            return (
              <span key={folder.id} className="flex items-center gap-1 min-w-0">
                <ChevronRight className="h-4 w-4 flex-shrink-0 text-theme-muted" />
                <Crumb
                  label={folder.name}
                  folderId={folder.id}
                  onNavigate={onNavigate}
                  // The folder you are already in is not a place to drop things.
                  droppable={droppable && !isCurrent}
                  isCurrent={isCurrent}
                />
              </span>
            );
          })}
        </h1>
        {subtitle && <p className="truncate text-sm text-theme-secondary">{subtitle}</p>}
      </div>
    </div>
  );
}

function Crumb({
  label,
  folderId,
  onNavigate,
  droppable,
  isCurrent,
}: {
  label: string;
  folderId: string | null;
  onNavigate: (folderId: string | null) => void;
  droppable: boolean;
  isCurrent: boolean;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `crumb:${folderId ?? 'root'}`,
    data: { type: 'folder', folderId },
    disabled: !droppable,
  });

  return (
    <button
      ref={setNodeRef}
      type="button"
      onClick={() => onNavigate(folderId)}
      // The crumb of the level being shown is the page you are on, not somewhere to go.
      disabled={isCurrent}
      aria-current={isCurrent ? 'page' : undefined}
      className={`truncate rounded-md px-1 transition-colors ${
        isOver ? 'bg-[var(--accent-primary)]/10 text-[var(--accent-primary)]' : ''
      } ${isCurrent ? 'text-theme-primary' : 'text-theme-secondary hover:underline'}`}
    >
      {label}
    </button>
  );
}
