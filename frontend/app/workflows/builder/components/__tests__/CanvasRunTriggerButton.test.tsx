// @vitest-environment jsdom
/**
 * Run-mode toolbar trigger picker: choosing a trigger must do exactly what that
 * trigger's own play button does - fire no-payload triggers straight away, and
 * hand payload triggers (chat / form / webhook) over to their side-panel tab.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

const mockExecuteStep = vi.fn();
const mockCanExecuteStep = vi.fn();
const mockSetViewingEpoch = vi.fn();
let mockCtx: Record<string, unknown> | null;

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ setViewingEpoch: mockSetViewingEpoch }),
}));
// Only the context accessor is faked: computeBackendStepId stays REAL so the
// emitted triggerId is the true `trigger:<normalized_label>` the panels match on.
vi.mock('../../contexts/StepByStepContext', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../contexts/StepByStepContext')>()),
  useStepByStep: () => mockCtx,
}));
// deriveNodeContextFlags stays REAL (the trigger-type derivation is what these
// tests exercise); only its heavy side-panel imports are stubbed out.
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONVERSATION_TAB: 'conversation',
  AGENT_CONFIGURATION_TAB: 'configuration',
}));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({ openFilesPanel: vi.fn() }));

import { CanvasRunTriggerButton } from '../CanvasRunTriggerButton';
import { computeBackendStepId } from '../../contexts/StepByStepContext';
import { normalizeLabel } from '../../utils/labelNormalizer';

const triggerNode = (id: string, classId: string, label: string) => ({
  id,
  position: { x: 0, y: 0 },
  data: { id: classId, kind: 'entry', label },
}) as never;

const openMenu = () => fireEvent.click(screen.getByTestId('canvas-run-trigger-button'));

beforeEach(() => {
  mockExecuteStep.mockReset().mockResolvedValue(undefined);
  mockCanExecuteStep.mockReset().mockReturnValue(true);
  mockSetViewingEpoch.mockReset();
  mockCtx = {
    // Mirrors the provider: no backend mapping yet, so it falls back to the
    // REAL computeBackendStepId - the id format is part of what is under test.
    resolveNodeId: (nodeId: string, data: { label?: string; kind?: string }) =>
      computeBackendStepId(nodeId, data),
    canExecuteStep: mockCanExecuteStep,
    executeStep: mockExecuteStep,
  };
});

describe('CanvasRunTriggerButton', () => {
  it('renders nothing when the workflow has no trigger node', () => {
    const nodes = [{ id: 'n1', position: { x: 0, y: 0 }, data: { id: 'transform', kind: 'core', label: 'Shape' } }] as never[];
    const { container } = render(<CanvasRunTriggerButton nodes={nodes} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders nothing outside a run (no step-by-step context available)', () => {
    mockCtx = null;
    const { container } = render(
      <CanvasRunTriggerButton nodes={[triggerNode('n1', 'manual-trigger', 'Start')]} />,
    );
    expect(container.innerHTML).toBe('');
  });

  it('lists every trigger of the workflow in the menu', () => {
    render(
      <CanvasRunTriggerButton
        nodes={[
          triggerNode('n1', 'manual-trigger', 'Start'),
          triggerNode('n2', 'chat-trigger', 'Support Chat'),
          triggerNode('n3', 'schedule-trigger', 'Nightly'),
        ]}
      />,
    );
    openMenu();
    expect(screen.getAllByRole('menuitem')).toHaveLength(3);
    expect(screen.getByText('Start')).toBeTruthy();
    expect(screen.getByText('Support Chat')).toBeTruthy();
    expect(screen.getByText('Nightly')).toBeTruthy();
  });

  it('fires a no-payload trigger against all epochs and leaves the focused epoch', () => {
    render(<CanvasRunTriggerButton nodes={[triggerNode('n1', 'manual-trigger', 'Start')]} />);
    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Start' }));

    // epoch=undefined: firing targets the run, never the epoch being inspected.
    expect(mockExecuteStep).toHaveBeenCalledWith('trigger:start', undefined);
    expect(mockSetViewingEpoch).toHaveBeenCalledWith(null);
  });

  it('routes a chat trigger to its side-panel tab, naming the exact trigger', () => {
    const detail: unknown[] = [];
    const listener = (e: Event) => detail.push((e as CustomEvent).detail);
    window.addEventListener('workflowOpenTriggerTab', listener);
    try {
      render(<CanvasRunTriggerButton nodes={[triggerNode('n2', 'chat-trigger', 'Support Chat')]} />);
      openMenu();
      fireEvent.click(screen.getByRole('menuitem', { name: 'Support Chat' }));
    } finally {
      window.removeEventListener('workflowOpenTriggerTab', listener);
    }

    expect(detail).toEqual([
      { nodeId: 'n2', triggerId: 'trigger:support_chat', triggerType: 'chat' },
    ]);
    // A chat trigger needs a message: it must NOT be fired blind.
    expect(mockExecuteStep).not.toHaveBeenCalled();
  });

  it.each([
    ['form-trigger', 'Signup', 'form'],
    ['webhook-trigger', 'Inbound', 'webhook'],
  ])('routes a %s to its %s tab instead of firing it', (classId, label, tab) => {
    const detail: unknown[] = [];
    const listener = (e: Event) => detail.push((e as CustomEvent).detail);
    window.addEventListener('workflowOpenTriggerTab', listener);
    try {
      render(<CanvasRunTriggerButton nodes={[triggerNode('n3', classId, label)]} />);
      openMenu();
      fireEvent.click(screen.getByRole('menuitem', { name: label }));
    } finally {
      window.removeEventListener('workflowOpenTriggerTab', listener);
    }

    expect(detail).toEqual([{ nodeId: 'n3', triggerId: `trigger:${label.toLowerCase()}`, triggerType: tab }]);
    expect(mockExecuteStep).not.toHaveBeenCalled();
  });

  it('disables a trigger that cannot be fired right now', () => {
    mockCanExecuteStep.mockImplementation((stepId: string) => stepId !== 'trigger:nightly');
    render(
      <CanvasRunTriggerButton
        nodes={[triggerNode('n1', 'manual-trigger', 'Start'), triggerNode('n3', 'schedule-trigger', 'Nightly')]}
      />,
    );
    openMenu();

    const blocked = screen.getByRole('menuitem', { name: 'Nightly' });
    expect(blocked.getAttribute('aria-disabled')).toBe('true');
    expect(screen.getByRole('menuitem', { name: 'Start' }).getAttribute('aria-disabled')).toBe('false');
    // aria-disabled keeps the element event-eligible so the reason can surface;
    // the click must still be inert.
    expect(blocked.getAttribute('title')).toBe('triggerNotReady');

    fireEvent.click(blocked);
    expect(mockExecuteStep).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('menuitem', { name: 'Start' }));
    expect(mockExecuteStep).toHaveBeenCalledWith('trigger:start', undefined);
  });

  it('closes the menu after a trigger is picked', () => {
    render(<CanvasRunTriggerButton nodes={[triggerNode('n1', 'manual-trigger', 'Start')]} />);
    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Start' }));
    expect(screen.queryByRole('menuitem')).toBeNull();
  });

  it('closes the menu on Escape without firing anything', () => {
    render(<CanvasRunTriggerButton nodes={[triggerNode('n1', 'manual-trigger', 'Start')]} />);
    openMenu();
    expect(screen.getByRole('menu')).toBeTruthy();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(mockExecuteStep).not.toHaveBeenCalled();
  });

  it('closes the menu when the same button is clicked again', () => {
    render(<CanvasRunTriggerButton nodes={[triggerNode('n1', 'manual-trigger', 'Start')]} />);
    openMenu();
    expect(screen.getByRole('menu')).toBeTruthy();

    openMenu();
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('closes the menu when the pointer goes down outside it', () => {
    // Capture-phase listener: the React Flow pane stops propagation, so a
    // bubbling listener would leave the menu stuck open over the canvas.
    render(<CanvasRunTriggerButton nodes={[triggerNode('n1', 'manual-trigger', 'Start')]} />);
    openMenu();

    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('keeps the menu open when the pointer goes down inside it', () => {
    render(<CanvasRunTriggerButton nodes={[triggerNode('n1', 'manual-trigger', 'Start')]} />);
    openMenu();

    fireEvent.pointerDown(screen.getByRole('menu'));
    expect(screen.getByRole('menu')).toBeTruthy();
  });

  it('hides itself entirely when no trigger can be fired (terminal run)', () => {
    // Matches the per-node play buttons, which disappear on a terminal run - an
    // all-greyed menu behind an enabled Play would be a dead end.
    mockCanExecuteStep.mockReturnValue(false);
    const { container } = render(
      <CanvasRunTriggerButton
        nodes={[triggerNode('n1', 'manual-trigger', 'Start'), triggerNode('n2', 'chat-trigger', 'Support Chat')]}
      />,
    );
    expect(container.innerHTML).toBe('');
  });

  it('emits the same trigger id format the side panel matches tabs on', () => {
    // The panel keys its tabs on `trigger:${normalizeLabel(label)}`; if the two
    // formats ever diverge the feature silently degrades to type matching.
    const detail: Array<{ triggerId?: string }> = [];
    const listener = (e: Event) => detail.push((e as CustomEvent).detail);
    window.addEventListener('workflowOpenTriggerTab', listener);
    try {
      render(<CanvasRunTriggerButton nodes={[triggerNode('n2', 'chat-trigger', 'Réponse Client')]} />);
      openMenu();
      fireEvent.click(screen.getByRole('menuitem', { name: 'Réponse Client' }));
    } finally {
      window.removeEventListener('workflowOpenTriggerTab', listener);
    }

    expect(detail[0]?.triggerId).toBe(`trigger:${normalizeLabel('Réponse Client')}`);
    expect(detail[0]?.triggerId).toBe('trigger:reponse_client');
  });
});
