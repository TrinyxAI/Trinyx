'use client';

import React from 'react';
import { Folder } from 'lucide-react';
import { InterfaceThumbnail } from '@/app/workflows/builder/components/interface/InterfaceThumbnail';
import { resolveInterfaceFormatOrDefault } from '@/lib/interfaces/interfaceFormats';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';

/**
 * Cells of the tile's face at most: FOUR, two across and two down.
 *
 * <p>Two across and two down rather than the six the workflow, agent and table faces draw:
 * those hold flat marks that fill their cell, while this one holds a rendered page that keeps
 * its own shape, and a quarter of the face is about the least that still shows what that shape
 * and those colours are. Nothing is drawn in a cell with no page in it, for the same reason -
 * a filler would fill its whole cell beside a render that cannot.
 *
 * <p>Exported because the list that owns the tiles loads exactly this many templates per
 * folder - the two must agree or the face draws pages it never asked for.
 */
export const INTERFACE_FACE_CELLS = 4;

/** The html/css/js of one page, once the list has pulled it. */
export interface InterfaceFaceTemplate {
  htmlTemplate?: string;
  cssTemplate?: string;
  jsTemplate?: string;
}

interface InterfaceFolderFaceProps {
  /** Up to six pages of the folder, newest first (the backend already trimmed them). */
  preview: FolderPreviewItem[];
  /**
   * Page id -> its template, for the pages whose html the list has loaded. A page that is
   * absent (still loading, or failed) draws its silhouette instead, in the same frame, so
   * the face never jumps as the templates arrive.
   */
  templates?: Map<string, InterfaceFaceTemplate>;
}

/**
 * What a folder of PAGES looks like: the pages themselves, rendered, each in its OWN shape -
 * the same miniature its card shows. The shape tells you what kind of pages the folder holds
 * (a folder of phone-sized pages does not look like a folder of dashboards) and the render
 * tells you WHICH ones, which no drawn placeholder can.
 */
export function InterfaceFolderFace({ preview, templates }: InterfaceFolderFaceProps) {
  // Two explicit rows and columns, so a page lands in the same quarter of the face however
  // many the folder holds.
  const shown = preview.slice(0, INTERFACE_FACE_CELLS);

  return (
    <div className="relative h-[120px] overflow-hidden bg-white dark:bg-slate-900">
      {shown.length === 0 ? (
        <div className="flex items-center justify-center w-full h-full">
          <Folder className="h-9 w-9 text-theme-muted" />
        </div>
      ) : (
        <div className="grid grid-cols-2 grid-rows-2 gap-1.5 w-full h-full p-2.5">
          {shown.map((item) => (
            <PageCell key={item.id} item={item} template={templates?.get(item.id)} />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * One page inside the folder, rendered at its declared format ({@code subtitle} carries it)
 * and letterboxed into the cell, so the frame is exactly the page's own shape.
 */
function PageCell({ item, template }: { item: FolderPreviewItem; template?: InterfaceFaceTemplate }) {
  const viewport = resolveInterfaceFormatOrDefault(item.subtitle);
  const html = template?.htmlTemplate;

  return (
    <div className="min-w-0 min-h-0 flex items-center justify-center overflow-hidden" title={item.name}>
      {html && html.trim() ? (
        <InterfaceThumbnail
          htmlTemplate={html}
          mode="edit"
          customCss={template?.cssTemplate || undefined}
          jsTemplate={template?.jsTemplate || undefined}
          fit="contain"
          viewport={viewport}
          // The render is a sandboxed iframe, and pointer events inside one never reach this
          // document: the tile it sits on is meant to be clicked and dragged, so the render
          // must not take those events at all. The cell around it keeps them, and its tooltip.
          className="w-full h-full pointer-events-none"
          // The frame is the page's real shape, so the border hugs the render instead of
          // boxing the letterbox around it.
          frameClassName="rounded-[4px] overflow-hidden border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800"
        />
      ) : (
        <PageSilhouette item={item} viewport={viewport} />
      )}
    </div>
  );
}

/**
 * The stand-in for a page whose html is not loaded (or is empty): the page's shape with its
 * name where a title bar would be. Same frame as the render it is waiting for.
 */
function PageSilhouette({ item, viewport }: { item: FolderPreviewItem; viewport: { width: number; height: number } }) {
  const aspectRatio = `${viewport.width} / ${viewport.height}`;
  const isPortrait = viewport.height > viewport.width;

  return (
    <div
      className="max-w-full max-h-full rounded-[4px] border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 overflow-hidden flex flex-col"
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
  );
}
