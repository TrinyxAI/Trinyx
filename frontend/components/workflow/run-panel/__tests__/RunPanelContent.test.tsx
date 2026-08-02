/**
 * @vitest-environment jsdom
 *
 * The Run tab is a two-level hierarchy: run history (which run?) is the PARENT
 * of the run detail (which epoch? which step?). These tests pin that contract -
 * a picked run drills down, the back affordances walk back up, and surfaces
 * whose run is frozen (marketplace preview, embedded canvas) never expose the
 * history at all.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

const push = vi.hoisted(() => vi.fn());
const setRunId = vi.hoisted(() => vi.fn());
const setViewingEpoch = vi.hoisted(() => vi.fn());
/** Run the page context is bound to - null when the workflow has never run. */
const ctxRunId = vi.hoisted(() => ({ value: 'run-1' as string | null }));
/** Epoch the panel's own provider currently holds - null = freshly mounted. */
const ctxEpoch = vi.hoisted(() => ({ value: 2 as number | null }));
const historyProps = vi.hoisted(() => ({ current: null as any }));
const summaryProps = vi.hoisted(() => ({ current: null as any }));
const stepsProps = vi.hoisted(() => ({ current: null as any }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k, useLocale: () => 'en' }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ runId: ctxRunId.value, setRunId, viewingEpoch: ctxEpoch.value, setViewingEpoch }),
}));
vi.mock('@/components/workflow/run-panel/RunHistoryList', () => ({
  RunHistoryList: (props: any) => {
    historyProps.current = props;
    return (
      <button type="button" data-testid="history" onClick={() => props.onSelectRun({ runId: 'run-2', id: 'run-2' })}>
        history
      </button>
    );
  },
}));
vi.mock('@/components/workflow/run-panel/RunSummaryBar', () => ({
  RunSummaryBar: (props: any) => {
    summaryProps.current = props;
    return (
      <div data-testid="summary">
        {props.leading}
        <button type="button" data-testid="version" onClick={() => props.onVersionClick?.()}>version</button>
      </div>
    );
  },
}));
vi.mock('@/components/workflow/run-panel/RunStepsPanel', () => ({
  RunStepsPanel: (props: any) => { stepsProps.current = props; return <div data-testid="steps" />; },
}));

import { RunPanelContent } from '@/components/workflow/run-panel/RunPanelContent';
import { clearRunPanelCache, publishRunPanelData, makeEmptyRunPanelData } from '@/components/workflow/run-panel/runPanelBus';
import { resetEpochSelectionState } from '@/components/workflow/run-panel/useDefaultEpochSelection';

/** The back arrow rendered inside the summary bar's `leading` slot. */
const backButton = () => document.querySelector<HTMLElement>('[data-run-panel-back]');

function publish(overrides: Record<string, unknown> = {}) {
  publishRunPanelData({
    ...makeEmptyRunPanelData('wf-1'),
    runId: 'run-1',
    runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 3 },
    currentEpoch: 2,
    epochTimestamps: [{ epoch: 1, startedAt: 'x', endedAt: 'y' }, { epoch: 2, startedAt: 'z', endedAt: null }],
    ...overrides,
  } as any);
}

beforeEach(() => { ctxRunId.value = 'run-1'; ctxEpoch.value = 2; publish(); });
afterEach(() => {
  clearRunPanelCache();
  resetEpochSelectionState();
  ctxRunId.value = 'run-1';
  ctxEpoch.value = 2;
  push.mockReset(); setRunId.mockReset(); setViewingEpoch.mockReset();
  historyProps.current = null; summaryProps.current = null; stepsProps.current = null;
  cleanup();
});

