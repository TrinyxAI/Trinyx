/**
 * @vitest-environment jsdom
 *
 * The history's job is to tell runs apart at a glance, so the status it prints
 * has to be the one the backend reported. The panel this replaced mapped six of
 * the eleven RunStatus values and defaulted the rest to "pending", which is how a
 * live reusable run - resting in WAITING_TRIGGER between two fires - showed up in
 * its own history as a pending amber clock, and a run blocked on an approval read
 * as "not started yet".
 *
 * Statuses are listed here EXPLICITLY, transcribed from the backend enum. Reading
 * them off the implementation's own map is what let four of them go missing.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

const getWorkflowRuns = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  // Return the KEY so a missing translation is visible as `status.x` rather than
  // silently rendering the English fallback.
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

// jsdom has no IntersectionObserver; the list installs one for infinite scroll.
class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
}
(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = NoopObserver;

/** Every value of the backend RunStatus enum, plus the legacy streaming one. */
const BACKEND_STATUSES = [
  'PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'PARTIAL_SUCCESS',
  'SKIPPED', 'CANCELLED', 'TIMEOUT', 'WAITING_TRIGGER', 'AWAITING_SIGNAL',
  'STOPPED',
];

afterEach(() => { getWorkflowRuns.mockReset(); cleanup(); });

describe('RunHistoryList - status coverage', () => {
  it('labels every backend status with its own translated name, never "pending"', async () => {
    getWorkflowRuns.mockResolvedValue(
      BACKEND_STATUSES.map((status, i) => ({ id: `r${i}`, runId: `r${i}`, status, startedAt: '2026-01-01T00:00:00Z' })),
    );

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(screen.getAllByRole('button').length).toBeGreaterThan(0));
    for (const status of BACKEND_STATUSES) {
      const key = `status.${status.toLowerCase()}`;
      expect(screen.queryAllByText(key).length, `${status} rendered as something else`).toBeGreaterThan(0);
    }
  });

  it('has a real translation for every status it can print', () => {
    // A key that only exists in the code renders as the raw key in production.
    for (const status of BACKEND_STATUSES) {
      const key = status.toLowerCase();
      const statusMessages = (messages as unknown as { status: Record<string, string> }).status;
      expect(statusMessages[key], `status.${key} is missing`).toBeTruthy();
    }
  });

  it('falls back to pending only for a status nobody knows', async () => {
    getWorkflowRuns.mockResolvedValue([{ id: 'r1', runId: 'r1', status: 'WAT', startedAt: '2026-01-01T00:00:00Z' }]);
    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.queryAllByText('status.pending').length).toBe(1));
  });
});
