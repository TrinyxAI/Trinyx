/**
 * @vitest-environment jsdom
 *
 * Two things the step list gets wrong the moment it is driven by a LIVE run,
 * both invisible in a static render:
 *
 *  1. the per-epoch view is refreshed from REST as the stream pushes new steps.
 *     A plain debounce is reset by every push, so on a run pushing several
 *     batches a second - the only kind that needs refreshing - the trailing edge
 *     never arrives and the panel freezes on the snapshot taken when the epoch
 *     was selected. It has to be a THROTTLE.
 *  2. a timer scheduled for epoch N must not fire after the user moved to epoch
 *     M: it would load N's rows under M's header, and the generation guard
 *     cannot catch it because the stale call is the newest one.
 *
 * Plus the "nothing ran yet" node preview, which reads a module store React
 * cannot see: it has to subscribe, or a panel that mounted before the canvas
 * published stays empty for good.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const getEpochAggregatedSteps = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getEpochAggregatedSteps } }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getShowcaseAggregatedSteps: vi.fn() },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));
vi.mock('@/components/workflow/StepRowActions', () => ({ StepRowActions: () => null }));
vi.mock('./../EpochSelector', () => ({ EpochSelector: () => null }));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({
  getIconSlug: () => 'x',
  NodeIcon: () => null,
  // The no-icon placeholder reads its corner from here. Its real value is
  // pinned in nodeIconShape.test.tsx; this file is about the refresh cycle.
  nodeIconRadiusClass: () => 'rounded-md',
}));

import { RunStepsPanel } from '@/components/workflow/run-panel/RunStepsPanel';
import {
  clearCanvasNodes,
  setCanvasNodes,
} from '@/app/workflows/builder/services/canvasNodesStore';

const RUN = { runId: 'run-1' };
const EPOCHS = [{ epoch: 1, startedAt: '2026-01-01T00:00:00Z', endedAt: null }];

function renderPanel(props: Record<string, unknown> = {}) {
  return render(
    <RunStepsPanel
      currentRunInfo={RUN}
      streamedSteps={[]}
      epochTimestamps={EPOCHS}
      selectedEpoch={1}
      onSelectEpoch={vi.fn()}
      workflowId="wf-1"
      {...props}
    />,
  );
}

beforeEach(() => {
  vi.useFakeTimers();
  getEpochAggregatedSteps.mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
  getEpochAggregatedSteps.mockReset();
  clearCanvasNodes();
  cleanup();
});

describe('RunStepsPanel - refreshing a per-epoch view under a live stream', () => {
  it('keeps refreshing while pushes keep arriving (a debounce would starve here)', () => {
    const { rerender } = renderPanel();
    getEpochAggregatedSteps.mockClear(); // ignore the mount fetch

    // 30 pushes, 100 ms apart: a debounce reset by each push fires ZERO times.
    for (let i = 1; i <= 30; i += 1) {
      rerender(
        <RunStepsPanel
          currentRunInfo={RUN}
          streamedSteps={[{ alias: `s${i}`, status: 'running', startTime: null, endTime: null }]}
          epochTimestamps={EPOCHS}
          selectedEpoch={1}
          onSelectEpoch={vi.fn()}
          workflowId="wf-1"
        />,
      );
      act(() => { vi.advanceTimersByTime(100); });
    }

    // 3 s of stream at ~1 refresh/s: several calls, and far fewer than 30.
    const calls = getEpochAggregatedSteps.mock.calls.length;
    expect(calls).toBeGreaterThan(1);
    expect(calls).toBeLessThan(10);
  });

  it('never loads the previous epoch under the new epoch header', () => {
    const { rerender } = renderPanel({
      streamedSteps: [{ alias: 's1', status: 'running', startTime: null, endTime: null }],
    });
    getEpochAggregatedSteps.mockClear();

    // Switch epochs BEFORE the pending stream-driven refresh for epoch 1 fires.
    rerender(
      <RunStepsPanel
        currentRunInfo={RUN}
        streamedSteps={[{ alias: 's1', status: 'running', startTime: null, endTime: null }]}
        epochTimestamps={EPOCHS}
        selectedEpoch={2}
        onSelectEpoch={vi.fn()}
        workflowId="wf-1"
      />,
    );
    act(() => { vi.advanceTimersByTime(5000); });

    const epochsFetched = getEpochAggregatedSteps.mock.calls.map(c => c[1]);
    expect(epochsFetched).toContain(2);
    expect(epochsFetched).not.toContain(1);
  });
});

describe('RunStepsPanel - node preview before anything has run', () => {
  it('picks up the canvas nodes published AFTER it mounted', () => {
    // The panel can render first (side panel opened straight on the Run tab):
    // reading the module store once would leave it empty for the whole session.
    renderPanel({ selectedEpoch: null, streamedSteps: [] });
    expect(screen.queryByText('Fetch orders')).toBeNull();

    act(() => {
      setCanvasNodes(
        [{ id: 'n1', position: { x: 0, y: 0 }, data: { id: 'mcp:fetch', label: 'Fetch orders' } } as never],
        'wf-1',
      );
    });

    expect(screen.getByText('Fetch orders')).toBeTruthy();
  });
});
