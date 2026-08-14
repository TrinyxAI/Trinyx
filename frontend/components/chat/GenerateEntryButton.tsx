'use client';

import * as React from 'react';
import { Sparkles } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { warmGenerationModal } from '@/components/chat/generationModalEntry';

/**
 * The control that opens the generation dialog, wherever one is offered.
 *
 * <p><b>Why this is a component and not a pattern to copy.</b> Every surface
 * that offers a generation has to answer the same two questions before drawing
 * anything: may this reader write here, and does this install serve generation
 * at all. Both were written out per surface, and the per-surface copy is where
 * the answers drift: the second one keeps the button on a 404, or drops it on a
 * 5xx, or renders a disabled control whose reason nobody can reach. Those are
 * not styling differences, so they do not belong to the surfaces.
 *
 * <p>What DOES belong to the surface is how the control looks: a page toolbar
 * has room for a word, a composer's button row has room for an icon. That is
 * the only thing {@link GenerateEntryButtonProps.variant} decides.
 *
 * <p>Renders NOTHING when a generation cannot be started here, and asks the
 * catalogue nothing on behalf of a reader who could not act on the answer.
 */
export interface GenerateEntryButtonProps {
  /**
   * `toolbar` shows the word beside the icon (dropped under `sm`, where the row
   * gets tight); `icon` is icon-only, for a row that is already all icons.
   */
  variant: 'toolbar' | 'icon';
  /** What the control is called, and its accessible name. */
  label: string;
  /** Open the dialog. The caller mounts it, so it can do its own thing with the result. */
  onOpen: () => void;
}

/** @see the module docblock above for why the gate lives here and not per surface. */
export function GenerateEntryButton({ variant, label, onOpen }: GenerateEntryButtonProps) {
  // The dialog's own sentence, restated on the control that opens it, so the
  // two can never drift into two different facts about the same catalogue.
  const tGeneration = useTranslations('generationModal');

  // A generation writes a file into the workspace and spends credits, so a
  // read-only VIEWER is not offered one. Asked only of a reader who could act
  // on the answer, so they cost the install no request either.
  const canGenerate = useCanMutateInCurrentOrg();
  // Through the SAME query key and cache the dialog reads: a surface and the
  // dialog it opens cost one request between them, not two.
  const { availability } = useGenerationModels(canGenerate);

  // Ties the disabled control to the sentence saying why. Per instance, not a
  // module constant: a page can hold two of these at once (the docked chat
  // beside the main one), and two elements sharing an id would point every
  // `aria-describedby` at the first one's text.
  const reasonId = React.useId();

  // 404: this install does not serve generation. Nothing anyone does in the app
  // changes that, so the control is not offered at all.
  //
  // Every other answer keeps it, INCLUDING 'unknown' (in flight, a 5xx, a
  // connection that never landed): none of those say the feature is absent, and
  // hiding one the install really has is a lie that looks exactly like the
  // truth. It also keeps the row stable, instead of growing a control once the
  // first fetch lands.
  if (!canGenerate || availability === 'absent') return null;

  // Served, but with nothing in it. The feature IS here and an administrator
  // can seed it, so the control stays, visibly unavailable and carrying the
  // reason, rather than vanishing.
  const empty = availability === 'empty';

  return (
    // The reason lives on the WRAPPER, not on the button: a disabled Button
    // carries `pointer-events-none`, so a title on it would never be shown to
    // the person hovering it, and an unavailable action with no reachable
    // explanation reads as a permission they lack.
    <span className="inline-flex" title={empty ? tGeneration('empty') : label}>
      <Button
        variant={variant === 'toolbar' ? 'outline' : 'ghost'}
        size={variant === 'toolbar' ? 'default' : 'icon'}
        className={variant === 'toolbar' ? 'px-2.5 sm:px-4' : 'h-9 w-9'}
        aria-label={label}
        onClick={onOpen}
        // Both, because they are two different readers: a pointer hovers, a
        // keyboard focuses, and neither should be the one that waits.
        onMouseEnter={warmGenerationModal}
        onFocus={warmGenerationModal}
        disabled={empty}
        aria-describedby={empty ? reasonId : undefined}
      >
        <Sparkles className={variant === 'toolbar' ? 'h-4 w-4 sm:mr-1.5' : 'h-4 w-4'} />
        {variant === 'toolbar' && <span className="hidden sm:inline">{label}</span>}
      </Button>
      {/* The same words, for a reader who never hovers anything. */}
      {empty && (
        <span id={reasonId} className="sr-only">
          {tGeneration('empty')}
        </span>
      )}
    </span>
  );
}
