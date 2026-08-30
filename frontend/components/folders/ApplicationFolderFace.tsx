'use client';

import React from 'react';
import { Folder } from 'lucide-react';
import { ShowcasePreview } from '@/components/marketplace/ShowcasePreview';
import { showcaseBindingFor, type ShowcaseAppInput } from '@/lib/applications/showcasePreview';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';

/**
 * Cells of the tile's face at most: FOUR, two across and two down.
 *
 * <p>Two across and two down rather than the six the workflow, agent and table faces draw:
 * those hold flat marks that fill their cell, while this one holds a running page that keeps
 * its own proportions, and a sixth of a tile leaves nothing of it to recognise. Nothing is
 * drawn in a cell with no app in it, for the same reason - a filler would fill its whole cell
 * beside a preview that cannot.
 *
 * <p>Exported because the list that owns the tiles bounds how many live showcases a whole
 * level may run, and that ceiling is counted in these cells.
 */
export const APPLICATION_FACE_CELLS = 4;

/** As tall as the resource cards it sits among, so the grid stays one surface. */
const FACE_HEIGHT = 120;
/** `p-2.5` and `gap-1.5`, in the pixels they resolve to, so the row height can be computed. */
const FACE_PADDING = 10;
const FACE_GAP = 6;
/** What one of the two rows is left with. */
const ROW_HEIGHT = (FACE_HEIGHT - FACE_PADDING * 2 - FACE_GAP) / 2;

/**
 * Widest a preview may be: an application preview is a 16:10 box that takes its HEIGHT from
 * its width, so without this it grows past its row and gets its top and bottom cropped off.
 * Derived rather than written down, so changing the face's height or padding cannot silently
 * start cropping every app on the page.
 */
const MAX_PREVIEW_WIDTH = Math.floor((ROW_HEIGHT * 16) / 10);

interface ApplicationFolderFaceProps {
  /** Up to six applications of the folder, most recently updated first. */
  preview: FolderPreviewItem[];
  /**
   * The app behind a preview id, when the page holding this tile knows it. Returning an app
   * makes the cell draw that app's LIVE showcase, exactly as its card does; returning
   * nothing (or an app with no captured showcase) falls back to its cover.
   */
  resolveApp?: (publicationId: string) => ShowcaseAppInput | undefined;
}

/**
 * What a folder of APPLICATIONS looks like: the apps it holds, each showing the same live
 * showcase its card shows, in miniature. An app IS its page, so the folder shows pages -
 * a grid of initials would only say how many there are, which the footer already says.
 *
 * <p>Each preview loads itself only once its tile is on screen (see {@code ShowcasePreview}),
 * starts muted, and falls back to the app's cover when there is no captured showcase to
 * render. An empty folder shows the folder mark instead of a grid of blanks.
 */
export function ApplicationFolderFace({ preview, resolveApp }: ApplicationFolderFaceProps) {
  // Two explicit rows and columns, so an app lands in the same quarter of the face however
  // many the folder holds.
  const shown = preview.slice(0, APPLICATION_FACE_CELLS);

  return (
    // The height is a constant rather than a class because MAX_PREVIEW_WIDTH is computed from
    // it: written in two places they would drift, and the drift crops every preview.
    <div className="relative overflow-hidden bg-white dark:bg-slate-900" style={{ height: FACE_HEIGHT }}>
      {shown.length === 0 ? (
        <div className="flex items-center justify-center w-full h-full">
          <Folder className="h-9 w-9 text-theme-muted" />
        </div>
      ) : (
        <div className="grid grid-cols-2 grid-rows-2 gap-1.5 w-full h-full p-2.5">
          {shown.map((item) => (
            <AppCell key={item.id} item={item} app={resolveApp?.(item.id)} />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * One application inside the folder: its live showcase when it has one, its cover otherwise.
 * A render that fails (retention expired, publication made private) drops to the cover too,
 * rather than leaving a hole in the face.
 */
function AppCell({ item, app }: { item: FolderPreviewItem; app?: ShowcaseAppInput }) {
  const [renderFailed, setRenderFailed] = React.useState(false);
  const showcase = app ? showcaseBindingFor(app) : null;

  return (
    <div className="min-w-0 min-h-0 flex items-center justify-center overflow-hidden" title={item.name}>
      {showcase?.canPreview && !renderFailed ? (
        <div
          // The showcase is a sandboxed iframe, and pointer events inside one never reach this
          // document: the tile it sits on is meant to be clicked and dragged, so the showcase
          // must not take those events at all. The cell around it keeps them, and its tooltip.
          className="w-full rounded-md overflow-hidden ring-1 ring-slate-200 dark:ring-slate-700 pointer-events-none"
          style={{ maxWidth: MAX_PREVIEW_WIDTH }}
        >
          <ShowcasePreview
            runId={showcase.runId}
            interfaceId={showcase.interfaceId}
            publicationId={showcase.publicationId}
            authenticated={showcase.authenticated}
            remote={showcase.remote}
            // A whole grid of folder tiles can be on screen at once, so no preview may
            // speak until someone asks it to - the app's own card owns that control.
            mediaMuted
            hidePagination
            suppressErrorUi
            onError={() => setRenderFailed(true)}
            className="w-full"
          />
        </div>
      ) : (
        <AppCover name={item.name} imageUrl={item.imageUrl} />
      )}
    </div>
  );
}

/** The cover an app falls back to: its image when it has one, its initial otherwise. */
function AppCover({ name, imageUrl }: { name?: string; imageUrl?: string }) {
  const initial = (name ?? '?').trim().charAt(0).toUpperCase() || '?';

  return (
    <div
      className="w-full flex items-center justify-center rounded-md border border-slate-200 dark:border-slate-700 bg-slate-100 dark:bg-slate-800 overflow-hidden"
      style={{ aspectRatio: '16 / 10', maxWidth: MAX_PREVIEW_WIDTH }}
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
