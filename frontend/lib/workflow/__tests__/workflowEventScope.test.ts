import { describe, expect, it } from 'vitest';
import { isEventForWorkflow } from '../workflowEventScope';

/**
 * The rule the Save/Run/dirty events are filtered through once several canvases
 * can be mounted at once. It refuses ONE case and only that case: an event that
 * names a workflow other than the listener's.
 */
describe('isEventForWorkflow', () => {
  it('accepts an event addressed to this workflow', () => {
    expect(isEventForWorkflow({ workflowId: 'wf-1' }, 'wf-1')).toBe(true);
  });

  it('refuses an event addressed to another workflow', () => {
    expect(isEventForWorkflow({ workflowId: 'wf-2' }, 'wf-1')).toBe(false);
  });

  it('accepts an event that names no workflow, so broadcast dispatchers keep working', () => {
    expect(isEventForWorkflow({ startFromNode: 'n1' }, 'wf-1')).toBe(true);
    expect(isEventForWorkflow(undefined, 'wf-1')).toBe(true);
    expect(isEventForWorkflow(null, 'wf-1')).toBe(true);
    // An empty string is "unnamed", not a workflow called "".
    expect(isEventForWorkflow({ workflowId: '' }, 'wf-1')).toBe(true);
  });

  it('accepts anything when the listener has no workflow of its own to compare', () => {
    expect(isEventForWorkflow({ workflowId: 'wf-2' }, undefined)).toBe(true);
    expect(isEventForWorkflow({ workflowId: 'wf-2' }, null)).toBe(true);
    expect(isEventForWorkflow({ workflowId: 'wf-2' }, '')).toBe(true);
  });

  it('ignores a non-string workflow id rather than comparing it', () => {
    expect(isEventForWorkflow({ workflowId: 42 }, 'wf-1')).toBe(true);
  });
});
