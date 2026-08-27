'use client';

import { useLocale, useTranslations } from 'next-intl';
import { announcedPrice, type ResolvedPricingEvent } from '@/lib/billing/pricing-events';

interface ReferencePriceProps {
  planId: string;
  cycle: 'monthly' | 'yearly';
  creditTierIndex: number;
  event: ResolvedPricingEvent | null;
  /** 'md' sits beside a text-3xl card price, 'sm' beside the text-2xl modal price. */
  size?: 'md' | 'sm';
  className?: string;
}

/**
 * The struck-through reference price shown beside a plan's real price while a pricing
 * event is open: what the plan costs once the window closes.
 *
 * Placed as a SIBLING of each surface's existing price element rather than replacing it,
 * so the figure rendered large stays the one actually billed. The struck number is never
 * the billed one.
 *
 * Accessibility: a bare struck number reads as noise to a screen reader, which announces
 * two prices with no way to tell which one applies. The visible figure is therefore
 * aria-hidden and paired with a spelled-out label.
 *
 * Renders nothing when no window is open or the event announces nothing for this plan,
 * so every card can mount it unconditionally.
 */
export default function ReferencePrice({
  planId,
  cycle,
  creditTierIndex,
  event,
  size = 'md',
  className = '',
}: ReferencePriceProps) {
  const t = useTranslations('pricing.event');
  const locale = useLocale();

  const reference = announcedPrice(planId, cycle, creditTierIndex, event);
  if (!event || reference === null) return null;

  const label = `$${reference.toLocaleString(locale)}`;

  return (
    <span
      className={`line-through font-semibold ${size === 'sm' ? 'text-base' : 'text-xl'} ${className}`}
      style={{ color: 'var(--text-muted)' }}
      title={t('card.referenceTitle', { price: label })}
    >
      <span className="sr-only">{t('card.referenceTitle', { price: label })}</span>
      <span aria-hidden="true">{label}</span>
    </span>
  );
}
