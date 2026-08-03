/**
 * @vitest-environment jsdom
 *
 * WAITING_TRIGGER is the state a reusable run RESTS in between two fires: it is
 * armed, nothing is executing. The history rendered it exactly like RUNNING -
 * same blue, same pulsing dot - so a workflow that had been idle for days read as
 * busy, and the two states were impossible to tell apart in the list.
 *
 * It now takes the idle amber the run bar already gives it (getStatusClasses
 * defaults WAITING_TRIGGER to yellow), and the pulse stays with the one status
 * that means "work is happening right now".
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

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

class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
}
(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = NoopObserver;

afterEach(() => { getWorkflowRuns.mockReset(); cleanup(); });

/** The row whose status label is `status.<status>`. */
async function rowFor(status: string): Promise<HTMLElement> {
  const label = await screen.findByText(`status.${status}`);
  return label.closest('[data-run-history-row]') as HTMLElement;
}

describe('RunHistoryList - a resting run is not a running one', () => {
  it('gives WAITING_TRIGGER the idle amber, never the running blue', async () => {
    getWorkflowRuns.mockResolvedValue([
      { id: 'r1', runId: 'r1', status: 'WAITING_TRIGGER', startedAt: '2026-01-01T00:00:00Z' },
    ]);

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);

    const row = await rowFor('waiting_trigger');
    // The status colour lives on both the icon slot and the label.
    const coloured = row.querySelector('.text-amber-500');
    expect(coloured, 'waiting_trigger should be amber').toBeTruthy();
    expect(row.querySelector('.text-blue-500'), 'waiting_trigger must not be blue').toBeNull();
    // The label is the state itself, not the "pending" the old six-value map
    // collapsed everything unknown into.
    expect(row.textContent).toContain('status.waiting_trigger');
  });

  it('does not pulse a resting run', async () => {
    getWorkflowRuns.mockResolvedValue([
      { id: 'r1', runId: 'r1', status: 'WAITING_TRIGGER', startedAt: '2026-01-01T00:00:00Z' },
    ]);

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);

    const row = await rowFor('waiting_trigger');
    expect(row.querySelector('.animate-ping'), 'the pulse means work is happening now').toBeNull();
  });

  it('still pulses a RUNNING run, in blue', async () => {
    getWorkflowRuns.mockResolvedValue([
      { id: 'r1', runId: 'r1', status: 'RUNNING', startedAt: '2026-01-01T00:00:00Z' },
    ]);

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);

    const row = await rowFor('running');
    expect(row.querySelector('.animate-ping')).toBeTruthy();
    expect(row.querySelector('.text-blue-500')).toBeTruthy();
  });

  it('keeps the two statuses visually distinct when both are in the list', async () => {
    getWorkflowRuns.mockResolvedValue([
      { id: 'r1', runId: 'r1', status: 'RUNNING', startedAt: '2026-01-01T00:00:00Z' },
      { id: 'r2', runId: 'r2', status: 'WAITING_TRIGGER', startedAt: '2026-01-01T00:00:00Z' },
    ]);

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(screen.getAllByText(/^status\./).length).toBe(2));
    const running = await rowFor('running');
    const waiting = await rowFor('waiting_trigger');
    expect(running.querySelector('.animate-ping')).toBeTruthy();
    expect(waiting.querySelector('.animate-ping')).toBeNull();
  });
});
