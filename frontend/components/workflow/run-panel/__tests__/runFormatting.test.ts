import { describe, expect, it } from 'vitest';
import {
  deriveEffectiveStatus,
  formatCompactDuration,
  isRunStatusActive,
  latestEpoch,
} from '@/components/workflow/run-panel/runFormatting';

describe('formatCompactDuration', () => {
  it('collapses sub-second durations to "<1s"', () => {
    expect(formatCompactDuration(0)).toBe('<1s');
    expect(formatCompactDuration(999)).toBe('<1s');
  });

  it('keeps one decimal below 10s, then rounds to whole seconds', () => {
    expect(formatCompactDuration(1400)).toBe('1.4s');
    expect(formatCompactDuration(42_000)).toBe('42s');
  });

  it('pads the seconds/minutes remainder so the column never jitters', () => {
    expect(formatCompactDuration(187_000)).toBe('3m07s');
    expect(formatCompactDuration(7_500_000)).toBe('2h05m');
  });
});

describe('deriveEffectiveStatus', () => {
  it('returns the raw status when the step has no per-epoch counts', () => {
    expect(deriveEffectiveStatus('completed')).toBe('completed');
    expect(deriveEffectiveStatus('pending', undefined)).toBe('pending');
  });

  it('prioritises in-flight executions over finished ones', () => {
    expect(deriveEffectiveStatus('completed', { completed: 3, running: 1 })).toBe('running');
    expect(deriveEffectiveStatus('completed', { completed: 3, awaitingSignal: 1 })).toBe('awaiting_signal');
  });

  it('reports partial_success when some executions failed and some completed', () => {
    expect(deriveEffectiveStatus('completed', { completed: 2, failed: 1 })).toBe('partial_success');
    expect(deriveEffectiveStatus('failed', { failed: 2 })).toBe('failed');
  });

  it('falls back to skipped only when nothing else happened', () => {
    expect(deriveEffectiveStatus('pending', { skipped: 4 })).toBe('skipped');
    expect(deriveEffectiveStatus('pending', {})).toBe('pending');
  });
});

describe('isRunStatusActive', () => {
  it('treats every terminal status as inactive - including the ones a local copy forgot', () => {
    // Literal list on purpose, transcribed from the BACKEND enum
    // (RunStatus.isTerminal), not from the store: deriving it from a set the
    // implementation itself reads makes the test structurally unable to catch a
    // missing status, which is how first `stopped` and then `skipped` went missing
    // (no Reactivate button, and a fire button the dispatcher refuses).
    // `STOPPED` is not in the enum but still reaches the UI from the streaming layer.
    for (const s of ['COMPLETED', 'SKIPPED', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED', 'TIMEOUT', 'STOPPED']) {
      expect(isRunStatusActive(s)).toBe(false);
    }
  });

  it('stays in sync with the store terminal set (single source of truth)', async () => {
    const { TERMINAL_STATUSES } = await import('@/contexts/workflow-run/RunStateStore');
    for (const s of TERMINAL_STATUSES) {
      expect(isRunStatusActive(s.toUpperCase())).toBe(false);
    }
  });

  it('treats a live run as active, case-insensitively', () => {
    expect(isRunStatusActive('RUNNING')).toBe(true);
    expect(isRunStatusActive('waiting_trigger')).toBe(true);
  });

  it('is false when the status is unknown', () => {
    expect(isRunStatusActive(null)).toBe(false);
    expect(isRunStatusActive('')).toBe(false);
  });
});

describe('latestEpoch', () => {
  it('returns null when the run has no epoch yet', () => {
    expect(latestEpoch([])).toBeNull();
  });

  it('returns the highest epoch regardless of array order', () => {
    const epochs = [
      { epoch: 2, startedAt: '2026-07-31T10:00:00Z', endedAt: null },
      { epoch: 5, startedAt: '2026-07-31T11:00:00Z', endedAt: null },
      { epoch: 1, startedAt: '2026-07-31T09:00:00Z', endedAt: '2026-07-31T09:01:00Z' },
    ];
    expect(latestEpoch(epochs)).toBe(5);
  });
});
