// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}|${JSON.stringify(params)}` : key,
  useLocale: () => 'en',
}));

import ReferencePrice from '../ReferencePrice';
import { resolveActivePricingEvent, type PricingEvent } from '@/lib/billing/pricing-events';

const CAMPAIGN: PricingEvent = {
  id: 'test-window',
  startsAt: '2026-03-01T00:00:00.000Z',
  endsAt: '2026-03-31T23:59:59.999Z',
  renewEveryDays: 7,
  announcedBasePrices: { starter: 19, pro: 45, team: 89 },
  locksPrice: true,
};

const EVENT = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [CAMPAIGN])!;

afterEach(() => cleanup());

describe('ReferencePrice', () => {
  it('renders nothing when no window is open', () => {
    const { container } = render(
      <ReferencePrice planId="pro" cycle="monthly" creditTierIndex={0} event={null} />
    );

    expect(container.innerHTML).toBe('');
  });

  it('renders nothing for a plan the event says nothing about', () => {
    const { container } = render(
      <ReferencePrice planId="free" cycle="monthly" creditTierIndex={0} event={EVENT} />
    );

    expect(container.innerHTML).toBe('');
  });

  it('shows the post-window price struck through', () => {
    const { container } = render(
      <ReferencePrice planId="pro" cycle="monthly" creditTierIndex={0} event={EVENT} />
    );

    expect(screen.getByText('$45')).toBeTruthy();
    expect(container.querySelector('.line-through')).not.toBeNull();
  });

  it('never shows the price that is actually billed', () => {
    // The struck figure is the FUTURE price. If it ever equalled the billed one, the card
    // would be crossing out the amount the customer is about to be charged.
    const { container } = render(
      <ReferencePrice planId="pro" cycle="monthly" creditTierIndex={0} event={EVENT} />
    );

    expect(container.textContent).not.toContain('$24');
  });

  it('follows the yearly cycle, so it stays comparable with the price beside it', () => {
    render(<ReferencePrice planId="pro" cycle="yearly" creditTierIndex={0} event={EVENT} />);

    expect(screen.getByText('$36')).toBeTruthy();
  });

  it('includes the selected credit pack, like the price beside it does', () => {
    render(<ReferencePrice planId="pro" cycle="monthly" creditTierIndex={4} event={EVENT} />);

    expect(screen.getByText('$125')).toBeTruthy();
  });

  it('spells out what the struck figure means for screen readers', () => {
    // A bare struck number announces two prices with no way to tell which one applies.
    const { container } = render(
      <ReferencePrice planId="pro" cycle="monthly" creditTierIndex={0} event={EVENT} />
    );

    expect(container.querySelector('.sr-only')?.textContent).toContain('card.referenceTitle');
    expect(container.querySelector('[aria-hidden="true"]')?.textContent).toBe('$45');
  });
});
