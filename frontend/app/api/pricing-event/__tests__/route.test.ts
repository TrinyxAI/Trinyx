import { describe, it, expect, vi, afterEach } from 'vitest';
import { GET } from '../route';
import { PRICING_EVENTS } from '@/lib/billing/pricing-events';

const FOUNDING = PRICING_EVENTS.find((e) => e.id === 'founding-2026');

describe('GET /api/pricing-event', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('serves the open window when the server clock is inside it', async () => {
    expect(FOUNDING).toBeDefined();
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-15T12:00:00Z'));

    const body = await (await GET()).json();

    expect(body.event?.id).toBe('founding-2026');
  });

  it('serves no window once the founding window has closed', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2027-01-15T12:00:00Z'));

    const body = await (await GET()).json();

    expect(body.event).toBeNull();
  });

  it('serves no window before the founding window opens', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));

    const body = await (await GET()).json();

    expect(body.event).toBeNull();
  });

  it('reports the server time the window was resolved at', async () => {
    // The client derives the countdown from this, so a missing or non-parseable
    // value would silently push every consumer back onto the browser clock.
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-15T12:00:00Z'));

    const body = await (await GET()).json();

    expect(body.serverTime).toBe('2026-09-15T12:00:00.000Z');
  });

  it('announces the price increase, never a lower one', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-15T12:00:00Z'));

    const body = await (await GET()).json();

    expect(body.event?.announcedBasePrices).toEqual({ starter: 19, pro: 45, team: 89 });
  });

  it('allows only a short shared cache, so a window can close on its own', async () => {
    // Anything long-lived here would keep serving "open" after the deadline passed.
    const res = await GET();

    expect(res.headers.get('Cache-Control')).toBe('public, max-age=60, s-maxage=60');
  });
});
