// @vitest-environment jsdom
/**
 * The WIRING, not the predicate.
 *
 * `isTerminalStepStatus` has its own unit test, but a review proved the call site was uncovered:
 * reverting this hook to its old inline `completed || failed` filter left every suite green while
 * reinstating the bug in the only place it ever existed. The predicate being right is worthless if
 * the filter stops using it.
 *
 * What breaks when it does: this event is what `useStepCompletionInvalidation` / `useStepData` /
 * `useRunOutputData` listen to. A node excluded here simply stops refreshing its output, silently
 * and permanently, because the accumulated counts that make it partial never reset.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';

vi.mock('../../utils/labelNormalizer', () => ({
  normalizeLabel: (l: string) => l.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''),
  coreKey: (l: string) => `core:${l}`,
  mcpKey: (l: string) => `mcp:${l}`,
}));

import { useRunStateProcessing } from '../useRunStateProcessing';

const node = (id: string) => ({
  id,
  type: 'flowNode',
  position: { x: 0, y: 0 },
  data: { id, label: 'Boom', status: 'running' },
});

function runHookWith(stepStatus: string) {
  const nodes = [node('core:boom')];
  const nodesRef = { current: nodes } as React.MutableRefObject<any>;
  const edgesRef = { current: [] } as React.MutableRefObject<any>;

  renderHook(() =>
    useRunStateProcessing({
      runState: {
        batchSteps: [{ id: 'core:boom', status: stepStatus, statusCounts: { completed: 1, failed: 1 } }],
        batchEdges: [],
      },
      workflowLoaded: true,
      nodesReady: true,
      nodesRef,
      edgesRef,
      setNodes: (updater: any) => { nodesRef.current = typeof updater === 'function' ? updater(nodesRef.current) : updater; },
      setEdges: (updater: any) => { edgesRef.current = typeof updater === 'function' ? updater(edgesRef.current) : updater; },
      setWorkflowStatus: () => {},
      workflowId: 'wf-1',
      effectiveRunId: 'run-1',
      isViewingHistoricalEpoch: false,
    } as any),
  );
}

let dispatched: CustomEvent[];

beforeEach(() => {
  dispatched = [];
  vi.spyOn(window, 'dispatchEvent').mockImplementation((e: Event) => {
    if (e.type === 'stepExecutionCompleted') dispatched.push(e as CustomEvent);
    return true;
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('useRunStateProcessing - the data-refresh event', () => {
  it('fires for a partial_success step, so its output keeps refreshing', () => {
    runHookWith('partial_success');
    expect(dispatched, 'a partial node finished and must refresh like any other').toHaveLength(1);
  });

  it('fires for the plainly terminal statuses', () => {
    runHookWith('completed');
    expect(dispatched).toHaveLength(1);
    dispatched = [];
    runHookWith('failed');
    expect(dispatched).toHaveLength(1);
  });

  it('does not fire while the step is still running', () => {
    // The counterexample: firing here would refetch output that does not exist yet, on every tick.
    runHookWith('running');
    expect(dispatched).toHaveLength(0);
  });
});
