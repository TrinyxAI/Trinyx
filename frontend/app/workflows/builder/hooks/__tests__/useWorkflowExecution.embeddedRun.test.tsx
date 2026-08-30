// @vitest-environment jsdom
/**
 * An embedded canvas enters run mode IN PLACE. It must not route the app away.
 *
 * `isEmbeddedWorkflowCanvas` states the contract, and the mode toggle already
 * honours it, but the start path pushed `/app/workflow/<id>/run/<runId>`
 * unconditionally. That was survivable while only a workflow PAGE could start a
 * run; the side panel's Run button makes it reachable from anywhere, and taking
 * the user off the page they were on is the opposite of what a panel Run is for:
 * watching this workflow run without leaving.
 *
 * Mock scaffolding mirrors useWorkflowExecution.noRunLevelMock.test.tsx.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  executeWorkflow: vi.fn(),
  getAllCredentials: vi.fn(),
  executeSingleStepInStepByStepMode: vi.fn(),
}));
const setRunId = vi.hoisted(() => vi.fn());
const routerPush = vi.hoisted(() => vi.fn());
let mockPathname: string;

vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
vi.mock('@/lib/api/error-utils', () => ({ is402Error: () => false, is413StorageError: () => false }));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/lib/billing/ceRelayErrorModals', () => ({ handleCeRelayError: () => false }));
vi.mock('../../utils/workflowPlanGenerator', () => ({ generateWorkflowPlan: () => ({ id: 'generated' }) }));
vi.mock('@/lib/credentials/reconcilePlanCredentials', () => ({ reconcilePlanCredentials: (plan: unknown) => plan }));
vi.mock('../useWorkflowLoader', () => ({ markRunAsJustExecuted: vi.fn() }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isPreviewOnly: false, setRunId, workflowId: 'wf-1' }),
}));
vi.mock('next/navigation', () => ({ usePathname: () => mockPathname, useRouter: () => ({ push: routerPush }) }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));

import { useWorkflowExecution } from '../useWorkflowExecution';

function renderExecutionHook() {
  return renderHook(() =>
    useWorkflowExecution({
      workflowId: 'wf-1',
      nodes: [],
      edges: [],
      router: { push: routerPush, replace: vi.fn(), back: vi.fn() } as never,
      setWorkflowStatus: vi.fn(),
      pauseResumeActions: { setMode: vi.fn(), updateReadySteps: vi.fn() },
      onSaveBeforeExecute: async () => JSON.stringify({ id: 'p1' }),
    }),
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.executeWorkflow.mockResolvedValue({ runId: 'run-1', status: 'running' });
  api.getAllCredentials.mockResolvedValue([]);
});
afterEach(() => { vi.restoreAllMocks(); });

describe('useWorkflowExecution - where a started run lands', () => {
  it('routes to the run URL from the workflow own page', async () => {
    mockPathname = '/en/app/workflow/wf-1';
    renderExecutionHook();

    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { workflowId: 'wf-1' } }));

    await waitFor(() => expect(routerPush).toHaveBeenCalledWith('/app/workflow/wf-1/run/run-1'));
    expect(setRunId).not.toHaveBeenCalled();
  });

  it('binds the run in place when the canvas is embedded elsewhere', async () => {
    // A side-panel workflow tab opened from a chat: the app must stay where it is.
    mockPathname = '/en/app/c/conversation-42';
    renderExecutionHook();

    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { workflowId: 'wf-1' } }));

    await waitFor(() => expect(setRunId).toHaveBeenCalledWith('run-1'));
    expect(routerPush, 'the app was not routed away').not.toHaveBeenCalled();
  });

  it('binds in place for a panel opened over ANOTHER workflow page too', async () => {
    mockPathname = '/en/app/workflow/wf-other';
    renderExecutionHook();

    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { workflowId: 'wf-1' } }));

    await waitFor(() => expect(setRunId).toHaveBeenCalledWith('run-1'));
    expect(routerPush).not.toHaveBeenCalled();
  });
});
