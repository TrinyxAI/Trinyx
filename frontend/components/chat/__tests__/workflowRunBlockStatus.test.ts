import { describe, it, expect } from 'vitest';
import { deriveDisplayStatus, deriveRunStatusFromNodes } from '../WorkflowRunBlock';

/**
 * The chat run block was the last surface still answering `partial_success` for a RUN, and it was
 * also the one that failed open the hardest.
 *
 * Its old shape tested `some(n => n.status === 'completed')` and `some(n => n.status === 'failed')`
 * separately. Once the backend started reporting a node that failed and later succeeded as
 * `partial_success`, that node matched NEITHER, so a run carrying a failure fell through to the
 * final `return 'completed'` and rendered green in the chat.
 */
describe('deriveRunStatusFromNodes', () => {
  it('reports failed when a node carries a failure in its tally', () => {
    // The fail-open case: this used to return 'completed'.
    expect(deriveRunStatusFromNodes([
      { status: 'completed' },
      { status: 'partial_success' },
    ])).toBe('failed');
  });

  it('reports failed for a plainly failed node', () => {
    expect(deriveRunStatusFromNodes([{ status: 'completed' }, { status: 'failed' }])).toBe('failed');
  });

  it('never answers partial_success - that is a node verdict, not a run one', () => {
    // The mixed shape that used to produce it: some completed, some failed.
    expect(deriveRunStatusFromNodes([
      { status: 'completed' },
      { status: 'failed' },
    ])).not.toBe('partial_success');
  });

  it('reports completed when nothing failed', () => {
    // The counterexample that keeps the rule honest: skipped and completed are not failures.
    expect(deriveRunStatusFromNodes([
      { status: 'completed' },
      { status: 'skipped' },
    ])).toBe('completed');
  });

  it('reports completed for an empty node list rather than inventing a failure', () => {
    expect(deriveRunStatusFromNodes([])).toBe('completed');
  });
});

/**
 * The whole branch chain, not just its last line.
 *
 * A review restored the old fail-open body INSIDE the component while leaving the helper above
 * intact, and the helper's own tests stayed green - the third time in this work that an extracted
 * rule was pinned and its wiring was not. These cases drive the chain that actually reaches it.
 */
describe('deriveDisplayStatus - the chain that reaches the verdict', () => {
  const live = (nodes: Array<{ status?: string }>, workflowStatus?: { status?: string } | null) =>
    deriveDisplayStatus({ nodes, workflowStatus: workflowStatus ?? null });

  it('reports failed for a resolved run whose node carries a failure in its tally', () => {
    // The fail-open case, driven end to end: this rendered a green "completed" block.
    expect(live([{ status: 'completed' }, { status: 'partial_success' }])).toBe('failed');
  });

  it('reports completed for a resolved run with nothing failed', () => {
    expect(live([{ status: 'completed' }, { status: 'skipped' }])).toBe('completed');
  });

  it('still calls a run running while any node is running or pending', () => {
    // Must beat the verdict: a partial node next to a running one is not a finished run.
    expect(live([{ status: 'partial_success' }, { status: 'running' }])).toBe('running');
    expect(live([{ status: 'completed' }, { status: 'pending' }])).toBe('running');
  });

  it('lets waiting_trigger win over nodes sitting at pending', () => {
    expect(live([{ status: 'pending' }], { status: 'waiting_trigger' })).toBe('waiting_trigger');
  });

  it('trusts a stored terminal run status over its nodes, including a legacy partial one', () => {
    // Skipped branches can stay 'pending' forever, so a finished run beats its nodes. Runs
    // recorded before the binary rule still carry partial_success and must keep rendering.
    expect(live([{ status: 'pending' }], { status: 'completed' })).toBe('completed');
    expect(live([{ status: 'pending' }], { status: 'partial_success' })).toBe('partial_success');
  });

  it('is pending when a live run has no nodes yet', () => {
    expect(live([])).toBe('pending');
  });

  it('never invents a pending state for a historical run', () => {
    expect(deriveDisplayStatus({ isHistorical: true, nodes: [] })).toBe('completed');
    expect(deriveDisplayStatus({ isHistorical: true, savedStatus: 'failed', nodes: [] })).toBe('failed');
  });
});
