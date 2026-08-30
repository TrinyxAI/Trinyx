/**
 * @vitest-environment jsdom
 *
 * A chat preview card clicked while the side panel is collapsed to a strip.
 *
 * The cards ask `isForward` ("is the panel SHOWING this tab"), which a shaded
 * window answers false. Two things then have to hold, and the second one is the
 * trap: the click must not act on a close/destroy state the user cannot see, AND
 * it must land on THIS card's tab. A blanket "un-shade first" call gets the second
 * one wrong, it reveals whichever tab happened to be shaded, and the click the user
 * spent is wasted.
 *
 * The REAL card is rendered against the REAL provider. Nine cards share this handler
 * verbatim; the one that already had a test file is the one driven here, and the
 * scanning invariant test is what keeps the other eight from drifting off the shape.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: vi.fn().mockImplementation(async (id: string) => ({
      id,
      name: id === 'wf-1' ? 'Customer Sync' : 'Invoice Run',
      description: '',
      status: 'draft',
      plan: { triggers: [{ id: 'trigger:manual' }], mcps: [], tables: [], cores: [] },
      nodeIcons: [],
    })),
    deleteWorkflow: vi.fn(),
  },
}));
vi.mock('@/components/app/WorkflowBuilderPanelContent', () => ({
  WorkflowBuilderPanelContent: () => null,
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({
  WorkflowNodeIcons: () => <div />,
}));

import { SidePanelProvider, useSidePanel } from '@/contexts/SidePanelContext';
import { WorkflowPreviewBlock } from '@/components/chat/WorkflowPreviewBlock';

function Probe() {
  const sp = useSidePanel();
  return (
    <>
      <span data-testid="state">{`${sp.isOpen}|${sp.collapsed}|${sp.activeTabId ?? ''}|${sp.tabs.length}`}</span>
      <button type="button" data-testid="collapse" onClick={() => sp.setCollapsed(true)} />
    </>
  );
}

function renderCards() {
  render(
    <SidePanelProvider>
      <Probe />
      <WorkflowPreviewBlock workflowId="wf-1" />
      <WorkflowPreviewBlock workflowId="wf-2" />
    </SidePanelProvider>,
  );
}

const state = () => screen.getByTestId('state').textContent;
const collapse = () => act(() => { screen.getByTestId('collapse').click(); });
async function clickCard(name: string) {
  fireEvent.click(await screen.findByText(name));
}

beforeEach(() => { window.localStorage.clear(); });
afterEach(cleanup);

describe('chat preview card vs a collapsed side panel', () => {
  it('opens THIS card\'s tab in one click, not the one that was shaded', async () => {
    renderCards();
    await clickCard('Customer Sync');
    await waitFor(() => expect(state()).toBe('true|false|workflow-wf-1|1'));
    collapse();

    await clickCard('Invoice Run');

    // One click. An un-shade up front would have spent it revealing wf-1.
    // The card swaps workflow tabs rather than stacking them, hence still one tab.
    await waitFor(() => expect(state()).toBe('true|false|workflow-wf-2|1'));
  });

  it('does not offer its "click to close" affordance while the panel is shaded', async () => {
    // The card would otherwise paint that state over a panel nobody can see, and
    // the click behind it destroys the tab instead of revealing it.
    renderCards();
    await clickCard('Customer Sync');
    await waitFor(() => expect(screen.queryByText('clickToClose')).not.toBeNull());

    collapse();

    expect(screen.queryByText('clickToClose')).toBeNull();
  });

  it('re-reveals its own tab rather than destroying it', async () => {
    // `removeTab` is not recoverable, so the click that would have closed the tab
    // while shaded has to bring it back intact instead.
    renderCards();
    await clickCard('Customer Sync');
    await waitFor(() => expect(state()).toBe('true|false|workflow-wf-1|1'));
    collapse();

    await clickCard('Customer Sync');

    await waitFor(() => expect(state()).toBe('true|false|workflow-wf-1|1'));
  });

  it('still closes the tab when the panel really is showing it', async () => {
    // The un-shaded behaviour must be untouched by all of the above.
    renderCards();
    await clickCard('Customer Sync');
    await waitFor(() => expect(state()).toBe('true|false|workflow-wf-1|1'));

    await clickCard('Customer Sync');

    await waitFor(() => expect(state()).toBe('false|false||0'));
  });
});
