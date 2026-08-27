'use client';

import React from 'react';
import { Folder } from 'lucide-react';
import { AvatarDisplay } from '@/components/agents/AvatarPicker';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';

/** Cells of the tile's face: three across, two down. Mirrors PREVIEW_SIZE server-side. */
const FACE_CELLS = 6;

interface AgentFolderFaceProps {
  /** Up to six agents of the folder, newest first (the backend already trimmed them). */
  preview: FolderPreviewItem[];
}

/**
 * What a folder of AGENTS looks like: the same white ground the agent cards use, holding
 * the faces of the agents inside. An agent card IS its avatar, so a folder of agents is the
 * avatars it holds - you recognise the folder by the crew in it.
 *
 * <p>An empty folder shows the folder mark instead, rather than a grid of blanks.
 */
export function AgentFolderFace({ preview }: AgentFolderFaceProps) {
  return (
    <div className="relative h-[120px] overflow-hidden bg-white dark:bg-slate-900">
      {preview.length === 0 ? (
        <div className="flex items-center justify-center w-full h-full">
          <Folder className="h-9 w-9 text-theme-muted" />
        </div>
      ) : (
        <div className="grid grid-cols-3 grid-rows-2 gap-1.5 w-full h-full p-2.5">
          {preview.slice(0, FACE_CELLS).map((item) => (
            <div key={item.id} className="flex items-center justify-center" title={item.name}>
              <AvatarDisplay avatarUrl={item.imageUrl} name={item.name} size="md" />
            </div>
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
