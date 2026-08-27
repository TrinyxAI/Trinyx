'use client';

import React from 'react';
import { Folder } from 'lucide-react';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';

/** Cells of the tile's face: three across, two down. */
const FACE_CELLS = 6;

interface ApplicationFolderFaceProps {
  /** Up to six applications of the folder, most recently updated first. */
  preview: FolderPreviewItem[];
}

/**
 * What a folder of APPLICATIONS looks like: the apps it holds as small tiles, the way an
 * app is a tile on a home screen. An app's card is a live showcase, which is far too heavy
 * to draw six of inside one folder, so each app shows as its initial - enough to recognise
 * the set at a glance, and it costs nothing.
 *
 * <p>An empty folder shows the folder mark instead, rather than a grid of blanks.
 */
export function ApplicationFolderFace({ preview }: ApplicationFolderFaceProps) {
  return (
    <div className="relative h-[120px] overflow-hidden bg-white dark:bg-slate-900">
      {preview.length === 0 ? (
        <div className="flex items-center justify-center w-full h-full">
          <Folder className="h-9 w-9 text-theme-muted" />
        </div>
      ) : (
        <div className="grid grid-cols-3 grid-rows-2 gap-1.5 w-full h-full p-2.5">
          {preview.slice(0, FACE_CELLS).map((item) => (
            <AppTile key={item.id} name={item.name} imageUrl={item.imageUrl} />
          ))}
          {/* Keep the 3x2 rhythm when the folder holds fewer than six. */}
          {Array.from({ length: Math.max(0, FACE_CELLS - Math.min(preview.length, FACE_CELLS)) }).map((_, i) => (
            <div key={`empty-${i}`} className="rounded-md bg-slate-50 dark:bg-slate-800/40" />
          ))}
        </div>
      )}
    </div>
  );
}

/** One application inside the folder: its cover when it has one, its initial otherwise. */
function AppTile({ name, imageUrl }: { name?: string; imageUrl?: string }) {
  const initial = (name ?? '?').trim().charAt(0).toUpperCase() || '?';

  return (
    <div
      className="flex items-center justify-center rounded-md border border-slate-200 dark:border-slate-700 bg-slate-100 dark:bg-slate-800 overflow-hidden"
      title={name}
    >
      {imageUrl ? (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img src={imageUrl} alt="" loading="lazy" className="w-full h-full object-cover" />
      ) : (
        <span className="text-sm font-semibold text-theme-secondary">{initial}</span>
      )}
    </div>
  );
}
