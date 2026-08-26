'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { RotateCcw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { FormatGlyph } from '@/lib/generation/formats';
import { useGenerationProvenance } from '@/hooks/useGenerationHistory';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import type { GenerationProvenance } from '@/lib/api/storage-api';

/**
 * "Generated with X, from these words" - and the way to run it again.
 *
 * <p><b>Why an asset needs this at all.</b> Once a generated file lands in the workspace it is
 * indistinguishable from an uploaded one: same row, same bytes, nothing saying which model made it
 * or from which prompt. Looking at it, there was no way to tell it was generated, and no way to
 * make a variant except retyping the prompt from memory - which is a new guess, not a variation.
 *
 * <p><b>Why it is a separate component and not part of the file viewer.</b> The viewer is mounted
 * on every file, everywhere: the Files page, the side panel, the chat cards, the generation
 * dialog's own result preview. Asking each of those for a recipe would spend a request per file
 * opened, on an answer that is null for almost all of them, and would make a leaf component that
 * renders a PNG depend on the query cache being present. Mounted only where a Regenerate control
 * is actually offered, the cost and the dependency land exactly where the feature is used.
 *
 * <p>Renders NOTHING for a file that was not generated here, which is the ordinary case.
 */
export interface GenerationRecipeCardProps {
  /** Storage row id of the asset. */
  entryId: string;
  /** Run this asset's generation again, with its recipe in hand. */
  onRegenerate: (provenance: GenerationProvenance) => void;
  className?: string;
}

export function GenerationRecipeCard({
  entryId,
  onRegenerate,
  className,
}: GenerationRecipeCardProps) {
  const t = useTranslations('generationHistory');
  const { provenance } = useGenerationProvenance(entryId);
  // Through the SAME cache every other generation surface reads. Used for one thing: whether the
  // model still exists. A model that has left the catalogue cannot be re-run, and letting the
  // button open the form anyway would silently land the reader on a DIFFERENT model with their old
  // prompt in it - a variant of something else, with nothing on screen saying so.
  const { models } = useGenerationModels();

  if (!provenance) return null;

  const reusable = models.some((m) => m.model === provenance.model);

  return (
    <div className={`w-full max-w-md rounded-xl border border-theme bg-theme-secondary p-3 ${className ?? ''}`}>
      <div className="flex items-center gap-1.5 text-xs text-theme-secondary">
        <FormatGlyph kind={provenance.kind} className="h-3.5 w-3.5 flex-shrink-0" />
        <span className="truncate">{t('generatedWith', { model: provenance.model })}</span>
      </div>
      {/* The prompt, clamped rather than cut: a long one is common, and the whole of it is one
          hover away, while the button under it must stay reachable without scrolling. */}
      {provenance.prompt && (
        <p
          className="mt-1.5 text-sm text-theme-primary line-clamp-3 break-words"
          title={provenance.prompt}
        >
          {provenance.prompt}
        </p>
      )}
      <div className="mt-2.5" title={reusable ? undefined : t('reuseUnavailable')}>
        <Button
          variant="outline"
          size="sm"
          className="w-full"
          disabled={!reusable}
          onClick={() => onRegenerate(provenance)}
        >
          <RotateCcw className="mr-1.5 h-3.5 w-3.5" />
          {t('regenerate')}
        </Button>
      </div>
    </div>
  );
}

export default GenerationRecipeCard;
