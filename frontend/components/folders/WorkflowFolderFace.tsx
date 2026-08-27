'use client';

import React from 'react';
import { Folder } from 'lucide-react';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';
import type { NodeIconData } from '@/lib/api/orchestrator/types';

/** Cells of the tile's face: three across, two down. Mirrors PREVIEW_SIZE server-side. */
const FACE_CELLS = 6;

interface WorkflowFolderFaceProps {
  /** Up to six workflows of the folder, newest first (the backend already trimmed them). */
  preview: FolderPreviewItem[];
}

/**
 * What a folder of WORKFLOWS looks like: a plain sheet holding one small workflow per item.
 * The dotted canvas belongs to the WORKFLOWS, not to the folder - each mini card carries it,
 * exactly like the full-size cards around the tile, so the folder reads as a sheet with
 * little workflows on it rather than as one big canvas.
 *
 * <p>An empty folder shows the folder mark instead, rather than a grid of blanks.
 */
export function WorkflowFolderFace({ preview }: WorkflowFolderFaceProps) {
  return (
    <div className="relative h-[120px] overflow-hidden bg-white dark:bg-slate-800">
      {preview.length === 0 ? (
        <div className="flex items-center justify-center w-full h-full">
          <Folder className="h-9 w-9 text-theme-muted" />
        </div>
      ) : (
        <div className="grid grid-cols-3 grid-rows-2 gap-1.5 w-full h-full p-2.5">
          {preview.slice(0, FACE_CELLS).map((item) => (
            <MiniWorkflow key={item.id} item={item} />
          ))}
          {/* Keep the 3x2 rhythm when the folder holds fewer than six. */}
          {Array.from({ length: Math.max(0, FACE_CELLS - Math.min(preview.length, FACE_CELLS)) }).map((_, i) => (
            <div key={`empty-${i}`} className="rounded-md bg-slate-50 dark:bg-slate-900/40" />
          ))}
        </div>
      )}
    </div>
  );
}

/** One workflow inside the folder: its own little dotted canvas, carrying its node icons. */
function MiniWorkflow({ item }: { item: FolderPreviewItem }) {
  const icons = (item.icons ?? []) as NodeIconData[];
  return (
    <div
      className="relative flex items-center justify-center rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 overflow-hidden px-1"
      title={item.name}
    >
      {/* The workflow's dotted canvas, at the scale of the mini card (light + dark). */}
      <div
        className="absolute inset-0 dark:hidden"
        style={{
          backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)',
          backgroundSize: '8px 8px',
        }}
      />
      <div
        className="hidden dark:block absolute inset-0"
        style={{
          backgroundImage: 'radial-gradient(circle, #475569 1px, transparent 1px)',
          backgroundSize: '8px 8px',
        }}
      />
      <div className="relative z-10">
        {icons.length > 0 ? (
          /* Two icons plus the "+N" badge: three 24px bubbles and their gaps come to 80px,
             which is what a cell of the 3x2 face has room for. */
          <WorkflowNodeIcons nodeIcons={icons} size="inline" maxDisplay={2} />
        ) : (
          <span className="text-[10px] text-theme-muted truncate px-1">{item.name}</span>
        )}
      </div>
    </div>
  );
}
