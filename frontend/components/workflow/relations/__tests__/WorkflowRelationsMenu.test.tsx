// @vitest-environment jsdom
/**
 * The sub-workflow relations menu: "who calls this workflow, and who does it call?".
 *
 * What has to hold:
 *  - a workflow with NO neighbour renders nothing at all, because the control's presence IS the
 *    indicator the card grids show (a button onto an empty list would be a dead affordance);
 *  - a relation that cannot be resolved is SHOWN but not clickable - dropping it would hide the
 *    fact that a plan calls something the viewer no longer has;
 *  - the trigger does not bubble, because card footers sit inside a clickable card;
 *  - a caller that already resolved the relations (a grid, in one batch call) never triggers a
 *    per-card fetch behind its own batch.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

// Radix's Popover cannot position itself in jsdom; render both halves inline and surface the
// open state as an attribute so "the menu closed on pick" stays observable.
vi.mock('@/components/ui/popover', () => {
  // Enough of Radix to be honest: the trigger really toggles `open` through `onOpenChange`, so
  // "the menu closes when you pick a workflow" is observable rather than assumed. Positioning is
  // the only part dropped - both halves render inline.
  const Popover = ({ open, onOpenChange, children }: any) => (
    <div data-testid="popover" data-popover-open={open ? 'true' : 'false'}>
      {React.Children.map(children, (child) =>
        React.isValidElement(child) ? React.cloneElement(child as any, { onOpenChange }) : child)}
    </div>
  );
  const PopoverTrigger = ({ children, onOpenChange }: any) =>
    React.cloneElement(children, {
      onClick: (e: React.MouseEvent) => {
        children.props.onClick?.(e);
        onOpenChange?.(true);
      },
    });
  const PopoverContent = ({ children }: any) => <div>{children}</div>;
  return { Popover, PopoverTrigger, PopoverContent };
});

vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));

const openWorkflowBuilderTab = vi.fn();
vi.mock('@/lib/sidePanel/openWorkflowBuilderTab', () => ({
  openWorkflowBuilderTab: (...args: unknown[]) => openWorkflowBuilderTab(...args),
}));

const useWorkflowRelations = vi.fn(() => ({ relations: { parents: [], children: [] }, isLoading: false }));
vi.mock('../useWorkflowRelations', () => ({
  useWorkflowRelations: (...args: unknown[]) => useWorkflowRelations(...(args as [])),
}));

import { WorkflowRelationsMenu } from '../WorkflowRelationsMenu';
import { WorkflowRelationsAutoMenu } from '../WorkflowRelationsAutoMenu';

const parent = { id: 'wf-parent', name: 'Nightly orchestrator', resolved: true };
const child = { id: 'wf-child', name: 'Enricher', resolved: true };
const gone = { id: 'wf-gone', name: null, resolved: false };

beforeEach(() => {
  openWorkflowBuilderTab.mockClear();
  useWorkflowRelations.mockClear();
  useWorkflowRelations.mockReturnValue({ relations: { parents: [], children: [] }, isLoading: false });
});

describe('WorkflowRelationsMenu', () => {
  it('renders nothing for a workflow with no sub-workflow neighbour', () => {
    const { container } = render(
      <WorkflowRelationsMenu relations={{ parents: [], children: [] }} />,
    );
    expect(container.innerHTML).toBe('');
  });

  it('carries the count in its accessible name, never painted on the button', () => {
    // The number used to sit on the control and read as a notification badge - something unread
    // that wants clearing. It is neither: it changes nothing about what the button does. So the
    // button is a bare square icon, and the count reaches a screen reader and the tooltip.
    render(<WorkflowRelationsMenu relations={{ parents: [parent], children: [child, gone] }} />);
    const trigger = screen.getByTestId('workflow-relations-trigger');

    expect(trigger.textContent).toBe('');
    expect(trigger.getAttribute('aria-label')).toBe('menuLabel');
    expect(trigger.getAttribute('title')).toBe('menuLabel');
  });

  it('lists callers and callees under their own sections', () => {
    render(<WorkflowRelationsMenu relations={{ parents: [parent], children: [child] }} />);

    expect(screen.getByTestId('workflow-relations-parents').textContent).toContain('Nightly orchestrator');
    expect(screen.getByTestId('workflow-relations-children').textContent).toContain('Enricher');
  });

  it('omits a section entirely when that direction is empty', () => {
    render(<WorkflowRelationsMenu relations={{ parents: [], children: [child] }} />);

    expect(screen.queryByTestId('workflow-relations-parents')).toBeNull();
    expect(screen.getByTestId('workflow-relations-children')).not.toBeNull();
  });

  it('opens the picked workflow in the side panel by default, and closes the menu', () => {
    render(<WorkflowRelationsMenu relations={{ parents: [parent], children: [] }} />);
    fireEvent.click(screen.getByTestId('workflow-relations-trigger'));
    expect(screen.getByTestId('popover').getAttribute('data-popover-open')).toBe('true');

    fireEvent.click(screen.getByText('Nightly orchestrator'));

    expect(openWorkflowBuilderTab).toHaveBeenCalledWith(null, {
      workflowId: 'wf-parent',
      workflowName: 'Nightly orchestrator',
    });
    expect(screen.getByTestId('popover').getAttribute('data-popover-open')).toBe('false');
  });

  it('hands the pick to the caller instead when one is given', () => {
    // Inside a workflow view the pick goes through the view, which resolves the target's pinned
    // RUN first - so the default side-panel open must NOT also fire.
    const onOpen = vi.fn();
    render(<WorkflowRelationsMenu relations={{ parents: [parent], children: [] }} onOpen={onOpen} />);

    fireEvent.click(screen.getByText('Nightly orchestrator'));

    expect(onOpen).toHaveBeenCalledWith(parent);
    expect(openWorkflowBuilderTab).not.toHaveBeenCalled();
  });

  it('shows an unresolvable relation as an unavailable, unclickable row', () => {
    const onOpen = vi.fn();
    render(<WorkflowRelationsMenu relations={{ parents: [], children: [gone] }} onOpen={onOpen} />);

    const row = screen.getByText('unavailable').closest('button') as HTMLButtonElement;
    expect(row.disabled).toBe(true);

    fireEvent.click(row);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('is the square icon control the rest of the app uses, not a chip', () => {
    render(<WorkflowRelationsMenu relations={{ parents: [parent], children: [] }} />);
    const trigger = screen.getByTestId('workflow-relations-trigger');

    expect(trigger.className).toContain('h-7');
    expect(trigger.className).toContain('w-7');
    expect(trigger.className).toContain('rounded-xl');
  });

  it('does not let opening the menu also open the card it sits in', () => {
    const cardClick = vi.fn();
    render(
      <div onClick={cardClick}>
        <WorkflowRelationsMenu relations={{ parents: [parent], children: [] }} />
      </div>,
    );

    fireEvent.click(screen.getByTestId('workflow-relations-trigger'));

    expect(cardClick).not.toHaveBeenCalled();
  });

  it('never resolves anything itself - a grid hands it a slice of ONE batched call', () => {
    // The split that keeps this true: resolving lives in WorkflowRelationsAutoMenu, so a card grid
    // rendering this neither fires a read per card nor needs the query provider to render at all.
    render(<WorkflowRelationsMenu relations={{ parents: [parent], children: [] }} />);

    expect(useWorkflowRelations).not.toHaveBeenCalled();
  });

  it('renders nothing while the relations are still unresolved', () => {
    const { container } = render(<WorkflowRelationsMenu relations={undefined} />);

    expect(container.innerHTML).toBe('');
  });
});

describe('WorkflowRelationsAutoMenu', () => {
  it('resolves the workflow it is given and renders the menu from it', () => {
    useWorkflowRelations.mockReturnValue({ relations: { parents: [parent], children: [] }, isLoading: false });

    render(<WorkflowRelationsAutoMenu workflowId="wf-1" />);

    expect(useWorkflowRelations).toHaveBeenCalledWith('wf-1');
    expect(screen.getByTestId('workflow-relations-trigger')).not.toBeNull();
  });

  it('renders nothing for a workflow whose neighbourhood came back empty', () => {
    const { container } = render(<WorkflowRelationsAutoMenu workflowId="wf-1" />);

    expect(container.innerHTML).toBe('');
  });
});
