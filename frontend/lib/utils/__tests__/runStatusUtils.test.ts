import { describe, expect, it } from 'vitest';
import { deriveBadgeCycleResult, getRunDisplayStatus, getRunStatusLabel, getStatusClasses, resolveBadgeCycleResult } from '../runStatusUtils';

describe('runStatusUtils', () => {
  describe('getStatusClasses - partial success is amber/orange', () => {
    it('maps PARTIAL_SUCCESS (uppercase run status) to amber, not the yellow default', () => {
      const cls = getStatusClasses('PARTIAL_SUCCESS');
      expect(cls).toContain('amber');
      expect(cls).not.toContain('yellow');
      expect(cls).not.toContain('red');
    });

    it('maps the lowercase enum value partial_success to amber too', () => {
      expect(getStatusClasses('partial_success')).toContain('amber');
    });

    it('keeps COMPLETED green, FAILED red, and the idle default yellow', () => {
      expect(getStatusClasses('COMPLETED')).toContain('emerald');
      expect(getStatusClasses('FAILED')).toContain('red');
      // WAITING_TRIGGER has no explicit case -> idle yellow (unchanged behavior).
      expect(getStatusClasses('WAITING_TRIGGER')).toContain('yellow');
    });

    it('distinguishes partial success from plain failed (amber vs red)', () => {
      expect(getStatusClasses('PARTIAL_SUCCESS')).not.toEqual(getStatusClasses('FAILED'));
    });
  });

  describe('getRunDisplayStatus - surfaces the cycle result on WAITING_TRIGGER', () => {
    it('shows partial_success (uppercased) when a reusable-trigger run rests with that lastCycleResult', () => {
      expect(getRunDisplayStatus('WAITING_TRIGGER', { lastCycleResult: 'partial_success' })).toBe('PARTIAL_SUCCESS');
    });

    it('falls back to the raw status when no lastCycleResult is present', () => {
      expect(getRunDisplayStatus('WAITING_TRIGGER', {})).toBe('WAITING_TRIGGER');
      expect(getRunDisplayStatus('PAUSED', null)).toBe('PAUSED');
    });
  });

  describe('deriveBadgeCycleResult - outcome for a WAITING_TRIGGER badge', () => {
    it('mix of a failed node and a completed node -> failed, NOT partial', () => {
      // Rewritten with the rule, not adapted to it: this asserted 'partial_success' while the
      // backend wrote 'failed' for the same cycle, so the canvas badge and the run-history row
      // contradicted each other on one run. partial_success is a NODE verdict - a node
      // accumulates items and can be half-done; a cycle either did the job or did not.
      expect(deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler', 'core:wait'], true))
        .toBe('failed');
    });

    it('only the trigger completed (everything else failed) -> failed, NOT partial', () => {
      // The trigger always completes; it must not make an all-failed cycle look partial.
      expect(deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler'], true)).toBe('failed');
    });

    it('non-trigger completions, no failures -> completed', () => {
      expect(deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler', 'core:wait'], false))
        .toBe('completed');
    });

    it('lowercase run status is handled', () => {
      expect(deriveBadgeCycleResult('waiting_trigger', ['core:wait'], true)).toBe('failed');
    });

    it('not WAITING_TRIGGER (e.g. PAUSED mid-step) -> undefined (keep raw status)', () => {
      expect(deriveBadgeCycleResult('PAUSED', ['core:wait'], true)).toBeUndefined();
    });

    it('empty cycle (nothing ran) -> undefined', () => {
      expect(deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler'], false)).toBeUndefined();
    });
  });

  describe('getRunStatusLabel - localizes the badge status (no hardcoded English)', () => {
    const t = (key: string) => {
      const map: Record<string, string> = {
        'status.completed': 'Terminé',
        'status.running': 'En cours',
        'status.paused': 'En pause',
        'status.waiting_trigger': 'En attente de déclencheur',
      };
      return map[key] ?? `MISSING:${key}`;
    };

    it('translates a known status through the status.<key> namespace', () => {
      expect(getRunStatusLabel('COMPLETED', t)).toBe('Terminé');
      expect(getRunStatusLabel('RUNNING', t)).toBe('En cours');
      expect(getRunStatusLabel('PAUSED', t)).toBe('En pause');
      expect(getRunStatusLabel('WAITING_TRIGGER', t)).toBe('En attente de déclencheur');
    });

    it('regression: a known status is NOT the raw lowercased enum the badge used to print', () => {
      expect(getRunStatusLabel('COMPLETED', t)).not.toBe('completed');
    });

    it('falls back to the lowercased raw status for an unknown value (no missing-key placeholder)', () => {
      expect(getRunStatusLabel('SOME_NEW_STATE', t)).toBe('some_new_state');
      expect(getRunStatusLabel('SOME_NEW_STATE', t)).not.toContain('MISSING');
    });
  });

  describe('end-to-end: the badge chain', () => {
    it('a stored partial_success still renders amber, for runs that finished before the rule', () => {
      // Runs already in the database carry partial_success in their metadata. We stopped
      // PRODUCING it; every reader must keep displaying it, or their history reads wrong.
      const display = getRunDisplayStatus('WAITING_TRIGGER', { lastCycleResult: 'partial_success' });
      expect(getStatusClasses(display)).toContain('amber');
    });

    it('derive -> display -> colour chain yields RED for a mixed cycle', () => {
      const cycle = deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler', 'core:wait'], true);
      const display = getRunDisplayStatus('WAITING_TRIGGER', { lastCycleResult: cycle });
      expect(display).toBe('FAILED');
      expect(getStatusClasses(display)).toContain('red');
    });

    it('derive -> display -> colour chain yields GREEN for a clean cycle', () => {
      const cycle = deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler', 'core:wait'], false);
      const display = getRunDisplayStatus('WAITING_TRIGGER', { lastCycleResult: cycle });
      expect(display).toBe('COMPLETED');
      expect(getStatusClasses(display)).toContain('emerald');
    });

    it('a launched run whose trigger has not fired keeps the idle status', () => {
      const cycle = deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler'], false);
      expect(cycle).toBeUndefined();
      expect(getRunDisplayStatus('WAITING_TRIGGER', cycle ? { lastCycleResult: cycle } : {}))
        .toBe('WAITING_TRIGGER');
    });
  });

  describe('resolveBadgeCycleResult', () => {
    it('takes the backend verdict even when the local sets say the opposite', () => {
      // The case no client-side derivation can get right: the node that failed in an earlier
      // epoch is bucketed as completed (so it keeps its rerun button), so `hasFailed` is false
      // here and the local rule would answer "completed" for a cycle the backend recorded as
      // failed. Two badges on the same run, opposite colours.
      expect(resolveBadgeCycleResult('failed', 'WAITING_TRIGGER', ['trigger:s', 'core:x'], false))
        .toBe('failed');
    });

    it('takes the backend verdict when it says completed', () => {
      expect(resolveBadgeCycleResult('completed', 'WAITING_TRIGGER', ['trigger:s', 'core:x'], true))
        .toBe('completed');
    });

    it('falls back to the local derivation only when the backend said nothing', () => {
      // Older run payloads predate the field. The fallback must still work for them.
      expect(resolveBadgeCycleResult(undefined, 'WAITING_TRIGGER', ['trigger:s', 'core:x'], true))
        .toBe('failed');
      expect(resolveBadgeCycleResult(undefined, 'WAITING_TRIGGER', ['trigger:s', 'core:x'], false))
        .toBe('completed');
    });

    it('leaves an armed-but-unfired run with no outcome to borrow', () => {
      expect(resolveBadgeCycleResult(undefined, 'WAITING_TRIGGER', ['trigger:s'], false))
        .toBeUndefined();
    });

    it('ignores a stale verdict on a run that is not resting between fires', () => {
      // A RUNNING run still carries the PREVIOUS cycle's verdict in its metadata. Showing it would
      // badge a live run with the last cycle's outcome instead of "running".
      expect(resolveBadgeCycleResult('failed', 'RUNNING', ['trigger:s', 'core:x'], false))
        .toBeUndefined();
      expect(resolveBadgeCycleResult('completed', 'PAUSED', ['trigger:s', 'core:x'], false))
        .toBeUndefined();
    });
  });
});