describe('RunPanelContent - run history is the parent of the run detail', () => {
  it('opens on the run detail and feeds it the published run snapshot', () => {
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(screen.getByTestId('summary')).toBeTruthy();
    expect(stepsProps.current.epochTimestamps).toHaveLength(2);
    expect(stepsProps.current.selectedEpoch).toBe(2);
  });

  it('walks back up to the history from the back button AND the version chip', () => {
    const { rerender } = render(<RunPanelContent workflowId="wf-1" allowHistory />);

    fireEvent.click(backButton()!);
    expect(screen.getByTestId('history')).toBeTruthy();

    // Back down, then up again via the version chip - both lead to the same level.
    rerender(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'run', seq: 1 }} />);
    fireEvent.click(screen.getByTestId('version'));
    expect(screen.getByTestId('history')).toBeTruthy();
  });

  it('picking a run binds it IN PLACE (no navigation) and drills into its detail', () => {
    const bound: any[] = [];
    const onBind = (e: Event) => bound.push((e as CustomEvent).detail);
    window.addEventListener('workflowBindRun', onBind);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
      fireEvent.click(screen.getByTestId('history'));
    } finally {
      window.removeEventListener('workflowBindRun', onBind);
    }

    expect(setRunId).toHaveBeenCalledWith('run-2');
    expect(bound).toEqual([{ workflowId: 'wf-1', runId: 'run-2' }]);
    // A router push here would remount the whole route - the "full page refresh"
    // the user sees just for looking at a sibling run.
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByTestId('summary')).toBeTruthy();
  });

  it('marks the current run as selected in the history list', () => {
    render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
    expect(historyProps.current.currentRunId).toBe('run-1');
  });

  it('honours a re-requested level even when the level did not change', () => {
    const { rerender } = render(
      <RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />,
    );
    fireEvent.click(screen.getByTestId('history')); // navigates to the run detail
    expect(screen.getByTestId('summary')).toBeTruthy();

    rerender(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 2 }} />);
    expect(screen.getByTestId('history')).toBeTruthy();
  });
});

describe('RunPanelContent - frozen-run surfaces', () => {
  it('locks the marketplace preview to the run detail, with no way back to the history', () => {
    publish({ isPreviewOnly: true });
    render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);

    expect(screen.getByTestId('summary')).toBeTruthy();
    expect(screen.queryByTestId('history')).toBeNull();
    expect(backButton()).toBeNull();
    expect(summaryProps.current.onVersionClick).toBeUndefined();
  });

  it('hides the run actions on a preview (a frozen run cannot be stopped)', () => {
    publish({ isPreviewOnly: true });
    render(<RunPanelContent workflowId="wf-1" />);
    expect(summaryProps.current.onStop).toBeUndefined();
    expect(summaryProps.current.onReactivate).toBeUndefined();
  });

  it('keeps an embedded canvas (allowHistory=false) on its own run', () => {
    render(<RunPanelContent workflowId="wf-1" />);
    expect(backButton()).toBeNull();
    expect(screen.getByTestId('summary')).toBeTruthy();
  });
});

describe('RunPanelContent - no run yet', () => {
  it('falls back to the history when the workflow has no run bound at all', () => {
    ctxRunId.value = null;
    publish({ runId: null, runInfo: null });
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(screen.getByTestId('history')).toBeTruthy();
  });

  it('shows an empty state instead of a broken detail when history is not available', () => {
    ctxRunId.value = null;
    publish({ runId: null, runInfo: null });
    render(<RunPanelContent workflowId="wf-1" />);
    expect(screen.queryByTestId('summary')).toBeNull();
    expect(screen.getByText('runs.noRuns')).toBeTruthy();
  });

  it('STAYS on the run level while a bound run has no snapshot yet (just launched)', () => {
    // The canvas has not published its first snapshot; bouncing to the history
    // here is what made "open the run I just launched" land on the wrong level.
    publish({ runId: null, runInfo: null }); // context still holds run-1
    render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'run', seq: 1 }} />);
    expect(screen.queryByTestId('history')).toBeNull();
    expect(screen.getByText('runs.loading')).toBeTruthy();
  });
});

