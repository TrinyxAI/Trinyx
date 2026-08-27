'use client';

import { useLocale, useTranslations } from 'next-intl';
import {
  announcedPrice,
  formatEventDeadline,
  type ResolvedPricingEvent,
} from '@/lib/billing/pricing-events';

interface FoundingPriceNoteProps {
  planId: string;
  cycle: 'monthly' | 'yearly';
  creditTierIndex: number;
  event: ResolvedPricingEvent | null;
  className?: string;
}

/**
 * The caption under a plan card's price while a pricing event is open: what the
 * struck-through figure beside the price means, and how long the real price is guaranteed.
 *
 * A struck price with no caption is just a discount claim. The caption is what makes the
 * card say the true thing instead: this is a dated founding price, guaranteed to the end
 * of the current window, and subscribing inside it keeps the price.
 *
 * The date is bold because it is the only part a returning visitor needs to re-read: the
 * window rolls, so the figure stays put while the deadline moves.
 *
 * Renders nothing when no window is open or the event announces nothing for this plan.
 */
export default function FoundingPriceNote({
  planId,
  cycle,
  creditTierIndex,
  event,
  className = '',
}: FoundingPriceNoteProps) {
  const t = useTranslations('pricing.event');
  const locale = useLocale();

  // Gated on the announced price, not merely on the window: a plan the event says nothing
  // about (Free, Enterprise) shows no struck price, so a caption would explain nothing.
  const reference = announcedPrice(planId, cycle, creditTierIndex, event);
  if (!event || reference === null) return null;

  const date = formatEventDeadline(event, locale, 'short');
  const key = event.locksPrice ? 'card.foundingUntilKept' : 'card.foundingUntil';

  return (
    <p className={`mt-1.5 text-xs ${className}`} style={{ color: 'var(--text-muted)' }}>
      {t.rich(key, {
        date,
        // Emphasis lives in the message, not around it: every locale decides where the
        // date falls in its own sentence.
        b: (chunks) => (
          <strong className="font-semibold" style={{ color: 'var(--text-primary)' }}>
            {chunks}
          </strong>
        ),
      })}
    </p>
  );
}
