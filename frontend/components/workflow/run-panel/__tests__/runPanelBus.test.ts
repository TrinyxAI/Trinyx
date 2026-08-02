/**
 * @vitest-environment jsdom
 *
 * The Run tab lives in the side panel (app-layout tree) while the run state is
 * owned by the canvas (page tree). Everything they exchange goes through this
 * bus, so the scoping rules are load-bearing:
 *  - a snapshot must only reach the panel bound to the SAME workflow (a
 *    sub-workflow tab and the main panel are mounted at the same time);
 *  - the panel body is unmounted while the side panel is closed, so the level
 *    request must survive until it mounts.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearRunPanelCache,
  consumeRunPanelViewRequest,
  getCachedRunPanelData,
  getRunPanelViewRequest,
  makeEmptyRunPanelData,
  openRunPanel,
  publishRunPanelData,
  requestBindRun,
  requestRunAction,
  subscribeRunPanelData,
  BIND_RUN_EVENT,
  OPEN_RUN_PANEL_EVENT,
  RUN_PANEL_ACTION_EVENT,
  type RunPanelData,
} from '@/components/workflow/run-panel/runPanelBus';

const snapshot = (workflowId: string, overrides: Partial<RunPanelData> = {}): RunPanelData => ({
  ...makeEmptyRunPanelData(workflowId),
  runId: 'run-1',
  runInfo: { runId: 'run-1', status: 'RUNNING' },
  ...overrides,
});

afterEach(() => clearRunPanelCache());

describe('run panel snapshots', () => {
  it('delivers a snapshot to the subscriber bound to that workflow', () => {
    const received: RunPanelData[] = [];
    const unsubscribe = subscribeRunPanelData('wf-1', d => received.push(d));
    publishRunPanelData(snapshot('wf-1'));
    unsubscribe();

    expect(received).toHaveLength(1);
    expect(received[0].runId).toBe('run-1');
  });

  it('never delivers another workflow snapshot (sub-workflow tab isolation)', () => {
    const received: RunPanelData[] = [];
    const unsubscribe = subscribeRunPanelData('wf-1', d => received.push(d));
    publishRunPanelData(snapshot('wf-2'));
    unsubscribe();

    expect(received).toHaveLength(0);
  });

  it('caches the latest snapshot so a panel mounting later is not empty', () => {
    publishRunPanelData(snapshot('wf-1', { currentEpoch: 4 }));
    expect(getCachedRunPanelData('wf-1').currentEpoch).toBe(4);
    // An unknown workflow yields inert defaults rather than another one's run.
    expect(getCachedRunPanelData('wf-other').runInfo).toBeNull();
  });

  it('stops delivering after unsubscribe', () => {
    const onData = vi.fn();
    subscribeRunPanelData('wf-1', onData)();
    publishRunPanelData(snapshot('wf-1'));
    expect(onData).not.toHaveBeenCalled();
  });
});

describe('run panel level requests', () => {
  it('remembers the requested level for a panel that is not mounted yet', () => {
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    expect(getRunPanelViewRequest('wf-1')?.view).toBe('history');
  });

  it('bumps the sequence so the same level can be requested twice', () => {
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    const first = getRunPanelViewRequest('wf-1')!.seq;
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    expect(getRunPanelViewRequest('wf-1')!.seq).toBeGreaterThan(first);
  });

  it('defaults to the run detail and ignores a request aimed at another workflow', () => {
    openRunPanel({ workflowId: 'wf-1' });
    expect(getRunPanelViewRequest('wf-1')?.view).toBe('run');
    expect(getRunPanelViewRequest('wf-2')).toBeNull();
  });

  it('a request with NO workflowId applies to whichever panel asks', () => {
    // The application panel opens the Run tab without naming a workflow; the
    // request must not be swallowed there.
    openRunPanel({ view: 'run' });
    expect(getRunPanelViewRequest('wf-1')?.view).toBe('run');
    expect(getRunPanelViewRequest('wf-any-other')?.view).toBe('run');
  });

  it('emits an event carrying the requested level', () => {
    const seen: any[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener(OPEN_RUN_PANEL_EVENT, handler);
    try {
      openRunPanel({ workflowId: 'wf-1', view: 'history' });
    } finally {
      window.removeEventListener(OPEN_RUN_PANEL_EVENT, handler);
    }
    expect(seen[0]).toMatchObject({ workflowId: 'wf-1', view: 'history' });
  });
});

describe('run binding', () => {
  it('names the run to bind in place, without any navigation', () => {
    const seen: any[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener(BIND_RUN_EVENT, handler);
    try {
      requestBindRun({ workflowId: 'wf-1', runId: 'run-9' });
    } finally {
      window.removeEventListener(BIND_RUN_EVENT, handler);
    }
    expect(seen).toEqual([{ workflowId: 'wf-1', runId: 'run-9' }]);
  });
});

describe('level request lifetime', () => {
  it('is dropped once a panel has adopted it', () => {
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    consumeRunPanelViewRequest('wf-1');
    // A remount an hour later must not replay a level asked for one navigation ago.
    expect(getRunPanelViewRequest('wf-1')).toBeNull();
  });

  it('is not consumed by a panel bound to another workflow', () => {
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    consumeRunPanelViewRequest('wf-2');
    expect(getRunPanelViewRequest('wf-1')?.view).toBe('history');
  });
});

describe('workspace switch', () => {
  it('drops the pending level request, not just the snapshots', () => {
    publishRunPanelData(snapshot('wf-1'));
    openRunPanel({ workflowId: 'wf-1', view: 'history' });

    clearRunPanelCache();

    // Replaying a request minted in the previous workspace would yank the panel
    // to a level the user never asked for here.
    expect(getRunPanelViewRequest('wf-1')).toBeNull();
    expect(getCachedRunPanelData('wf-1').runInfo).toBeNull();
  });
});

describe('run actions', () => {
  it('names the action and the run so the canvas can perform it', () => {
    const seen: any[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
    try {
      requestRunAction({ action: 'reactivate', workflowId: 'wf-1', runId: 'run-1' });
    } finally {
      window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler);
    }
    expect(seen[0]).toEqual({ action: 'reactivate', workflowId: 'wf-1', runId: 'run-1' });
  });
});
