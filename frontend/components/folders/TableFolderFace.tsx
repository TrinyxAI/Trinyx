'use client';

import React from 'react';
import { Folder } from 'lucide-react';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';

/** Cells of the tile's face: three across, two down. Mirrors PREVIEW_SIZE server-side. */
const FACE_CELLS = 6;

interface TableFolderFaceProps {
  /** Up to six tables of the folder, newest first (the backend already trimmed them). */
  preview: FolderPreviewItem[];
}

/**
 * What a folder of TABLES looks like: one small table per item, each showing its NAME and
 * its first real columns. A grid of anonymous rules would only say "these are tables"; the
 * names and columns say WHICH tables, which is the only reason to read a folder without
 * opening it.
 *
 * <p>An empty folder shows the folder mark instead, rather than a grid of blanks.
 */
export function TableFolderFace({ preview }: TableFolderFaceProps) {
  return (
    <div className="relative h-[120px] overflow-hidden bg-white dark:bg-slate-900">
      {preview.length === 0 ? (
        <div className="flex items-center justify-center w-full h-full">
          <Folder className="h-9 w-9 text-theme-muted" />
        </div>
      ) : (
        <div className="grid grid-cols-3 grid-rows-2 gap-1.5 w-full h-full p-2.5">
          {preview.slice(0, FACE_CELLS).map((item) => (
            <MiniTable key={item.id} item={item} />
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

/** One table inside the folder: its name as the header band, then its first columns. */
function MiniTable({ item }: { item: FolderPreviewItem }) {
  const columns = (item.icons ?? []) as Array<{ name?: string }>;

  return (
    <div
      className="rounded-[3px] border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 overflow-hidden flex flex-col"
      title={item.name}
    >
      <div className="px-1 py-[1px] bg-slate-200/80 dark:bg-slate-700/80 shrink-0">
        <span className="block text-[8px] leading-[10px] font-medium text-theme-primary truncate">
          {item.name}
        </span>
      </div>
      {columns.length > 0 ? (
        <div className="flex-1 flex flex-col">
          {/* Column headers - the table's own fields, not placeholders. */}
          <div className="flex gap-[1px] border-b border-slate-200 dark:border-slate-700">
            {columns.map((column, i) => (
              <span
                key={`${column.name}-${i}`}
                className="flex-1 min-w-0 px-[2px] text-[7px] leading-[9px] text-theme-muted truncate"
              >
                {column.name}
              </span>
            ))}
          </div>
          {/* Two ruled rows under them, one cell per column. */}
          {[0, 1].map((row) => (
            <div key={row} className="flex gap-[1px] flex-1 items-center">
              {columns.map((column, i) => (
                <span
                  key={`${column.name}-${row}-${i}`}
                  className="flex-1 mx-[2px] h-[2px] rounded-full bg-slate-200 dark:bg-slate-700"
                />
              ))}
            </div>
          ))}
        </div>
      ) : (
        /* A table with no columns of its own yet: say so rather than draw fake ones. */
        <div className="flex-1 flex items-center justify-center">
          <span className="text-[7px] text-theme-muted">-</span>
        </div>
      )}
    </div>
  );
}
