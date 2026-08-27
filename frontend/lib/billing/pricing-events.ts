/**
 * Event-driven pricing announcements.
 *
 * An event NEVER changes what a customer is charged. `BASE_PRICES` / `CREDIT_COSTS` in
 * `pricing-constants.ts` stay the single source of truth for the billed amount, and the
 * Stripe price IDs behind them are untouched by anything in this file. An event only
 * declares the HIGHER price a plan moves to once the campaign ends.
 *
 * That higher figure is rendered beside the real price, struck through (see
 * ReferencePrice), with a dated caption saying the current price is a founding price and
 * is kept (see FoundingPriceNote). The figure shown LARGE is always the one actually
 * billed; only the future figure is ever struck.
 *
 * ## Campaign vs guarantee window
 *
 * An event has two horizons. `startsAt`/`endsAt` bound the CAMPAIGN, the date after which
 * the announced price applies. `renewEveryDays` slices that campaign into short GUARANTEE
 * WINDOWS: what a visitor is told is that the founding price holds until the end of the
 * window currently running, which is days away rather than months.
 *
 * The distinction is what keeps the short deadline truthful. The caption promises the
 * price is guaranteed until `windowEndsAt`, not that it disappears then. Renewing the
 * window is a real decision taken at each boundary, so a visitor reading "guaranteed until
 * the 1st" is told exactly what is being committed to, and no more.
 *
 * Two conditions keep the whole thing honest, and both belong to the operator:
 *
 *  1. The announced price must be one you genuinely intend to charge after the campaign.
 *     A struck price that never comes into effect is a fictitious reference price under
 *     the EU Omnibus directive (enforced in France by the DGCCRF).
 *  2. The campaign must actually end on `endsAt`. Guarantee windows may renew inside it,
 *     but pushing `endsAt` forward indefinitely turns the announcement into a permanent
 *     discount claim, which is the same problem wearing a countdown.
 *
 * `resolveActivePricingEvent` is deliberately the only way a window opens: it is resolved
 * server-side, it rolls the guarantee window on its own, and it stops at `endsAt` with no
 * deploy needed.
 *
 * Adding an event = one entry in `PRICING_EVENTS` + its label keys in all six locale
 * files. No other code change.
 */

import { priceFromBase } from './pricing-constants';

const MS_PER_DAY = 86_400_000;

export interface PricingEvent {
  /** Stable id, used in logs and tests to name the campaign. */
  id: string;
  /** ISO-8601 UTC instant the campaign opens (inclusive). */
  startsAt: string;
  /** ISO-8601 UTC instant the campaign closes (inclusive). */
  endsAt: string;
  /**
   * Length in days of each guarantee window inside the campaign. Omit (or 0) for a single
   * window running the whole campaign, in which case `windowEndsAt` equals `endsAt`.
   */
  renewEveryDays?: number;
  /**
   * Base plan prices announced for AFTER the campaign, keyed by the same plan ids as
   * `BASE_PRICES`. A plan absent here has no announcement and renders unchanged.
   * Every value must be strictly above the current base price: an event exists to
   * announce an increase, never to advertise a cut.
   */
  announcedBasePrices: Record<string, number>;
  /**
   * True when subscribing inside the campaign keeps the current price for as long as the
   * subscription stays active. This is a real promise: existing Stripe subscriptions keep
   * the price they were created on, so a later increase only applies to new subscriptions.
   */
  locksPrice: boolean;
}

/** A campaign that is open right now, together with the guarantee window it is in. */
export interface ResolvedPricingEvent extends PricingEvent {
  /**
   * End of the guarantee window currently running, ISO-8601 UTC. This is the date shown
   * to visitors. Never past `endsAt`: the last window is truncated to the campaign end
   * rather than promising a date beyond it.
   */
  windowEndsAt: string;
}

/**
 * The declared campaigns. Campaigns must not overlap; `pricing-events.test.ts` pins that,
 * along with every announced price being above the current one.
 */
export const PRICING_EVENTS: PricingEvent[] = [
  {
    id: 'founding-2026',
    startsAt: '2026-08-25T00:00:00.000Z',
    endsAt: '2027-01-01T23:59:59.999Z',
    renewEveryDays: 7,
    announcedBasePrices: { starter: 19, pro: 45, team: 89 },
    locksPrice: true,
  },
];

