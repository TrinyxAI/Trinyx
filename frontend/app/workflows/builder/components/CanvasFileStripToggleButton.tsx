'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronsDownUp, ChevronsUpDown } from 'lucide-react';
import { canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
import { useFileStripExpansionSafe } from '@/contexts/FileStripExpansionContext';

/**
 * Toolbar control that unfolds - or folds back - EVERY file preview hanging
 * under the nodes of a run at once, instead of clicking each pill in turn.
 *
 * Renders nothing until at least one file strip is on the canvas, which in
 * practice means run mode on a run that actually produced files: a control that
 * could only ever no-op has no business taking a slot in the toolbar. It reads
 * that condition from the strips themselves (they register on mount) rather than
 * re-deriving "does this node show a file?" from the node list, which is decided
 * deep inside FileNodePreview and would drift.
 */
export function CanvasFileStripToggleButton() {
  const t = useTranslations('workflowBuilder.canvas');
  const expansion = useFileStripExpansionSafe();

  if (!expansion || expansion.stripCount === 0) return null;

  // A partially-expanded canvas counts as "not all expanded", so the first click
  // always brings every preview up and the next one puts them all away. The
  // alternative (treating any expanded strip as "expanded") makes the first
  // click collapse the one preview the user had just opened.
  const allExpanded = expansion.expandedCount >= expansion.stripCount;
  const label = allExpanded ? t('collapseAllFiles') : t('expandAllFiles');

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
        className={canvasChromeCompactButtonClass(allExpanded)}
        title={label}
      >
        {allExpanded ? <ChevronsDownUp className="h-4 w-4" /> : <ChevronsUpDown className="h-4 w-4" />}
      </button>
    </div>
  );
}
