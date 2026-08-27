/**
 * Two parallel tool calls blocked on the SAME unconnected service raise two cards that the
 * store merges into one, because cards are keyed by the services they name. The merge is
 * therefore the only place that knows both calls exist, and it is what the answer handler
 * reads to release them.
 */
import { describe, it, expect } from 'vitest';
import { mergePendingServiceApprovals, type PendingServiceApproval } from '@/contexts/StreamingContext';

const gmail = { serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' };

function card(gateKeys?: string[], blocking?: boolean): PendingServiceApproval {
  return { services: [gmail], blocking, gateKeys, timestamp: 1 };
}

describe('mergePendingServiceApprovals - held calls', () => {
  it('keeps every held call, not just the newest', () => {
    // Keeping one key would release one call and leave the other holding to its deadline:
    // the user answers, the turn takes minutes anyway, and half the work fails silently.
    const merged = mergePendingServiceApprovals(card(['call-1'], true), card(['call-2'], true));

    expect(merged.gateKeys).toEqual(['call-1', 'call-2']);
    expect(merged.blocking).toBe(true);
  });

  it('does not repeat a key the two cards share', () => {
    const merged = mergePendingServiceApprovals(card(['call-1'], true), card(['call-1'], true));

    expect(merged.gateKeys).toEqual(['call-1']);
  });

  it('stays blocking when only one side holds a call, and keeps that call', () => {
    const merged = mergePendingServiceApprovals(card(undefined, false), card(['call-2'], true));

    expect(merged.blocking).toBe(true);
    expect(merged.gateKeys).toEqual(['call-2']);
  });

  it('reports no held call when neither side has one', () => {
    // undefined rather than [] so the answer handler takes the plain resume path, which is
    // exactly the pre-gate behaviour.
    const merged = mergePendingServiceApprovals(card(), card());

    expect(merged.gateKeys).toBeUndefined();
  });
});
