/**
 * @vitest-environment jsdom
 *
 * The run panel's step list has to read in DAG order - trigger at the top, each
 * branch's last node at the bottom - whatever order the aggregate arrived in.
 *
 * The aggregate is NOT ordered: "all epochs" is WebSocket push order and a single
 * epoch is backend row order, so a workflow with parallel branches, or an epoch
 * that re-fires only part of the graph, listed its nodes shuffled. These render
 * the panel with a deliberately scrambled aggregate and assert the DOM order, on
 * both views (list and waterfall) and on the "nothing ran yet" node preview.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getEpochAggregatedSteps: vi.fn() } }));
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
  // pinned in nodeIconShape.test.tsx; this file is about step ORDER.
  nodeIconRadiusClass: () => 'rounded-md',
}));

import { RunStepsPanel } from '@/components/workflow/run-panel/RunStepsPanel';
import {
  clearCanvasNodes,
  setCanvasEdges,
  setCanvasNodes,
} from '@/app/workflows/builder/services/canvasNodesStore';

const RUN = { runId: 'run-1' };

/**
 * The graph under test - a fork with two branches of DIFFERENT lengths joining
 * on a merge, which is where arrival order and DAG order disagree most:
 *
 *   Start -> Fetch -> Enrich -> Save
 *         -> Notify -----------^
 *
 * `Notify` sits one hop from the trigger but must still render ABOVE `Save`,
 * and `Save` last because it consumes both branches.
 */
const NODES = [
  { id: 'n-start', position: { x: 0, y: 100 }, data: { id: 'trigger:manual', label: 'Start', kind: 'entry' } },
  { id: 'n-fetch', position: { x: 200, y: 0 }, data: { id: 'mcp:fetch', label: 'Fetch' } },
  { id: 'n-enrich', position: { x: 400, y: 0 }, data: { id: 'mcp:enrich', label: 'Enrich' } },
  { id: 'n-notify', position: { x: 200, y: 200 }, data: { id: 'mcp:notify', label: 'Notify' } },
  { id: 'n-save', position: { x: 600, y: 100 }, data: { id: 'mcp:save', label: 'Save' } },
];

const EDGES = [
  { source: 'n-start', target: 'n-fetch' },
  { source: 'n-fetch', target: 'n-enrich' },
  { source: 'n-enrich', target: 'n-save' },
  { source: 'n-start', target: 'n-notify' },
  { source: 'n-notify', target: 'n-save' },
];

const step = (alias: string) => ({
  alias,
  status: 'completed',
  startTime: '2026-01-01T00:00:00Z',
  endTime: '2026-01-01T00:00:01Z',
  executionTimeMs: 1000,
});

/** Aggregate as the stream really hands it over: not in graph order. */
const SCRAMBLED = [step('save'), step('notify'), step('enrich'), step('start'), step('fetch')];

/** Each branch contiguous, then the merge that consumes both. */
const DAG_ORDER = ['Start', 'Fetch', 'Enrich', 'Notify', 'Save'];

function publishCanvas() {
  act(() => {
    setCanvasNodes(NODES as never, 'wf-1');
    setCanvasEdges(EDGES as never, 'wf-1');
  });
}

/** Labels of the rendered step rows, in DOM order. */
function renderedLabels(container: HTMLElement): string[] {
  const known = new Set(DAG_ORDER);
  return [...container.querySelectorAll('span')]
    .map((el) => el.textContent ?? '')
    .filter((text) => known.has(text));
}

function renderPanel(props: Record<string, unknown> = {}) {
  return render(
    <RunStepsPanel
      currentRunInfo={RUN}
      streamedSteps={SCRAMBLED}
      epochTimestamps={[]}
      selectedEpoch={null}
      onSelectEpoch={vi.fn()}
      workflowId="wf-1"
      {...props}
    />,
  );
}

afterEach(() => {
  clearCanvasNodes();
  cleanup();
});

describe('RunStepsPanel - step list follows the DAG', () => {
  it('renders a scrambled aggregate trigger-first, terminal-node-last', () => {
    const { container } = renderPanel();
    publishCanvas();

    expect(renderedLabels(container)).toEqual(DAG_ORDER);
  });

  it('puts a short branch above a longer branch it merges with', () => {
    // The regression the arrival order produced: `Notify` (1 hop) arrived after
    // `Save` and rendered under the node that consumes it.
    const { container } = renderPanel();
    publishCanvas();

    const labels = renderedLabels(container);
    expect(labels.indexOf('Notify')).toBeLessThan(labels.indexOf('Save'));
    expect(labels.indexOf('Enrich')).toBeLessThan(labels.indexOf('Save'));
  });

  it('orders the waterfall view the same way as the list view', () => {
    // Both views render the same `filteredSteps`, so the ordering must not be a
    // property of one renderer.
    const { container, getByTitle } = renderPanel();
    publishCanvas();

    act(() => {
      getByTitle('workflow.runSteps.gaugeView').click();
    });

    expect(renderedLabels(container)).toEqual(DAG_ORDER);
  });

  it('keeps a step whose node no longer exists, at the end', () => {
    // Rows survive a node deletion; dropping or scattering them would hide
    // execution data the user came to read.
    const { container } = renderPanel({
      streamedSteps: [step('ghost'), ...SCRAMBLED],
    });
    publishCanvas();

    const labels = [...container.querySelectorAll('span')]
      .map((el) => el.textContent ?? '')
      .filter((text) => new Set([...DAG_ORDER, 'ghost']).has(text));
    expect(labels).toEqual([...DAG_ORDER, 'ghost']);
  });

  it('falls back to the given order when no canvas has published', () => {
    // The panel can be open with no canvas mounted (run history, showcase):
    // no graph means no opinion, never a reshuffle into an arbitrary order.
    const { container } = renderPanel({ workflowId: 'wf-not-mounted' });

    expect(renderedLabels(container)).toEqual([]);
    const aliases = [...container.querySelectorAll('span')]
      .map((el) => el.textContent ?? '')
      .filter((text) => ['save', 'notify', 'enrich', 'start', 'fetch'].includes(text));
    expect(aliases).toEqual(['save', 'notify', 'enrich', 'start', 'fetch']);
  });

  it('orders the not-run-yet node preview by the same DAG rank', () => {
    // Otherwise the preview reads in canvas creation order and visibly reshuffles
    // the instant the first step lands.
    const { container } = renderPanel({ streamedSteps: [] });
    publishCanvas();

    expect(renderedLabels(container)).toEqual(DAG_ORDER);
  });
});
