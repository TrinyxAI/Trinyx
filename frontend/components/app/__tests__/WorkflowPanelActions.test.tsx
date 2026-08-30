/**
 * @vitest-environment jsdom
 *
 * Share / Save / Run for a workflow that lives in the right side panel.
 *
 * These three are in the page header, gated on being ON a workflow page. A
 * workflow opened as a panel tab is not, so the header showed nothing for it and
 * the canvas offered no replacement: it could be edited and not saved, and there
 * was no way to run it or publish it. This is the same three controls, in the
 * panel's sub-tab bar.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

let mockCanMutate: boolean;
const getWorkflow = vi.hoisted(() => vi.fn());
const publishModalProps = vi.hoisted(() => ({ current: null as Record<string, any> | null }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => mockCanMutate }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getWorkflow } }));
vi.mock('@/components/workflow/WorkflowVersionHistory', () => ({
  WorkflowSaveWithVersions: (props: Record<string, any>) => (
    <button data-testid="save" data-placement={props.placement} onClick={props.onSave}>save</button>
  ),
}));
vi.mock('@/components/workflow/WorkflowRunSplitButton', () => ({
  WorkflowRunSplitButton: () => <div data-testid="run" />,
}));
vi.mock('@/components/workflow/ShareWorkflowModal', () => ({
  PublishWorkflowModal: (props: Record<string, any>) => {
    publishModalProps.current = props;
    return props.isOpen ? <div data-testid="publish-modal" /> : null;
  },
}));

import { WorkflowPanelActions } from '../WorkflowPanelActions';

/** Edit mode, editable workspace, live workflow - the common case. */
function renderActions(overrides: { isRunMode?: boolean; isPreviewOnly?: boolean; canEdit?: boolean } = {}) {
  return render(
    <WorkflowPanelActions
      workflowId="wf-1"
      isRunMode={overrides.isRunMode ?? false}
      isPreviewOnly={overrides.isPreviewOnly ?? false}
      canEdit={overrides.canEdit}
    />,
  );
}

beforeEach(() => {
  mockCanMutate = true;
  publishModalProps.current = null;
  getWorkflow.mockReset();
  getWorkflow.mockResolvedValue({ name: 'My workflow', description: 'What it does' });
});
afterEach(cleanup);

describe('WorkflowPanelActions', () => {
  it('offers the three header controls in edit mode', () => {
    renderActions();

    expect(screen.getByTestId('panel-action-share')).toBeTruthy();
    expect(screen.getByTestId('save')).toBeTruthy();
    expect(screen.getByTestId('panel-action-run')).toBeTruthy();
  });

  it('drops Run in run mode, where the canvas fires triggers and the Run tab holds the run', () => {
    renderActions({ isRunMode: true });

    expect(screen.queryByTestId('panel-action-run')).toBeNull();
    // Save stays: it is the version history too, and step-by-step still saves.
    expect(screen.getByTestId('save')).toBeTruthy();
  });

  it('drops Run for a read-only VIEWER, whose execute would 403', () => {
    mockCanMutate = false;
    renderActions();

    expect(screen.queryByTestId('panel-action-run')).toBeNull();
  });

  it('renders nothing on a workflow that is not the caller to change', () => {
    // An application panel resolves a publication the caller has not acquired to
    // the PUBLISHER's workflow: Share would publish someone else's work, and the
    // version controls would be refused.
    const { container } = renderActions({ canEdit: false });

    expect(container.textContent).toBe('');
  });

  it('renders nothing at all in a marketplace preview', () => {
    const { container } = renderActions({ isRunMode: true, isPreviewOnly: true });

    expect(container.textContent).toBe('');
  });

  it('asks THIS workflow to save', () => {
    const saves: CustomEvent[] = [];
    window.addEventListener('workflowViewSave', (e) => saves.push(e as CustomEvent));
    renderActions();

    fireEvent.click(screen.getByTestId('save'));

    expect(saves).toHaveLength(1);
    expect(saves[0].detail).toEqual({ workflowId: 'wf-1' });
  });

  it('opens the version list upward: the bar is the panel last row', () => {
    renderActions();

    expect(screen.getByTestId('save').getAttribute('data-placement')).toBe('above');
  });

  it('fetches the workflow metadata only when Share is opened, and prefills the wizard with it', async () => {
    renderActions();
    expect(getWorkflow).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('panel-action-share'));

    expect(screen.getByTestId('publish-modal')).toBeTruthy();
    await waitFor(() => expect(publishModalProps.current?.workflowName).toBe('My workflow'));
    expect(publishModalProps.current?.workflowDescription).toBe('What it does');
    expect(getWorkflow).toHaveBeenCalledTimes(1);
  });

  it('still opens the publish wizard when the metadata fetch fails', async () => {
    getWorkflow.mockRejectedValue(new Error('nope'));
    renderActions();

    fireEvent.click(screen.getByTestId('panel-action-share'));

    expect(screen.getByTestId('publish-modal')).toBeTruthy();
    await waitFor(() => expect(publishModalProps.current?.workflowName).toBe(''));
  });
});