describe('RunPanelContent - run actions', () => {
  it('asks the canvas to stop / cancel / reactivate the run it is showing', async () => {
    const seen: any[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener('workflowRunAction', handler);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory />);
      act(() => { summaryProps.current.onStop(); });
      act(() => { summaryProps.current.onCancel(); });
      act(() => { summaryProps.current.onReactivate(); });
    } finally {
      window.removeEventListener('workflowRunAction', handler);
    }

    expect(seen).toEqual([
      { action: 'stop', workflowId: 'wf-1', runId: 'run-1' },
      { action: 'cancel', workflowId: 'wf-1', runId: 'run-1' },
      { action: 'reactivate', workflowId: 'wf-1', runId: 'run-1' },
    ]);
  });
});

describe('RunPanelContent - picking a run', () => {
  it('does not re-bind when the picked run is already the one being shown', () => {
    const bound: any[] = [];
    const onBind = (e: Event) => bound.push((e as CustomEvent).detail);
    window.addEventListener('workflowBindRun', onBind);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
      act(() => { historyProps.current.onSelectRun({ runId: 'run-1', id: 'run-1' }); });
    } finally {
      window.removeEventListener('workflowBindRun', onBind);
    }
    // Re-binding the current run would tear the canvas down for nothing.
    expect(bound).toEqual([]);
    expect(screen.getByTestId('summary')).toBeTruthy();
  });

  it('ignores a row with no usable id instead of binding to undefined', () => {
    const bound: any[] = [];
    const onBind = (e: Event) => bound.push((e as CustomEvent).detail);
    window.addEventListener('workflowBindRun', onBind);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
      act(() => { historyProps.current.onSelectRun({}); });
    } finally {
      window.removeEventListener('workflowBindRun', onBind);
    }
    expect(bound).toEqual([]);
    expect(setRunId).not.toHaveBeenCalled();
    // Still on the history: nothing was selected.
    expect(screen.getByTestId('history')).toBeTruthy();
  });
});

describe('RunPanelContent - epoch selection', () => {
  it('tells its own provider which run it shows, so epoch picks stay scoped', () => {
    // The side panel mounts its OWN WorkflowModeProvider and the workflow-panel
    // tab does not name a run when it does. Until the provider is told, its
    // viewingEpoch broadcast carries runId:null, which every other mounted
    // provider adopts - picking an epoch here dragged a sub-workflow tab and an
    // application tab to the same epoch.
    ctxRunId.value = null;
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(setRunId).toHaveBeenCalledWith('run-1');
  });

  it('waits for its provider to adopt the run before seeding an epoch', () => {
    // setRunId is a state update: seeding in the same commit would broadcast the
    // epoch with runId:null, which every other mounted provider adopts - the
    // cross-talk the scoping exists to prevent, on the very first selection.
    ctxRunId.value = null;
    ctxEpoch.value = null;
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(setRunId).toHaveBeenCalledWith('run-1');
    expect(setViewingEpoch).not.toHaveBeenCalled();
  });

  it('lands on an epoch by itself, instead of the cumulative "All epochs"', () => {
    // The canvas seeds its own provider and broadcasts, but this panel does not
    // exist while the side panel is closed - the normal case - so it never heard
    // that broadcast and opened unselected, listing every epoch's steps while the
    // canvas chip beside it read a number.
    ctxEpoch.value = null;
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(setViewingEpoch).toHaveBeenCalledWith(2);
  });

  it('does not re-announce a run the provider already knows', () => {
    ctxRunId.value = 'run-1';
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(setRunId).not.toHaveBeenCalled();
  });

  it('records an explicit epoch pick so the live-follow stops fighting the user', async () => {
    const { markEpochPickedByUser, isEpochAutoFollowing } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    expect(isEpochAutoFollowing('run-1')).toBe(true);
    render(<RunPanelContent workflowId="wf-1" allowHistory />);

    act(() => { stepsProps.current.onSelectEpoch(1); });

    expect(setViewingEpoch).toHaveBeenCalledWith(1);
    expect(isEpochAutoFollowing('run-1')).toBe(false);
    // Sanity: the helper the component uses is the one asserted above.
    expect(typeof markEpochPickedByUser).toBe('function');
  });
});
