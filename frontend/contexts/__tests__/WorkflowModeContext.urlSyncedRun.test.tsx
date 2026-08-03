/**
 * @vitest-environment jsdom
 *
 * Two rules about who owns "which run is on screen".
 *
 * 1. `setRunId(id, { urlSynced: true })` - the run-history pick, which writes the
 *    address bar itself. That binding is NOT programmatic: the provider must keep
 *    honouring the pathname afterwards, or the next real navigation (the Edit
 *    toggle) would move the URL with nothing following it. A plain `setRunId(id)`
 *    (an agent-launched run overlaid on the edit page) still latches, because
 *    there the URL says nothing about the run.
 *
 * 2. The URL speaks for ONE workflow. Several providers are mounted at once (the
 *    page, a sub-workflow tab, an application tab); a provider that knows it is
 *    about another workflow must not adopt the run named by the URL.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React from 'react';
import { act, render } from '@testing-library/react';

let mockPathname = '/app/workflow/wf-1';
vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { listVersions: vi.fn(async () => ({ pinnedVersion: null, currentVersion: 1 })) },
}));
vi.mock('@/lib/stores/pending-interfaces-store', () => ({
  usePendingInterfacesStore: { getState: () => ({ clear: vi.fn() }) },
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ clear: vi.fn() }) },
}));

import { WorkflowModeProvider, useWorkflowMode } from '../WorkflowModeContext';

let ctx: ReturnType<typeof useWorkflowMode> | null = null;
function Probe() {
  ctx = useWorkflowMode();
  return null;
}

function setPath(path: string) {
  mockPathname = path;
  window.history.pushState({}, '', path);
}

beforeEach(() => {
  ctx = null;
  setPath('/app/workflow/wf-1');
});

describe('WorkflowModeContext - a URL-synced run binding', () => {
  it('keeps honouring the pathname after the pick (the Edit toggle still works)', () => {
    setPath('/app/workflow/wf-1/run/run-1');
    const { rerender } = render(<WorkflowModeProvider workflowId="wf-1"><Probe /></WorkflowModeProvider>);
    expect(ctx!.runId).toBe('run-1');

    // The history pick: bind run-2 and say the address bar was rewritten to it.
    act(() => { ctx!.setRunId('run-2', { urlSynced: true }); });
    setPath('/app/workflow/wf-1/run/run-2');
    rerender(<WorkflowModeProvider workflowId="wf-1"><Probe /></WorkflowModeProvider>);
    expect(ctx!.runId).toBe('run-2');

    // A later REAL navigation must still be obeyed.
    setPath('/app/workflow/wf-1');
    rerender(<WorkflowModeProvider workflowId="wf-1"><Probe /></WorkflowModeProvider>);
    expect(ctx!.runId, 'leaving the run route clears the run').toBeNull();
    expect(ctx!.isEditMode).toBe(true);
  });

  it('still latches for a run bound with NO url behind it (agent-launched overlay)', () => {
    // The edit page never names a run, so the pathname must not be allowed to
    // undo the overlay on the next render.
    const { rerender } = render(<WorkflowModeProvider workflowId="wf-1"><Probe /></WorkflowModeProvider>);
    act(() => { ctx!.setRunId('run-9'); });
    expect(ctx!.runId).toBe('run-9');
    expect(ctx!.isRunMode).toBe(true);

    setPath('/app/workflow/wf-1');
    rerender(<WorkflowModeProvider workflowId="wf-1"><Probe /></WorkflowModeProvider>);
    expect(ctx!.runId, 'the overlay survives an edit-page pathname').toBe('run-9');
  });
});

describe('WorkflowModeContext - the URL belongs to one workflow', () => {
  it('does not adopt the run of a URL that names ANOTHER workflow', () => {
    // A sub-workflow tab mounted next to the page: picking a run on the page
    // moves the URL, and this provider must ignore it.
    setPath('/app/workflow/wf-1/run/run-1');
    render(<WorkflowModeProvider workflowId="wf-2"><Probe /></WorkflowModeProvider>);
    expect(ctx!.runId).toBeNull();
    expect(ctx!.isRunMode).toBe(false);
  });

  it('adopts it when the URL names ITS workflow', () => {
    setPath('/fr/app/workflow/wf-2/run/run-7');
    render(<WorkflowModeProvider workflowId="wf-2"><Probe /></WorkflowModeProvider>);
    expect(ctx!.runId).toBe('run-7');
    expect(ctx!.isRunMode).toBe(true);
  });

  it('reads NOTHING from that pathname, not even the mode', () => {
    // The guard is about the whole reading, not just the run: a sub-workflow
    // tab must not flip to run mode because the page it sits next to did.
    setPath('/app/workflow/wf-1/run/run-1');
    render(<WorkflowModeProvider workflowId="wf-2"><Probe /></WorkflowModeProvider>);
    expect(ctx!.isRunMode).toBe(false);
    expect(ctx!.isEditMode).toBe(true);
  });

  it('still reads a pathname that names no workflow at all', () => {
    // The guard fires on a MISMATCH only: an applications URL names no
    // workflow, so the historical reading (application mode) still applies.
    setPath('/app/applications/pub-1');
    render(<WorkflowModeProvider workflowId="wf-2"><Probe /></WorkflowModeProvider>);
    expect(ctx!.isApplicationMode).toBe(true);
  });

  it('keeps the historical behaviour when the provider names no workflow', () => {
    setPath('/app/workflow/wf-1/run/run-1');
    render(<WorkflowModeProvider><Probe /></WorkflowModeProvider>);
    expect(ctx!.runId).toBe('run-1');
  });
});
