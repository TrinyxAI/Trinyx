/**
 * @vitest-environment jsdom
 *
 * Regression: picking a run in the panel's history bound the canvas in place but
 * left the address bar on the run the page had been opened with. The switch was
 * therefore invisible to a reload - F5 restored the PREVIOUS run - and the run
 * being looked at could not be shared or bookmarked.
 *
 * WorkflowDetailView now brings the URL along with the native History API (no
 * route fetch, nothing remounts) and tells the provider the binding IS the URL,
 * so the URL-driven effect stays in charge of later navigations.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const push = vi.hoisted(() => vi.fn());
const setRunId = vi.hoisted(() => vi.fn());
const markRunAsJustExecuted = vi.hoisted(() => vi.fn());
const modeState = vi.hoisted(() => ({ current: { isPreviewOnly: false, runId: null as string | null, setRunId } }));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => modeState.current,
}));
vi.mock('@/app/workflows/builder/hooks/useWorkflowLoader', () => ({ markRunAsJustExecuted }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/app/workflows/builder/hooks/state', () => ({
  useUnsavedChanges: () => ({
    handleDirtyChange: vi.fn(),
    handleRefreshBlocked: vi.fn(),
    saveRef: { current: null },
    showModal: false,
    handleSave: vi.fn(),
    handleDiscard: vi.fn(),
    handleCancel: vi.fn(),
    isSaving: false,
  }),
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  setPendingActivateTab: vi.fn(),
  NODE_CREATOR_TAB_ID: 'node-creator',
  RUN_TAB_ID: 'run',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun: vi.fn() } }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('../hooks', () => ({ useAutoCollapseSidebar: () => undefined }));
vi.mock('@/components/modals/UnsavedChangesModal', () => ({ UnsavedChangesModal: () => null }));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({ WorkflowRunCanvas: () => null }));

import { WorkflowDetailView } from '@/components/views/workflow/WorkflowDetailView';
import { BIND_RUN_EVENT } from '@/components/workflow/run-panel/runPanelBus';

function fireBindRun(detail: { workflowId?: string; runId?: string; surfaceId?: string }) {
  act(() => {
    window.dispatchEvent(new CustomEvent(BIND_RUN_EVENT, { detail }));
  });
}

function goTo(path: string) {
  window.history.replaceState(null, '', path);
}

describe('WorkflowDetailView - picking a run in the history writes it to the URL', () => {
  afterEach(() => {
    push.mockReset();
    setRunId.mockReset();
    markRunAsJustExecuted.mockReset();
    modeState.current = { isPreviewOnly: false, runId: null, setRunId };
    goTo('/');
    cleanup();
  });

  it('replaces the run segment so a reload lands on the run the user picked', () => {
    goTo('/app/workflow/wf-1/run/run-old');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-old" />);
    const entriesBefore = window.history.length;

    fireBindRun({ workflowId: 'wf-1', runId: 'run-new' });

    expect(window.location.pathname).toBe('/app/workflow/wf-1/run/run-new');
    // Swapping runs REPLACES: one history entry per glanced-at run would turn
    // Back into a walk through the run history instead of leaving the page.
    expect(window.history.length).toBe(entriesBefore);
    // Bound in place all the same: no router navigation, so nothing remounts.
    expect(push).not.toHaveBeenCalled();
  });

  it('pushes instead when the run is picked from the edit page, so Back returns to it', () => {
    goTo('/app/workflow/wf-1');
    render(<WorkflowDetailView workflowId="wf-1" />);
    const entriesBefore = window.history.length;

    fireBindRun({ workflowId: 'wf-1', runId: 'run-new' });

    expect(window.location.pathname).toBe('/app/workflow/wf-1/run/run-new');
    expect(window.history.length, 'the edit page stays reachable').toBe(entriesBefore + 1);
    expect(push).not.toHaveBeenCalled();
  });

  it('marks the binding as URL-synced so later navigations keep working', () => {
    // A "programmatic" binding makes the provider ignore the URL from then on -
    // which would swallow the next Edit toggle or Back press.
    goTo('/app/workflow/wf-1/run/run-old');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-old" />);

    fireBindRun({ workflowId: 'wf-1', runId: 'run-new' });

    expect(setRunId).toHaveBeenCalledWith('run-new', { urlSynced: true });
  });

  it('keeps the locale segment and the query string', () => {
    goTo('/fr/app/workflow/wf-1/run/run-old?app=true');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-old" />);

    fireBindRun({ workflowId: 'wf-1', runId: 'run-new' });

    expect(window.location.pathname).toBe('/fr/app/workflow/wf-1/run/run-new');
    expect(window.location.search).toBe('?app=true');
  });

  it('keeps the hash, which names something on the page and not the run', () => {
    goTo('/app/workflow/wf-1/run/run-old#node-3');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-old" />);

    fireBindRun({ workflowId: 'wf-1', runId: 'run-new' });

    expect(window.location.pathname).toBe('/app/workflow/wf-1/run/run-new');
    expect(window.location.hash).toBe('#node-3');
  });

  it('binds without touching an URL that does not name this workflow', () => {
    // Defensive: the address bar is only ever rewritten when it is this
    // workflow's page. Anywhere else the binding still happens, unsynced.
    goTo('/app/workflow/other-wf');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-old" />);

    fireBindRun({ workflowId: 'wf-1', runId: 'run-new' });

    expect(window.location.pathname).toBe('/app/workflow/other-wf');
    expect(setRunId).toHaveBeenCalledWith('run-new', { urlSynced: false });
  });

  it('writes the URL for the run already on screen when the URL does not name it', () => {
    // An agent-launched run is overlaid on the EDIT url. Picking it in the
    // history is how the user says "keep this one": the address bar has to
    // follow, and the binding has to hand itself back to the pathname, or the
    // Back that push exists for would move the URL with nothing following it.
    goTo('/app/workflow/wf-1');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-agent" />);

    fireBindRun({ workflowId: 'wf-1', runId: 'run-agent' });

    expect(window.location.pathname).toBe('/app/workflow/wf-1/run/run-agent');
    expect(setRunId).toHaveBeenCalledWith('run-agent', { urlSynced: true });
  });

  it('does nothing at all when the URL already names the run on screen', () => {
    goTo('/app/workflow/wf-1/run/run-1');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-1" />);
    const entriesBefore = window.history.length;

    fireBindRun({ workflowId: 'wf-1', runId: 'run-1' });

    expect(setRunId).not.toHaveBeenCalled();
    expect(window.history.length).toBe(entriesBefore);
    expect(window.location.pathname).toBe('/app/workflow/wf-1/run/run-1');
  });

  it('ignores an event with no run in it (a marker for something else)', () => {
    goTo('/app/workflow/wf-1/run/run-old');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-old" />);

    fireBindRun({ workflowId: 'wf-1' });

    expect(setRunId).not.toHaveBeenCalled();
    expect(window.location.pathname).toBe('/app/workflow/wf-1/run/run-old');
  });

  it('ignores a bind for another workflow, URL included', () => {
    goTo('/app/workflow/wf-1/run/run-old');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-old" />);

    fireBindRun({ workflowId: 'wf-other', runId: 'run-new' });

    expect(setRunId).not.toHaveBeenCalled();
    expect(window.location.pathname).toBe('/app/workflow/wf-1/run/run-old');
  });

  it('ignores a run picked in a side-panel tab showing THIS workflow', () => {
    // A self-referencing sub-workflow node (or the tab picker) can open wf-1 in
    // the side panel next to the page already showing it. A pick made in that
    // tab carries its surface id; following it here would rewrite the address
    // bar and move the page canvas behind the panel the user is working in.
    goTo('/app/workflow/wf-1/run/run-old');
    render(<WorkflowDetailView workflowId="wf-1" runId="run-old" />);

    fireBindRun({ workflowId: 'wf-1', runId: 'run-new', surfaceId: 'side-panel-tab' });

    expect(setRunId).not.toHaveBeenCalled();
    expect(window.location.pathname).toBe('/app/workflow/wf-1/run/run-old');
  });

});
