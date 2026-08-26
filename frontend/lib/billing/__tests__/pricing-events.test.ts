import { describe, it, expect } from 'vitest';
import {
  PRICING_EVENTS,
  resolveActivePricingEvent,
  announcedPrice,
  isRenderablePricingEvent,
  formatEventDeadline,
  type PricingEvent,
} from '@/lib/billing/pricing-events';
import { BASE_PRICES, calcPrice } from '@/lib/billing/pricing-constants';

const EVENT: PricingEvent = {
  id: 'test-window',
  startsAt: '2026-03-01T00:00:00.000Z',
  endsAt: '2026-03-31T23:59:59.999Z',
  renewEveryDays: 7,
  announcedBasePrices: { starter: 19, pro: 45, team: 89 },
  locksPrice: true,
};

describe('pricing events', () => {
  describe('billed prices are untouched by the event layer', () => {
    it('keeps every current base price exactly where it was', () => {
      expect(BASE_PRICES).toEqual({ starter: 10, pro: 24, team: 49 });
    });

    it('leaves calcPrice unaffected while a window is open', () => {
      const active = resolveActivePricingEvent(new Date('2026-03-15T12:00:00Z'), [EVENT]);

      expect(active).not.toBeNull();
      expect(calcPrice('starter', 'monthly', 0)).toBe(10);
      expect(calcPrice('pro', 'monthly', 0)).toBe(24);
      expect(calcPrice('team', 'monthly', 0)).toBe(49);
    });
  });

  describe('resolveActivePricingEvent', () => {
    it('returns null before the window opens', () => {
      expect(resolveActivePricingEvent(new Date('2026-02-28T23:59:59Z'), [EVENT])).toBeNull();
    });

    it('returns the event on the opening instant', () => {
      expect(resolveActivePricingEvent(new Date('2026-03-01T00:00:00.000Z'), [EVENT])?.id).toBe('test-window');
    });

    it('returns the event on the closing instant', () => {
      expect(resolveActivePricingEvent(new Date('2026-03-31T23:59:59.999Z'), [EVENT])?.id).toBe('test-window');
    });

    it('returns null one millisecond after the window closes', () => {
      expect(resolveActivePricingEvent(new Date('2026-04-01T00:00:00.000Z'), [EVENT])).toBeNull();
    });

    it('returns null when no events are declared at all', () => {
      expect(resolveActivePricingEvent(new Date('2026-03-15T12:00:00Z'), [])).toBeNull();
    });

    it('prefers the window closing soonest when two somehow overlap', () => {
      const later: PricingEvent = { ...EVENT, id: 'later', endsAt: '2026-06-30T00:00:00.000Z' };

      const active = resolveActivePricingEvent(new Date('2026-03-15T12:00:00Z'), [later, EVENT]);

      expect(active?.id).toBe('test-window');
    });

    it('ignores an event whose dates do not parse', () => {
      const broken: PricingEvent = { ...EVENT, id: 'broken', startsAt: 'not-a-date' };

      expect(resolveActivePricingEvent(new Date('2026-03-15T12:00:00Z'), [broken])).toBeNull();
    });
  });

  describe('announcedPrice', () => {
    it('announces the monthly price composed from the announced base', () => {
      expect(announcedPrice('pro', 'monthly', 0, EVENT)).toBe(45);
    });

    it('applies the yearly base discount to the announced base, like calcPrice does', () => {
      expect(announcedPrice('pro', 'yearly', 0, EVENT)).toBe(36);
    });

    it('adds the same credit pack cost the current price adds', () => {
      const current = calcPrice('pro', 'monthly', 4);
      const future = announcedPrice('pro', 'monthly', 4, EVENT);

      expect(current).toBe(24 + 80);
      expect(future).toBe(45 + 80);
      expect((future as number) - current).toBe(45 - 24);
    });

    it('uses the Team credit table for Team, like calcPrice does', () => {
      expect(announcedPrice('team', 'monthly', 4, EVENT)).toBe(89 + 100);
    });

    it('returns null for a plan the event says nothing about', () => {
      expect(announcedPrice('free', 'monthly', 0, EVENT)).toBeNull();
      expect(announcedPrice('enterprise', 'monthly', 0, EVENT)).toBeNull();
    });

    it('returns null when no window is open', () => {
      expect(announcedPrice('pro', 'monthly', 0, null)).toBeNull();
    });
  });

  describe('formatEventDeadline', () => {
    it('formats the window deadline in the app locale, not the browser locale', () => {
      const active = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [EVENT])!;

      expect(formatEventDeadline(active, 'en')).toBe('March 8, 2026');
      expect(formatEventDeadline(active, 'fr')).toBe('8 mars 2026');
    });

    it('reads the closing date in UTC, so a late-evening local time cannot shift the day', () => {
      const active = resolveActivePricingEvent(new Date('2026-03-25T23:00:00Z'), [EVENT])!;

      // 2026-03-29T00:00Z is already the 29th in UTC and would be the 29th locally too,
      // but formatting in UTC+2 at 23:00 local on the 28th would print the 28th.
      expect(formatEventDeadline(active, 'en')).toBe('March 29, 2026');
    });

    it('shortens the month for plan cards, where a long month costs a third line', () => {
      const active = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [EVENT])!;

      expect(formatEventDeadline(active, 'en', 'short')).toBe('Mar 8, 2026');
    });

    it('keeps the long month by default', () => {
      const active = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [EVENT])!;

      expect(formatEventDeadline(active, 'en')).toBe('March 8, 2026');
      expect(formatEventDeadline(active, 'en', 'long')).toBe('March 8, 2026');
    });
  });

  describe('rolling guarantee window', () => {
    it('closes at the first boundary after the campaign opens', () => {
      const active = resolveActivePricingEvent(new Date('2026-03-01T00:00:00.000Z'), [EVENT]);

      expect(active?.windowEndsAt).toBe('2026-03-08T00:00:00.000Z');
    });

    it('keeps the same deadline for every visitor inside one window', () => {
      const early = resolveActivePricingEvent(new Date('2026-03-02T01:00:00Z'), [EVENT]);
      const late = resolveActivePricingEvent(new Date('2026-03-07T23:00:00Z'), [EVENT]);

      expect(early?.windowEndsAt).toBe(late?.windowEndsAt);
    });

    it('rolls to the next window once a boundary passes', () => {
      const before = resolveActivePricingEvent(new Date('2026-03-07T23:59:00Z'), [EVENT]);
      const after = resolveActivePricingEvent(new Date('2026-03-08T00:01:00Z'), [EVENT]);

      expect(before?.windowEndsAt).toBe('2026-03-08T00:00:00.000Z');
      expect(after?.windowEndsAt).toBe('2026-03-15T00:00:00.000Z');
    });

    it('never shows a deadline that has already passed, even exactly on a boundary', () => {
      // On the boundary instant the window that is closing is over, so the date shown
      // has to be the NEXT one. Flooring without the +1 would print today, already gone.
      const onBoundary = resolveActivePricingEvent(new Date('2026-03-08T00:00:00.000Z'), [EVENT]);

      expect(Date.parse(onBoundary!.windowEndsAt)).toBeGreaterThan(Date.parse('2026-03-08T00:00:00.000Z'));
    });

    it('never promises a date beyond the campaign end', () => {
      // The campaign ends mid-window (Mar 31 is not a multiple of 7 days from Mar 1),
      // so the last window must be truncated rather than advertise April.
      const last = resolveActivePricingEvent(new Date('2026-03-30T12:00:00Z'), [EVENT]);

      expect(last?.windowEndsAt).toBe(EVENT.endsAt);
    });

    it('falls back to the campaign end when no renewal cadence is declared', () => {
      const single = { ...EVENT, renewEveryDays: undefined };

      const active = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [single]);

      expect(active?.windowEndsAt).toBe(EVENT.endsAt);
    });

    it('falls back to the campaign end on a nonsensical cadence', () => {
      // A zero or negative cadence would make the boundary arithmetic divide by zero
      // and hand the card an Invalid Date.
      for (const renewEveryDays of [0, -7, Number.NaN]) {
        const active = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [{ ...EVENT, renewEveryDays }]);

        expect(active?.windowEndsAt).toBe(EVENT.endsAt);
      }
    });

    it('leaves the announced prices untouched as the window rolls', () => {
      // Only the deadline moves. A rolling window that also moved the price would be a
      // different offer each week, not a renewed guarantee on the same one.
      const week1 = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [EVENT]);
      const week3 = resolveActivePricingEvent(new Date('2026-03-16T00:00:00Z'), [EVENT]);

      expect(week1?.announcedBasePrices).toEqual(week3?.announcedBasePrices);
      expect(announcedPrice('pro', 'monthly', 0, week3)).toBe(45);
    });
  });

  describe('isRenderablePricingEvent', () => {
    // Regression: the browser caches the endpoint for a minute, so a page loaded right
    // after a deploy can be handed the PREVIOUS payload shape. Formatting a missing date
    // threw a RangeError inside the plan card and the error boundary blanked the card.
    it('accepts a freshly resolved event', () => {
      const active = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [EVENT]);

      expect(isRenderablePricingEvent(active)).toBe(true);
    });

    it('rejects a payload from before windowEndsAt existed', () => {
      const stale = { ...EVENT };

      expect(isRenderablePricingEvent(stale)).toBe(false);
    });

    it('rejects an unparseable window deadline', () => {
      const broken = { ...EVENT, windowEndsAt: 'not-a-date' };

      expect(isRenderablePricingEvent(broken)).toBe(false);
    });

    it('rejects null, undefined and non-objects', () => {
      for (const value of [null, undefined, 42, 'event', true]) {
        expect(isRenderablePricingEvent(value)).toBe(false);
      }
    });

    it('rejects an event with no announced prices object', () => {
      const active = resolveActivePricingEvent(new Date('2026-03-02T00:00:00Z'), [EVENT])!;

      expect(isRenderablePricingEvent({ ...active, announcedBasePrices: undefined })).toBe(false);
    });
  });

  describe('the founding campaign as declared', () => {
    // These pin the SHIPPED dates, not a fixture: the rolling-window tests above prove the
    // mechanism, and these prove the campaign actually runs where it is meant to.
    const founding = PRICING_EVENTS.find((e) => e.id === 'founding-2026')!;

    it('is open on its first day', () => {
      expect(resolveActivePricingEvent(new Date('2026-08-25T12:00:00Z'), PRICING_EVENTS)?.id).toBe('founding-2026');
    });

    it('is still open the day before it ends', () => {
      expect(resolveActivePricingEvent(new Date('2026-12-31T12:00:00Z'), PRICING_EVENTS)?.id).toBe('founding-2026');
    });

    it('rolls its window on a normal week', () => {
      const before = resolveActivePricingEvent(new Date('2026-11-02T12:00:00Z'), PRICING_EVENTS);
      const after = resolveActivePricingEvent(new Date('2026-11-03T12:00:00Z'), PRICING_EVENTS);

      expect(before?.windowEndsAt).toBe('2026-11-03T00:00:00.000Z');
      expect(after?.windowEndsAt).toBe('2026-11-10T00:00:00.000Z');
    });

    it('truncates its final window to the campaign end', () => {
      // The campaign end does not land on a 7-day boundary (the next one is 5 Jan 2027),
      // so the last window is short. Without the clamp the card would promise a date in
      // 2027 that the campaign never reaches.
      const last = resolveActivePricingEvent(new Date('2026-12-30T12:00:00Z'), PRICING_EVENTS);

      expect(last?.windowEndsAt).toBe(founding.endsAt);
    });

    it('closes once past its end, taking every struck price with it', () => {
      expect(resolveActivePricingEvent(new Date('2027-01-02T00:00:01Z'), PRICING_EVENTS)).toBeNull();
    });
  });

  describe('declared catalogue', () => {
    it('declares every window with parseable, ordered dates', () => {
      for (const e of PRICING_EVENTS) {
        expect(Number.isFinite(Date.parse(e.startsAt))).toBe(true);
        expect(Number.isFinite(Date.parse(e.endsAt))).toBe(true);
        expect(Date.parse(e.endsAt)).toBeGreaterThan(Date.parse(e.startsAt));
      }
    });

    it('declares no overlapping windows', () => {
      const sorted = [...PRICING_EVENTS].sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt));
      for (let i = 1; i < sorted.length; i++) {
        expect(Date.parse(sorted[i].startsAt)).toBeGreaterThan(Date.parse(sorted[i - 1].endsAt));
      }
    });

    it('only ever announces an increase, never a cut', () => {
      for (const e of PRICING_EVENTS) {
        for (const [planId, announced] of Object.entries(e.announcedBasePrices)) {
          const current = BASE_PRICES[planId];
          if (current === undefined) continue;
          expect(announced).toBeGreaterThan(current);
        }
      }
    });

    it('only names plans that are actually sold', () => {
      const sold = new Set(Object.keys(BASE_PRICES));
      for (const e of PRICING_EVENTS) {
        for (const planId of Object.keys(e.announcedBasePrices)) {
          expect(sold.has(planId)).toBe(true);
        }
      }
    });
  });
});
