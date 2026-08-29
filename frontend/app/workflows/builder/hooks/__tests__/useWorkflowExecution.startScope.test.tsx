// @vitest-environment jsdom
/**
 * A Run addressed to one workflow must not start another one.
 *
 * `workflowViewStart` / `workflowStartStepByStep` are window events and both
 * handlers ignored who they were addressed to. With the right side panel
 * mounting its own canvas (a sub-workflow tab, an application tab), pressing Run
 * in the page header ALSO launched a run of the panel's workflow - billed,
 * visible in its history, started by nobody.
 *
 * The rule is permissive on purpose: an event that names NO workflow still
 * reaches every hook, which is what keeps the e2e fixtures working.
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
vi.mock('@/lib/api/error-utils', () => ({
  is402Error: () => false,
  is413StorageError: () => false,
}));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({
  showInsufficientCreditsModal: vi.fn(),
}));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({
  showInsufficientStorageModal: vi.fn(),
}));
vi.mock('@/lib/billing/ceRelayErrorModals', () => ({ handleCeRelayError: () => false }));
vi.mock('../../utils/workflowPlanGenerator', () => ({
  generateWorkflowPlan: () => ({ id: 'generated' }),
}));
vi.mock('@/lib/credentials/reconcilePlanCredentials', () => ({
  reconcilePlanCredentials: (plan: unknown) => plan,
}));
vi.mock('../useWorkflowLoader', () => ({ markRunAsJustExecuted: vi.fn() }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isPreviewOnly: false, setRunId, workflowId: 'wf-1' }),
}));
vi.mock('next/navigation', () => ({ usePathname: () => mockPathname, useRouter: () => ({ push: routerPush }) }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
}));

import { useWorkflowExecution } from '../useWorkflowExecution';

function renderExecutionHook() {
  return renderHook(() =>
    useWorkflowExecution({
      workflowId: 'wf-1',
      nodes: [],
      edges: [],
      router: { push: vi.fn(), replace: vi.fn(), back: vi.fn() } as never,
      setWorkflowStatus: vi.fn(),
      pauseResumeActions: { setMode: vi.fn(), updateReadySteps: vi.fn() },
      onSaveBeforeExecute: async () => JSON.stringify({ id: 'p1' }),
    })
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockPathname = '/en/app/workflow/wf-1';
  api.executeWorkflow.mockResolvedValue({ runId: 'run-1', status: 'running' });
  api.getAllCredentials.mockResolvedValue([]);
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('useWorkflowExecution - a start event is scoped to its workflow', () => {
  it('starts when workflowViewStart names THIS workflow', async () => {
    renderExecutionHook();

    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { workflowId: 'wf-1' } }));

    await waitFor(() => expect(api.executeWorkflow).toHaveBeenCalledTimes(1));
  });

  it('ignores a workflowViewStart addressed to another workflow', async () => {
    renderExecutionHook();

    // The page header running ITS workflow, while this hook sits in the panel.
    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { workflowId: 'wf-other' } }));

    await new Promise((r) => setTimeout(r, 20));
    expect(api.executeWorkflow).not.toHaveBeenCalled();
  });

  it('starts step-by-step when the event names THIS workflow', async () => {
    // The negative below is only meaningful next to this: a stray `return` in the
    // handler would satisfy the refusal and break the feature silently.
    renderExecutionHook();

    window.dispatchEvent(new CustomEvent('workflowStartStepByStep', { detail: { workflowId: 'wf-1' } }));

    await waitFor(() => expect(api.executeWorkflow).toHaveBeenCalledTimes(1));
    expect(api.executeWorkflow.mock.calls[0][0]).toMatchObject({ executionMode: 'step_by_step' });
  });

  it('ignores a workflowStartStepByStep addressed to another workflow', async () => {
    renderExecutionHook();

    window.dispatchEvent(new CustomEvent('workflowStartStepByStep', { detail: { workflowId: 'wf-other' } }));

    await new Promise((r) => setTimeout(r, 20));
    expect(api.executeWorkflow).not.toHaveBeenCalled();
  });

  it('still starts on an event that names no workflow at all', async () => {
    renderExecutionHook();

    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { startFromNode: 'n1' } }));

    await waitFor(() => expect(api.executeWorkflow).toHaveBeenCalledTimes(1));
  });
});
