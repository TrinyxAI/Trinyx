'use client';

import { useSyncExternalStore } from 'react';
import { isRenderablePricingEvent, type ResolvedPricingEvent } from '@/lib/billing/pricing-events';

export interface UsePricingEventResult {
  /** The open pricing event, or null when no window is open (and while loading). */
  event: ResolvedPricingEvent | null;
  /**
   * Server time the window was resolved at. Consumers derive the remaining-time copy
   * from this, never from the browser clock, which can be days off.
   */
  serverTime: Date | null;
  isLoading: boolean;
}

interface PricingEventPayload {
  event: ResolvedPricingEvent | null;
  serverTime: string;
}

/**
 * Module-level store rather than a React Query subscription, for two reasons.
 *
 * The announcement is read by components mounted in very different trees (the public
 * landing, the settings pricing page, the insufficient-credits and insufficient-storage
 * modals). Going through `useQuery` would make each of them require a
 * `QueryClientProvider` above it, which turns a decorative price note into a hard
 * structural dependency of a billing modal. It would also open one subscription per
 * consumer for a value that is identical for everyone and changes on a scale of weeks.
 *
 * A single in-flight fetch, shared by every consumer, is both lighter and honest about
 * what this is: one global, slowly-changing value.
 */
const LOADING: UsePricingEventResult = { event: null, serverTime: null, isLoading: true };

let snapshot: UsePricingEventResult = LOADING;
let started = false;
const listeners = new Set<() => void>();

function publish(next: UsePricingEventResult): void {
  snapshot = next;
  listeners.forEach((notify) => notify());
}

function startFetchOnce(): void {
  if (started || typeof window === 'undefined') return;
  started = true;

  fetch('/api/pricing-event')
    .then((res) => (res.ok ? res.json() : Promise.reject(new Error(`pricing-event ${res.status}`))))
    .then((payload: PricingEventPayload) => {
      publish({
        // A payload cached from before a payload-shape change is dropped rather than
        // rendered: see isRenderablePricingEvent.
        event: isRenderablePricingEvent(payload.event) ? payload.event : null,
        serverTime: payload.serverTime ? new Date(payload.serverTime) : null,
        isLoading: false,
      });
    })
    .catch(() => {
      // Degrade to "no event": every consumer then renders the plain current price.
      // The announcement is additive, so a failed fetch must never break a price card.
      publish({ event: null, serverTime: null, isLoading: false });
    });
}

function subscribe(onStoreChange: () => void): () => void {
  listeners.add(onStoreChange);
  startFetchOnce();
  return () => {
    listeners.delete(onStoreChange);
  };
}

/**
 * The open pricing event, resolved against the SERVER clock via `GET /api/pricing-event`.
 *
 * Unauthenticated by design: the landing renders price cards before any session exists.
 * The server snapshot is deliberately the loading one, so the server HTML carries no
 * announcement and the first client render agrees with it (no hydration mismatch); the
 * note appears once the window has been confirmed by the server.
 */
export function usePricingEvent(): UsePricingEventResult {
  return useSyncExternalStore(
    subscribe,
    () => snapshot,
    () => LOADING
  );
}

/** Test seam: drops the cached window and lets the next consumer fetch again. */
export function __resetPricingEventStoreForTests(): void {
  snapshot = LOADING;
  started = false;
  listeners.clear();
}
