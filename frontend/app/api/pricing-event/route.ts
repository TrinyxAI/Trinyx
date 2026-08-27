import { NextResponse } from 'next/server';
import { resolveActivePricingEvent } from '@/lib/billing/pricing-events';

/**
 * The pricing event currently open, resolved against the SERVER clock.
 *
 * Every price surface (landing, settings pricing, the insufficient-credits and
 * insufficient-storage modals) reads the window from here rather than from
 * `new Date()` in the browser, for two reasons: a visitor's clock can be days off,
 * and resolving the window during render would make the server HTML and the first
 * client render disagree around the boundary (a hydration mismatch, the same class
 * of bug the landing already hit on locale-formatted numbers).
 *
 * No authentication: it returns only the published price announcement, which is
 * marketing copy every visitor is meant to see, and the landing needs it before
 * there is any session.
 */

// Resolved per request. Prerendering it would freeze "is the window open" into the
// build output, so a window would open or close only on the next deploy.
export const dynamic = 'force-dynamic';

export interface PricingEventResponse {
  /** The open event, or null when no window is open right now. */
  event: ReturnType<typeof resolveActivePricingEvent>;
  /** Server time the window was resolved at, ISO-8601 UTC. Drives the countdown. */
  serverTime: string;
}

export async function GET() {
  const now = new Date();
  const body: PricingEventResponse = {
    event: resolveActivePricingEvent(now),
    serverTime: now.toISOString(),
  };
  // Short shared cache: the payload only changes when a window opens or closes, and a
  // minute of staleness on a window measured in weeks is invisible. Keeps the landing
  // from re-resolving per visitor while still letting the window close on its own.
  return NextResponse.json(body, {
    headers: { 'Cache-Control': 'public, max-age=60, s-maxage=60' },
  });
}
