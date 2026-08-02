'use client';

import { Server } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ceExclusiveFeatureKeys, isCeExclusive, type CeExclusiveLike } from '@/lib/marketplace/ceExclusive';

interface CeExclusiveBadgeProps {
  publication: CeExclusiveLike | null | undefined;
  /**
   * `chip` overlays the card thumbnail (translucent, like the price pill);
   * `inline` sits in a text row (detail panel, modal header).
   */
  variant?: 'chip' | 'inline';
  className?: string;
}

/**
 * "Self-hosted only" marker for a publication that needs a Community Edition
 * install (local-CLI agents, vector search).
 *
 * Renders on BOTH editions on purpose: on cloud it explains why the install is
 * refused, on a self-hosted install it advertises that this app is one the user
 * can actually run. The tooltip names the features behind the requirement so
 * the badge is never a dead end.
 *
 * Returns null when the publication is not CE-exclusive, so callers can drop it
 * in unconditionally.
 */
export function CeExclusiveBadge({ publication, variant = 'chip', className = '' }: CeExclusiveBadgeProps) {
  const t = useTranslations('marketplace');

  if (!isCeExclusive(publication)) return null;

  const featureLabels = ceExclusiveFeatureKeys(publication).map((key) => t(key));
  const tooltip = featureLabels.length > 0
    ? `${t('ceExclusiveTooltip')} (${featureLabels.join(', ')})`
    : t('ceExclusiveTooltip');

  const base = 'inline-flex items-center gap-1 font-medium shrink-0';
  // text-xs is the badge size in the typography scale; the chip variant matches
  // the neighbouring price pill so the two read as one row of chips.
  const skin = variant === 'chip'
    ? 'h-[22px] px-2 rounded-md text-xs backdrop-blur bg-white/80 dark:bg-black/50 text-theme-primary border border-white/40 dark:border-white/10'
    : 'h-[22px] px-2 rounded-md text-xs bg-theme-secondary text-theme-primary border border-theme';

  return (
    <span
      data-testid="ce-exclusive-badge"
      className={`${base} ${skin} ${className}`.trim()}
      title={tooltip}
    >
      <Server className="h-3 w-3" />
      {t('ceExclusiveBadge')}
    </span>
  );
}
