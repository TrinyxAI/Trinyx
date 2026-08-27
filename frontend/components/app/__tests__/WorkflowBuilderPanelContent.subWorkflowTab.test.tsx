/**
 * @vitest-environment jsdom
 *
 * This panel carries a SECOND `workflowOpenSubWorkflow` listener, twin of the one
 * in WorkflowDetailView. Both sit on `window`, and sub-workflow tabs are opened
 * `keepMounted`, so once one is open BOTH handlers answer the same click. They
 * must therefore build the SAME tab id: openTab merges on the id, so two ids mean
 * two tabs for one sub-workflow, the second stealing focus.
 *
 * Pinning the id here and in WorkflowDetailView.subWorkflowTab.test.tsx keeps the
 * twins in agreement; tabResource.noHandBuiltIds.test.ts stops a third one from
 * appearing.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const openTab = vi.hoisted(() => vi.fn());
const getPinnedWorkflowRun = vi.hoisted(() => vi.fn());

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ runId: null, setRunId: vi.fn() }),
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  WorkflowRunProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => ({ openTab, tabs: [] }) }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun } }));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null }));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: (props: { workflowCanvasSlot?: React.ReactNode }) => <div>{props.workflowCanvasSlot}</div>,
}));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({ WorkflowRunCanvas: () => null }));

import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';

const WF = 'f54f378a-c4ff-4398-a003-107c87e9f2a6';
const SUB_WF = 'ef1d124a-610b-4c6b-b1d8-8fb6a6f20604';
const RUN = '9c3f1b2e-77aa-4d61-9d0e-51d2b6a4c8f0';

afterEach(() => { openTab.mockReset(); getPinnedWorkflowRun.mockReset(); cleanup(); });

async function openSubWorkflowFromPanel() {
  render(<WorkflowBuilderPanelContent workflowId={WF} />);
  await act(async () => {
    window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', {
      detail: { workflowId: SUB_WF, workflowName: 'Sub', nodeId: 'node-1' },
    }));
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('WorkflowBuilderPanelContent - opening a nested sub-workflow', () => {
  it('uses the workflow own id, the same one WorkflowDetailView uses', async () => {
    getPinnedWorkflowRun.mockResolvedValue(null);

    await openSubWorkflowFromPanel();

    expect(openTab).toHaveBeenCalledTimes(1);
    // Pre-fix this listener kept building 'workflow-builder-<id>' while its twin
    // had moved to 'workflow-<id>': one click, two tabs for one sub-workflow.
    expect(openTab.mock.calls[0][0].id).toBe(`workflow-${SUB_WF}`);
    expect(openTab.mock.calls[0][0].id).not.toContain('builder-');
  });

  it('uses the run-scoped id when the sub-workflow has a pinned run', async () => {
    getPinnedWorkflowRun.mockResolvedValue({ runId: RUN });

    await openSubWorkflowFromPanel();

    expect(openTab.mock.calls[0][0].id).toBe(`workflow-run-${SUB_WF}-${RUN}`);
  });
});
