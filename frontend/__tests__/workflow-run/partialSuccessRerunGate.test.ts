/**
 * A PARTIAL_SUCCESS node must be RE-RUNNABLE, which is the other half of the same fix.
 *
 * `StepStateBuilder` reports a node carrying a failure in its tally as PARTIAL_SUCCESS, and the
 * client's status switch matched no case for it - so the node landed in NEITHER `completedSteps`
 * nor `failedSteps`, and `canRerunStep`, which tests those sets, answered false. An aggregate with
 * a few failed items had no rerun button while every other node on the run had one.
 *
 * This file covers the REST path; the socket emits the same value and is bucketed identically in
 * `WorkflowRunManager.deriveTrackingSetsFromSteps`, pinned in its own suite. Both channels must
 * bucket it, or the button disappears on whichever one delivered the node.
 *
 * The node's amber colour is unaffected by this bucketing: it comes from `data.status`, asserted
 * in `nodes/__tests__/partialSuccessNodeColor.test.tsx`. The two tests are deliberately separate
 * because the two behaviours read different sources, and an earlier attempt that made them share
 * one source broke whichever half it did not touch.
 */

import { describe, it, expect, beforeEach } from 'vitest';

import { deleteRunStateStore, getRunStateStore } from '@/contexts/workflow-run/RunStateStore';
import { deriveNodeStatus } from '@/app/workflows/builder/components/NodePlayButton';

const RUN_ID = 'run_partial_gate';

beforeEach(() => {
  deleteRunStateStore(RUN_ID);
});

function restState(steps: any[]): any {
  return {
    runId: RUN_ID,
    workflowId: 'wf_partial',
    status: 'waiting_trigger',
    executionMode: 'step_by_step',
    startedAt: '2026-08-09T10:00:00Z',
    currentEpoch: 0,
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps,
    edges: [],
  };
}

describe('partial_success bucketing - the REST path that produces it', () => {
  it('counts a PARTIAL_SUCCESS node as completed, which is what opens the rerun gate', () => {
    const store = getRunStateStore(RUN_ID);

    store.applyTrackingFromApi(restState([
      { stepId: 'core:aggregate', status: 'PARTIAL_SUCCESS', statusCounts: { completed: 3, failed: 1 } },
      { stepId: 'mcp:plain', status: 'COMPLETED', statusCounts: { completed: 1 } },
    ]));

    const state = store.getState();
    expect(state.completedSteps.has('core:aggregate')).toBe(true);
    expect(state.failedSteps.has('core:aggregate')).toBe(false);
    expect(state.completedSteps.has('mcp:plain')).toBe(true);
  });

  it('keeps a plainly failed node in failedSteps, which also opens the gate', () => {
    const store = getRunStateStore(RUN_ID);

    store.applyTrackingFromApi(restState([
      { stepId: 'mcp:broken', status: 'FAILED', statusCounts: { failed: 1 } },
    ]));

    const state = store.getState();
    expect(state.failedSteps.has('mcp:broken')).toBe(true);
    expect(state.completedSteps.has('mcp:broken')).toBe(false);
  });

  it('leaves a node that has not run in neither set, so it gets a play button not a rerun', () => {
    const store = getRunStateStore(RUN_ID);

    store.applyTrackingFromApi(restState([
      { stepId: 'mcp:untouched', status: 'PENDING' },
    ]));

    const state = store.getState();
    expect(state.completedSteps.has('mcp:untouched')).toBe(false);
    expect(state.failedSteps.has('mcp:untouched')).toBe(false);
  });

  it('carries the counts through, so the node can render its own tally', () => {
    // The colour and the badge both read these; losing them here would leave the node
    // unable to show why it is amber.
    const store = getRunStateStore(RUN_ID);

    store.applyTrackingFromApi(restState([
      { stepId: 'core:aggregate', status: 'PARTIAL_SUCCESS', statusCounts: { completed: 3, failed: 1 } },
    ]));

    const step = store.getState().batchSteps.find((s: any) =>
      s.id === 'core:aggregate' || s.normalizedStepId === 'core:aggregate');
    expect(step?.statusCounts).toMatchObject({ completed: 3, failed: 1 });
  });
});

/**
 * The other source the button reads. `deriveNodeStatus` intentionally does NOT look at
 * `data.status`: it answers from the boolean flags, so a partial node is still 'completed' there
 * and NodePlayButton renders its rerun affordance. That split is what lets the border be amber
 * and the button be present at the same time - an earlier attempt introduced 'partial_success'
 * into this enum instead, and the button renders null for a status it does not know, so it
 * removed the very thing it was meant to preserve.
 */
describe('deriveNodeStatus - the status the rerun button reads', () => {
  const flags = (over: Partial<Record<string, boolean>> = {}) => ({
    isRunning: false, isFailed: false, isSkipped: false, isCompleted: false, isReady: false, ...over,
  }) as Parameters<typeof deriveNodeStatus>[0];

  it('answers completed for a node the backend calls partial, so the button still renders', () => {
    expect(deriveNodeStatus(flags({ isCompleted: true }))).toBe('completed');
  });

  it('never answers partial_success - NodePlayButton renders null for a status it does not know', () => {
    const every = [
      deriveNodeStatus(flags({ isCompleted: true })),
      deriveNodeStatus(flags({ isFailed: true })),
      deriveNodeStatus(flags({ isRunning: true })),
      deriveNodeStatus(flags({ isSkipped: true })),
      deriveNodeStatus(flags({ isReady: true })),
      deriveNodeStatus(flags()),
    ];
    expect(every).not.toContain('partial_success');
  });
});
