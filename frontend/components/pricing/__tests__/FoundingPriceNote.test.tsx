// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => {
  // t.rich runs the <b> chunk through the callback exactly like next-intl does, so a test
  // can assert that the DATE is the emphasised part and not merely that bold text exists.
  const t = (key: string, params?: Record<string, unknown>) =>
    params ? `${key}|${JSON.stringify(params)}` : key;
  t.rich = (key: string, values: Record<string, unknown>) => {
    const bold = values.b as (chunks: unknown) => unknown;
    return [key, ': ', bold(String(values.date))];
  };
  return { useTranslations: () => t, useLocale: () => 'en' };
});

import FoundingPriceNote from '../FoundingPriceNote';
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

describe('FoundingPriceNote', () => {
  it('renders nothing when no window is open', () => {
    const { container } = render(
      <FoundingPriceNote planId="pro" cycle="monthly" creditTierIndex={0} event={null} />
    );

    expect(container.innerHTML).toBe('');
  });

  it('renders nothing for a plan with no struck price to explain', () => {
    // Free and Enterprise get no reference price, so a caption would caption nothing.
    const { container } = render(
      <FoundingPriceNote planId="free" cycle="monthly" creditTierIndex={0} event={EVENT} />
    );

    expect(container.innerHTML).toBe('');
  });

  it('promises the price is guaranteed and kept', () => {
    render(<FoundingPriceNote planId="pro" cycle="monthly" creditTierIndex={0} event={EVENT} />);

    expect(screen.getByText(/card\.foundingUntilKept/)).toBeTruthy();
  });

  it('shows the current guarantee window deadline, not the campaign end', () => {
    // The whole point of the rolling window: a visitor sees days, not the month-away
    // campaign end. Printing 31 Mar here would flatten the urgency the window exists for.
    const { container } = render(
      <FoundingPriceNote planId="pro" cycle="monthly" creditTierIndex={0} event={EVENT} />
    );

    expect(container.textContent).toContain('Mar 8, 2026');
    expect(container.textContent).not.toContain('Mar 31');
  });

  it('emphasises the date, since that is the part that moves', () => {
    const { container } = render(
      <FoundingPriceNote planId="pro" cycle="monthly" creditTierIndex={0} event={EVENT} />
    );

    expect(container.querySelector('strong')?.textContent).toBe('Mar 8, 2026');
  });

  it('drops the kept-price promise when the event does not make one', () => {
    render(
      <FoundingPriceNote
        planId="pro"
        cycle="monthly"
        creditTierIndex={0}
        event={{ ...EVENT, locksPrice: false }}
      />
    );

    expect(screen.queryByText(/card\.foundingUntilKept/)).toBeNull();
    expect(screen.getByText(/card\.foundingUntil/)).toBeTruthy();
  });
});