/**
 * End of the guarantee window containing `now`, clamped to the campaign end.
 *
 * Boundaries belong to the window that is closing: at exactly `startsAt + k*step` the
 * answer is the NEXT boundary, so the date shown is always in the future and a visitor
 * never sees a deadline that has already passed.
 */
function currentWindowEnd(event: PricingEvent, now: Date): string {
  const start = Date.parse(event.startsAt);
  const end = Date.parse(event.endsAt);
  const days = event.renewEveryDays ?? 0;

  if (!Number.isFinite(days) || days <= 0) return event.endsAt;

  const step = days * MS_PER_DAY;
  const elapsed = Math.max(0, now.getTime() - start);
  const boundary = start + (Math.floor(elapsed / step) + 1) * step;

  return new Date(Math.min(boundary, end)).toISOString();
}

/**
 * The campaign whose window contains `now`, with its current guarantee window, or null
 * outside every campaign.
 *
 * Campaigns are declared non-overlapping; if two ever do overlap, the one closing soonest
 * wins so the displayed deadline is always the next real one.
 */
export function resolveActivePricingEvent(
  now: Date = new Date(),
  events: PricingEvent[] = PRICING_EVENTS
): ResolvedPricingEvent | null {
  const t = now.getTime();
  const active = events
    .filter((e) => {
      const from = Date.parse(e.startsAt);
      const to = Date.parse(e.endsAt);
      return Number.isFinite(from) && Number.isFinite(to) && t >= from && t <= to;
    })
    .sort((a, b) => Date.parse(a.endsAt) - Date.parse(b.endsAt));

  const event = active[0];
  if (!event) return null;

  return { ...event, windowEndsAt: currentWindowEnd(event, now) };
}

/**
 * The monthly-equivalent price this plan is announced to move to after the campaign,
 * or null when the event announces nothing for it.
 *
 * Composed through `priceFromBase`, the same helper `calcPrice` uses, so the announced
 * price and the billed price can never disagree about the yearly discount or about how
 * the credit pack is added on top.
 */
export function announcedPrice(
  planId: string,
  cycle: 'monthly' | 'yearly',
  creditTierIndex: number,
  event: PricingEvent | null
): number | null {
  if (!event) return null;
  const announcedBase = event.announcedBasePrices[planId];
  if (announcedBase === undefined) return null;
  return priceFromBase(announcedBase, planId, cycle, creditTierIndex);
}

/**
 * Whether a payload that crossed the network is safe to render.
 *
 * The browser caches `GET /api/pricing-event` briefly, so right after a deploy that
 * changes the payload shape a page can be handed the PREVIOUS shape. Formatting a missing
 * or unparseable date throws a RangeError, and because the caption renders inside the plan
 * card, that throw takes the whole card down through the error boundary.
 *
 * Validating once here, at the boundary where untrusted data enters, keeps every consumer
 * free of defensive checks: an unusable payload simply reads as "no window open", which
 * every price surface already handles by showing the plain current price.
 */
export function isRenderablePricingEvent(event: unknown): event is ResolvedPricingEvent {
  if (!event || typeof event !== 'object') return false;
  const e = event as Partial<ResolvedPricingEvent>;
  return (
    typeof e.id === 'string' &&
    Number.isFinite(Date.parse(e.startsAt ?? '')) &&
    Number.isFinite(Date.parse(e.endsAt ?? '')) &&
    Number.isFinite(Date.parse(e.windowEndsAt ?? '')) &&
    !!e.announcedBasePrices &&
    typeof e.announcedBasePrices === 'object'
  );
}

/**
 * The current guarantee window's closing date in the APP locale (never the browser
 * locale, never a hardcoded one). Rendered without a UTC suffix: this is marketing copy,
 * not a log timestamp, so `formatUtcDate` from dateFormatters would read wrong here.
 *
 * Read in UTC, because the window closes on a UTC instant: formatting in the viewer zone
 * would advertise a deadline one day off the one actually enforced.
 *
 * The 'short' style is for plan cards, where the long month name pushes the caption onto
 * a third line and crowds the price it is there to qualify.
 */
export function formatEventDeadline(
  event: ResolvedPricingEvent,
  locale: string,
  style: 'long' | 'short' = 'long'
): string {
  return new Intl.DateTimeFormat(locale, {
    timeZone: 'UTC',
    day: 'numeric',
    month: style === 'short' ? 'short' : 'long',
    year: 'numeric',
  }).format(new Date(event.windowEndsAt));
}
