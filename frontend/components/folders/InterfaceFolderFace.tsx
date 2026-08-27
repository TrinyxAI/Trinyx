'use client';

import React from 'react';
import { Folder } from 'lucide-react';
import { resolveInterfaceFormatOrDefault } from '@/lib/interfaces/interfaceFormats';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';

/** Cells of the tile's face: three across, two down. Mirrors PREVIEW_SIZE server-side. */
const FACE_CELLS = 6;

interface InterfaceFolderFaceProps {
  /** Up to six pages of the folder, newest first (the backend already trimmed them). */
  preview: FolderPreviewItem[];
}

/**
 * What a folder of PAGES looks like: one silhouette per page, each in the page's OWN shape
 * and carrying its name. The shape tells you what kind of pages the folder holds (a folder
 * of phone-sized pages does not look like a folder of dashboards); the name tells you which
 * ones - a grid of blank rectangles would say neither.
 *
 * <p>Drawing the shape rather than the page itself is deliberate: the cards render a live
 * thumbnail per page, and six live frames per tile would cost more than the tile is worth.
 */
export function InterfaceFolderFace({ preview }: InterfaceFolderFaceProps) {
  return (
    <div className="relative h-[120px] overflow-hidden bg-white dark:bg-slate-900">
      {preview.length === 0 ? (
        <div className="flex items-center justify-center w-full h-full">
          <Folder className="h-9 w-9 text-theme-muted" />
        </div>
      ) : (
        <div className="grid grid-cols-3 grid-rows-2 gap-1.5 w-full h-full p-2.5">
          {preview.slice(0, FACE_CELLS).map((item) => (
            <PageSilhouette key={item.id} item={item} />
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

/**
 * One page inside the folder: a browser-ish rectangle sized to the page's declared format
 * ({@code subtitle} carries it), with the page's name where a title bar would be.
 */
function PageSilhouette({ item }: { item: FolderPreviewItem }) {
  const viewport = resolveInterfaceFormatOrDefault(item.subtitle);
  const aspectRatio = `${viewport.width} / ${viewport.height}`;
  const isPortrait = viewport.height > viewport.width;

  return (
    <div className="flex items-center justify-center overflow-hidden" title={item.name}>
      <div
        className="max-w-full max-h-full rounded-[3px] border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 overflow-hidden flex flex-col"
        style={{ aspectRatio, height: '100%' }}
      >
        {/* Title bar - the page's own name, truncated to whatever its shape affords. */}
        <div className="px-[2px] bg-slate-200/90 dark:bg-slate-700/90 shrink-0">
          <span className="block text-[7px] leading-[9px] font-medium text-theme-primary truncate">
            {item.name}
          </span>
        </div>
        {/* A hint of layout: a wide page reads as columns, a tall one as stacked blocks. */}
        <div className={`flex-1 flex gap-[2px] p-[3px] ${isPortrait ? 'flex-col' : 'flex-row'}`}>
          <span className="flex-1 rounded-[1px] bg-slate-200 dark:bg-slate-700" />
          <span className="flex-1 rounded-[1px] bg-slate-100 dark:bg-slate-700/60" />
        </div>
      </div>
    </div>
  );
}
