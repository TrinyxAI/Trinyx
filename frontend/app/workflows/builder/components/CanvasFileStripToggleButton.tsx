'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronsDownUp, ChevronsUpDown, Images } from 'lucide-react';
import { canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
import { useFileStripExpansionSafe } from '@/contexts/FileStripExpansionContext';

/**
 * Toolbar control for whether file previews hang open under the nodes of a run.
 *
 * It is a REMEMBERED preference, not a one-shot bulk action: the click writes it to
 * local storage, and it is what a strip falls back to on the next run, the next epoch
 * and the next visit. That is also why the control stays on the toolbar with nothing
 * on screen - it used to hide itself whenever the canvas carried no strip, which meant
 * the only moment you could state "I want previews open" was a moment when they were
 * already in front of you. Setting it in edit mode, or on a run that produced no file,
 * is exactly when it is worth setting.
 *
 * Still renders nothing outside the provider: on a surface with no canvas chrome there
 * is no toolbar to sit in.
 */
export function CanvasFileStripToggleButton() {
  const t = useTranslations('workflowBuilder.canvas');
  const expansion = useFileStripExpansionSafe();

  if (!expansion) return null;

  // With strips on screen: a partially-expanded canvas counts as "not all expanded", so
  // the first click always brings every preview up and the next one puts them all away.
  // The alternative (treating any expanded strip as "expanded") makes the first click
  // collapse the one preview the user had just opened.
  //
  // With NO strip, that comparison is 0 >= 0 - vacuously true - and would show the
  // toggle pressed while the stored preference says folded. So fall back to the
  // preference itself, which is the only thing the control acts on in that case.
  const hasStrips = expansion.stripCount > 0;
  const allExpanded = hasStrips
    ? expansion.expandedCount >= expansion.stripCount
    : expansion.defaultExpanded;

  // The click does two things at once, and which one it is worth naming depends on
  // what is on screen. With previews in front of the user it is a bulk action on
  // them; with nothing to act on it is ONLY the standing preference, and promising
  // to "expand all file previews" when there are none is a lie to anyone who cannot
  // see the empty canvas.
  const label = hasStrips
    ? (allExpanded ? t('collapseAllFiles') : t('expandAllFiles'))
    : (allExpanded ? t('collapseFilesByDefault') : t('expandFilesByDefault'));

  return (
    <div className="flex items-center gap-1 border-r border-[var(--border-color)] pr-1">
      <button
        type="button"
        data-testid="canvas-toggle-all-files"
        onClick={() => (allExpanded ? expansion.collapseAll() : expansion.expandAll())}
        // Icon-only control: the accessible name has to carry the ACTION, and
        // aria-pressed carries the state the icon shows.
        aria-label={label}
        aria-pressed={allExpanded}
        // Same shape, height, hover ladder and focus ring as every other chrome
        // control - it only gives up the square 28px width, because it is the one
        // control carrying two glyphs. Same `w-auto`/`px-1.5` widening the toolbar
        // already uses for its cursor-mode trigger.
        className={canvasChromeCompactButtonClass(allExpanded, 'w-auto gap-0.5 px-1.5')}
        title={label}
      >
        {/* Two glyphs, because one could not say both things: WHAT it acts on
            (media previews, not nodes or panels - a lone chevron pair on a canvas
            toolbar reads as "collapse the graph") and WHICH WAY it will go. The
            media glyph carries the sibling 16px size, the direction one is a
            modifier on it. */}
        <Images className="h-4 w-4" aria-hidden="true" />
        {allExpanded
          ? <ChevronsDownUp className="h-3 w-3" aria-hidden="true" />
          : <ChevronsUpDown className="h-3 w-3" aria-hidden="true" />}
      </button>
    </div>
  );
}
