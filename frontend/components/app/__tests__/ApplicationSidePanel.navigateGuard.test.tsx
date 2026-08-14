/**
 * @vitest-environment jsdom
 *
 * The publication side panel had NO navigate branch at all.
 *
 * A page-switch link (`interface:<label>:navigate`) reaching its action handler fell
 * through to the trigger parser, which strips the last segment and fires the remainder:
 * `executeStep(runId, 'interface:<label>', data, 'manual')` - a real backend call from
 * what the author wrote as a navigation link, on a surface that renders ONE interface
 * and has nowhere to navigate to.
 *
 * This pins the guard. It is the only change in the set that removes a backend call on
 * a legitimate page switch, so it is the one most worth a test.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as React from 'react';
import { render, waitFor, act } from '@testing-library/react';

const executeStep = vi.hoisted(() => vi.fn().mockResolvedValue({}));
/** The onAction the panel hands to ApplicationTabContent. */
const onActionRef = vi.hoisted(() => ({
  current: undefined as ((ref: string, data: Record<string, unknown>) => Promise<void> | void) | undefined,
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getPublicationById: vi.fn().mockResolvedValue({
      id: 'pub-1', workflowId: 'wf-1', title: 'App', showcaseInterfaceId: 'iface-1',
      planSnapshot: { interfaces: [{ id: 'iface-1', label: 'Home', isEntryInterface: true, actionMapping: {} }] },
    }),
    getAcquiredApplications: vi.fn().mockResolvedValue({ applications: [] }),
  },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: {
    getApplicationRun: vi.fn().mockResolvedValue({ runId: 'run_abc' }),
    executeWorkflow: vi.fn(),
  },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getWorkflow: vi.fn().mockResolvedValue({ plan: { interfaces: [] } }) } }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({ useRun: () => [null, { executeStep }] }));
vi.mock('@/app/workflows/builder/hooks/execution', () => ({ useWorkflowStreaming: () => undefined }));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
  usePublicationSnapshot: () => null,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span /> }));
vi.mock('@/components/chat/ApplicationTabContent', () => ({
  ApplicationTabContent: (props: { onAction: (ref: string, data: Record<string, unknown>) => Promise<void> | void }) => {
    onActionRef.current = props.onAction;
    return <div data-testid="app-tab-stub" />;
  },
}));

import { ApplicationPanelContent } from '../ApplicationSidePanel';

describe('ApplicationSidePanel - navigate is not a trigger fire', () => {
  beforeEach(() => {
    executeStep.mockClear();
    onActionRef.current = undefined;
  });

  it('does not fire a trigger for a page-switch ref', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(onActionRef.current).toBeDefined());

    await act(async () => {
      await onActionRef.current?.('interface:details:navigate', {});
    });

    // Pre-guard this called executeStep(runId, 'interface:details', {}, 'manual').
    expect(executeStep).not.toHaveBeenCalled();
  });

  it('does not fire for the legacy trigger-prefixed page switch either', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(onActionRef.current).toBeDefined());

    await act(async () => {
      await onActionRef.current?.('trigger:details:navigate', {});
    });

    expect(executeStep).not.toHaveBeenCalled();
  });

  it('still fires a real trigger', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(onActionRef.current).toBeDefined());

    await act(async () => {
      await onActionRef.current?.('trigger:my_form:submit', { city: 'Lyon' });
    });

    expect(executeStep).toHaveBeenCalledWith('run_abc', 'trigger:my_form', { city: 'Lyon' }, 'form');
  });
});
