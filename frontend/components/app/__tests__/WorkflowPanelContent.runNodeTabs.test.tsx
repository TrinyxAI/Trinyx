/**
 * @vitest-environment jsdom
 *
 * The node palette and the run history are side-panel TABS now, not panels
 * floating over the ReactFlow canvas. This pins their contract inside the
 * workflow / application panel tab bar:
 *   - edit mode (no run bound) shows "Add Node", run mode shows "Run";
 *   - the canvas buttons (history / version chip / "+") focus the right tab;
 *   - an embedded or preview surface never offers run-history navigation.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const runPanelProps = vi.hoisted(() => ({ current: null as any }));
const nodeCreatorProps = vi.hoisted(() => ({ current: null as any }));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('@/components/chat/ChatCore', () => ({ ChatCore: () => <div data-testid="chat" /> }));
vi.mock('@/app/shared/components', () => ({ WelcomeTitle: ({ children }: any) => <>{children}</> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => null,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/ai/NoProviderCta', () => ({ NoProviderCta: () => null }));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => null }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({ ApplicationCarousel: () => null }));
vi.mock('@/hooks/useWorkflowChat', () => ({
  useWorkflowChat: () => ({
    conversationId: null, messages: [], isLoading: false,
    sendMessage: vi.fn(), loadConversation: vi.fn(), stopStream: vi.fn(),
  }),
}));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ models: [], defaultModel: null, isLoading: false, error: null }),
  EMPTY_SELECTED_MODEL: { id: '' },
  modelMatches: () => false,
  selectedModelFromAIModel: () => ({ id: '' }),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({ id: '' }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/contexts/StreamingContext', () => ({ useStreaming: () => ({ isStreamingConversation: () => false }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: any) => <>{children}</>,
  useWorkflowMode: () => ({ isPreviewOnly: false, workflowId: 'wf-1' }),
}));
vi.mock('@/components/workflow/run-panel/RunPanelContent', () => ({
  RunPanelContent: (props: any) => { runPanelProps.current = props; return <div data-testid="run-panel" />; },
}));
vi.mock('@/components/app/NodeCreatorPanelContent', () => ({
  NodeCreatorPanelContent: (props: any) => { nodeCreatorProps.current = props; return <div data-testid="node-creator" />; },
}));

import { panelTabClass } from '@/components/ui/panel-tab';
import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import {
  clearRunPanelCache,
  makeEmptyRunPanelData,
  openNodeCreatorPanel,
  openRunPanel,
  publishRunPanelData,
} from '@/components/workflow/run-panel/runPanelBus';

function publishRun(overrides: Record<string, unknown> = {}) {
  act(() => {
    publishRunPanelData({
      ...makeEmptyRunPanelData('wf-1'),
      runId: 'run-1',
      runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 },
      currentEpoch: 1,
      ...overrides,
    } as any);
  });
}

beforeEach(() => clearRunPanelCache());
afterEach(() => {
  clearRunPanelCache();
  runPanelProps.current = null;
  nodeCreatorProps.current = null;
  cleanup();
});

describe('WorkflowPanelContent - Run / Add Node sub-tabs', () => {
  // This is the only fixture that binds a run, so it is the only place the Run
  // tab renders at all: its shared styling and aria state can be pinned here
  // and nowhere else.
  it('gives the Run tab the same shared sub-tab style and aria state as every other tab', () => {
    publishRun();
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

    const runTab = screen.getByText('sidePanel.runTab').closest('button')!;
    expect(runTab.getAttribute('data-testid')).toBe('panel-sub-tab');
    expect(runTab.className).toContain(panelTabClass(false, 'sm'));
    expect(runTab.getAttribute('aria-pressed')).toBe('false');

    act(() => { runTab.click(); });

    const active = screen.getByText('sidePanel.runTab').closest('button')!;
    expect(active.getAttribute('aria-pressed')).toBe('true');
    expect(active.className).toContain(panelTabClass(true, 'sm'));
    expect(active.className.split(/\s+/)).toContain('bg-[var(--bg-hover)]');
    // Every rendered sub-tab is reachable through the shared marker.
    expect(container.querySelectorAll('[data-testid="panel-sub-tab"]').length)
      .toBe(container.querySelectorAll('button[aria-pressed]').length);
  });

  it('offers Add Node while editing and Run once a run is bound', () => {
    const { rerender } = render(<WorkflowPanelContent workflowId="wf-1" />);
    // Edit mode: the palette tab, no run tab.
    expect(screen.getByText('workflowBuilder.canvas.addNode')).toBeTruthy();
    expect(screen.queryByText('sidePanel.runTab')).toBeNull();

    publishRun();
    rerender(<WorkflowPanelContent workflowId="wf-1" />);

    // Run mode: the run tab replaces the palette (the canvas is read-only then).
    expect(screen.getByText('sidePanel.runTab')).toBeTruthy();
    expect(screen.queryByText('workflowBuilder.canvas.addNode')).toBeNull();
  });

  it('renders the palette when the Add Node tab is selected', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => { screen.getByText('workflowBuilder.canvas.addNode').click(); });
    expect(screen.getByTestId('node-creator')).toBeTruthy();
    expect(nodeCreatorProps.current.workflowId).toBe('wf-1');
  });

  it('focuses the palette when the canvas "+" fires while this panel is already showing', () => {
    // The page-level handler cannot serve this case: nothing remounts, so only
    // this in-panel listener can react. Without it the click was swallowed.
    render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(screen.queryByTestId('node-creator')).toBeNull();

    act(() => openNodeCreatorPanel({ workflowId: 'wf-1' }));

    expect(screen.getByTestId('node-creator')).toBeTruthy();
  });

  it('ignores a palette request aimed at another workflow', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openNodeCreatorPanel({ workflowId: 'wf-other' }));
    expect(screen.queryByTestId('node-creator')).toBeNull();
  });

  it('focuses the Run tab on the requested level when the canvas asks for it', () => {
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(screen.queryByTestId('run-panel')).toBeNull();

    act(() => openRunPanel({ workflowId: 'wf-1', view: 'history' }));

    expect(screen.getByTestId('run-panel')).toBeTruthy();
    expect(runPanelProps.current.viewRequest.view).toBe('history');

    // The run-detail shortcut (panel button on the canvas bar) lands on 'run'.
    act(() => openRunPanel({ workflowId: 'wf-1', view: 'run' }));
    expect(runPanelProps.current.viewRequest.view).toBe('run');
  });

  it('ignores an open request addressed to another workflow (sub-workflow tabs)', () => {
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openRunPanel({ workflowId: 'wf-other', view: 'history' }));
    expect(screen.queryByTestId('run-panel')).toBeNull();
  });

  it('allows run-history navigation on the standalone workflow page only', () => {
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.allowHistory).toBe(true);

    cleanup();

    // Embedded canvas (application panel / sub-workflow tab): the run is fixed
    // by the host, so the Run tab stays on that run's detail.
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.allowHistory).toBe(false);
  });

  it('keeps the Run tab in marketplace preview but never the palette', () => {
    publishRun({ isPreviewOnly: true });
    render(<WorkflowPanelContent workflowId="wf-1" isPreviewOnly workflowCanvasSlot={<div />} />);
    expect(screen.getByText('sidePanel.runTab')).toBeTruthy();
    expect(screen.queryByText('workflowBuilder.canvas.addNode')).toBeNull();
  });

  it('falls back off the Run tab when the run disappears (back to edit mode)', () => {
    publishRun();
    const { rerender } = render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(screen.getByTestId('run-panel')).toBeTruthy();

    act(() => {
      publishRunPanelData({ ...makeEmptyRunPanelData('wf-1') } as any);
    });
    rerender(<WorkflowPanelContent workflowId="wf-1" />);

    expect(screen.queryByTestId('run-panel')).toBeNull();
    expect(screen.getByTestId('chat')).toBeTruthy();
  });
});
