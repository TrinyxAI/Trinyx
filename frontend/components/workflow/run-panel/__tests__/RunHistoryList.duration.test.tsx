/**
 * @vitest-environment jsdom
 *
 * The history's last column is the duration, and it read "-" on every row.
 *
 * Not a formatting slip: a reusable run never ends. It rests in WAITING_TRIGGER
 * between fires and accumulates epochs, so it has neither `durationMs` nor
 * `endedAt`, and the formatter answers a literal "-" when it cannot measure.
 * Since runs aggregate instead of forking, that is the state of MOST rows.
 *
 * The figure that answers "how slow is this workflow" is the duration of the last
 * CLOSED epoch, which the backend now returns per run. Two lookalikes are traps
 * and are pinned as such below:
 *   - the whole-run span, because `cancelStaleRuns` stamps `endedAt` on resting
 *     runs, turning a week of daily fires into a "7d" duration;
 *   - `lastCycleAt - lastFireAt`, because concurrent epochs across trigger DAGs
 *     let those two timestamps describe different epochs.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

const getWorkflowRuns = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => (ns ? `${ns}.${key}` : key),
}));
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getWorkflowRuns,
    listVersions: vi.fn().mockResolvedValue({ pinnedVersion: null }),
    getPinnedWorkflowRun: vi.fn().mockResolvedValue(null),
  },
}));

import { RunHistoryList } from '@/components/workflow/run-panel/RunHistoryList';
import messages from '@/messages/en.json';

class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
}
(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = NoopObserver;

const row = () => document.querySelector('[data-run-history-row]');
/** The duration lives in the fixed-width trailing cell. */
const durationCell = () => row()?.querySelector('span.w-14')?.textContent ?? null;
const tooltip = () => row()?.getAttribute('title') ?? '';

async function renderWith(run: Record<string, unknown>) {
  getWorkflowRuns.mockResolvedValue([{ id: 'r1', runId: 'r1', status: 'WAITING_TRIGGER', ...run }]);
  render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
  await waitFor(() => expect(row()).toBeTruthy());
}

afterEach(() => { getWorkflowRuns.mockReset(); cleanup(); });

describe('RunHistoryList - the duration column', () => {
  it('shows the last CLOSED epoch duration for a run that never ends', async () => {
    await renderWith({ startedAt: '2026-08-01T10:00:00Z', lastEpochDurationMs: 7000 });
    expect(durationCell()).toBe('7.0s');
  });

  it('prefers the epoch figure over the run lifetime of a stale-cancelled run', async () => {
    // `cancelStaleRuns` sets endedAt on every resting run when a new one is
    // created, so this run has a perfectly parseable 7-DAY span. Showing it in a
    // column headed "duration" is worse than the dash it replaced: it looks right.
    await renderWith({
      status: 'CANCELLED',
      startedAt: '2026-07-25T10:00:00Z',
      endedAt: '2026-08-01T10:00:00Z',
      currentEpoch: 5,
      lastEpochDurationMs: 7000,
    });
    expect(durationCell()).toBe('7.0s');
  });

  it('ignores lastCycleAt/lastFireAt entirely - they can describe different epochs', async () => {
    // Concurrent epochs across trigger DAGs: trigger B fires at 12:00 and is still
    // running when trigger A's epoch closes at 12:05. The subtraction is positive
    // and completely wrong, so the column must not be computed from it.
    await renderWith({
      lastFireAt: '2026-08-01T12:00:00Z',
      metadata: { lastCycleAt: '2026-08-01T12:05:00Z' },
    });
    expect(durationCell()).toBe('');
  });

  it('falls back to the whole run only when it terminated without EVER opening an epoch', async () => {
    // A single-shot step-by-step run: the whole run is the single execution.
    // The run's own `durationMs` is deliberately absent here - nothing in the
    // orchestrator ever writes it, so a fallback built on it would be dead code.
    await renderWith({
      status: 'COMPLETED',
      startedAt: '2026-08-01T12:00:00Z',
      endedAt: '2026-08-01T12:00:42Z',
    });
    expect(durationCell()).toBe('42s');
  });

  it('does NOT use the whole-run span once the run has epochs', async () => {
    // The stale-cancel trap, from the other side: this run HAS fired, so its
    // span is a lifetime. With no closed epoch to report, blank is the honest answer.
    await renderWith({
      status: 'CANCELLED',
      currentEpoch: 3,
      startedAt: '2026-07-25T10:00:00Z',
      endedAt: '2026-08-01T10:00:00Z',
    });
    expect(durationCell()).toBe('');
  });

  it('renders NOTHING rather than a dash when there is nothing to measure', async () => {
    await renderWith({ startedAt: '2026-08-01T10:00:00Z' });
    expect(durationCell()).toBe('');
  });
});

describe('RunHistoryList - what the duration means', () => {
  it('labels an epoch figure as the last execution, not as the run duration', async () => {
    await renderWith({ lastEpochDurationMs: 7000 });
    expect(tooltip()).toContain('runs.lastFireDuration');
    expect(tooltip()).not.toContain('runs.duration');
  });

  it('labels a whole-run figure as the duration', async () => {
    await renderWith({ status: 'COMPLETED', startedAt: '2026-08-01T12:00:00Z', endedAt: '2026-08-01T12:00:42Z' });
    expect(tooltip()).toContain('runs.duration');
    expect(tooltip()).not.toContain('runs.lastFireDuration');
  });

  it('says nothing about duration when there is no figure', async () => {
    await renderWith({});
    expect(tooltip()).not.toContain('runs.duration');
    expect(tooltip()).not.toContain('runs.lastFireDuration');
  });

  it('has a real message behind both labels, distinct from the last-fire TIMESTAMP', async () => {
    // A key that only exists in the code renders as the raw key in production.
    // And the two must not read the same: the tooltip shows the timestamp and the
    // duration side by side, so "Last execution" for both is a riddle.
    const runs = (messages as unknown as { runs: Record<string, string> }).runs;
    expect(runs.duration).toBeTruthy();
    expect(runs.lastFireDuration).toBeTruthy();
    expect(runs.lastFireDuration).not.toBe(runs.lastFire);
  });
});
