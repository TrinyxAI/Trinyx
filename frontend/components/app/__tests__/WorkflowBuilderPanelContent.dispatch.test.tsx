/**
 * @vitest-environment jsdom
 *
 * Regression: the side-panel workflow (opened from the `+` menu or a chat
 * workflow event WITHOUT a runId prop) must still surface its Trigger and
 * Application sub-tabs once the in-panel canvas enters run mode. The canvas
 * only emits trigger/application configs when it is in run mode (WorkflowBuilder
 * gates them on `isRunMode`), so WorkflowBuilderPanelContent must forward those
 * emissions AS-IS - NOT re-gate them on its own `runId` prop (which is undefined
 * for the `+`-menu / chat-event open path). Pre-fix the prop gate dropped the
 * configs to [] and the Application tab never appeared.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const canvasProps = vi.hoisted(() => ({ current: null as any }));
const panelProps = vi.hoisted(() => ({ current: null as any }));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  // The canvas slot binds the tab's run into its own provider through this hook.
  useWorkflowMode: () => ({ runId: null, setRunId: vi.fn() }),
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  WorkflowRunProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun: vi.fn() } }));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null }));
// WorkflowPanelContent passthrough - captures its props so we can assert the
// runId propagated from the canvas (for the "No template configured" fix).
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: (props: { workflowCanvasSlot?: React.ReactNode; runId?: string }) => {
    panelProps.current = props;
    return <div>{props.workflowCanvasSlot}</div>;
  },
}));
// Capture the canvas props so the test can drive the run-mode config emissions.
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({
  WorkflowRunCanvas: (props: any) => { canvasProps.current = props; return null; },
}));

import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';

describe('WorkflowBuilderPanelContent - forwards run-mode configs without a runId prop', () => {
  afterEach(() => { canvasProps.current = null; panelProps.current = null; cleanup(); });

  it('dispatches application + trigger configs the canvas emits even when opened WITHOUT a runId (in-place run mode)', () => {
    const appEvents: any[] = [];
    const triggerEvents: any[] = [];
    const appHandler = (e: Event) => appEvents.push((e as CustomEvent).detail);
    const triggerHandler = (e: Event) => triggerEvents.push((e as CustomEvent).detail);
    window.addEventListener('workflowPanelApplicationConfigsChange', appHandler);
    window.addEventListener('workflowPanelTriggerDataChange', triggerHandler);

    try {
      // Opened WITHOUT a runId prop - the `+`-menu / chat-event path.
      render(<WorkflowBuilderPanelContent workflowId="wf-1" />);
      expect(canvasProps.current).not.toBeNull();

      // Canvas enters run mode in place and emits its configs.
      act(() => {
        canvasProps.current.onApplicationConfigsChange([
          { interfaceId: 'iface-1', label: 'Search Page', actionMapping: {} },
        ]);
        canvasProps.current.onTriggerConfigsChange({
          configs: [{ triggerId: 'trigger:start', triggerLabel: 'Start', type: 'form' }],
          runStatus: 'WAITING_TRIGGER',
          runId: 'run-inplace-1',
        });
      });

      // The Application configs must reach WorkflowPanelContent NON-empty
      // (pre-fix the missing runId prop forced them to []).
      const lastApp = appEvents[appEvents.length - 1];
      expect(lastApp.workflowId).toBe('wf-1');
      expect(lastApp.configs).toHaveLength(1);
      expect(lastApp.configs[0].interfaceId).toBe('iface-1');

      // Same contract for triggers (symmetry - "comme le trigger").
      const lastTrigger = triggerEvents[triggerEvents.length - 1];
      expect(lastTrigger.configs).toHaveLength(1);
      expect(lastTrigger.configs[0].triggerId).toBe('trigger:start');

      // The canvas's in-place run id must be passed down as WorkflowPanelContent's
      // runId prop - otherwise the interface renders against null and shows
      // "No template configured" (the reported bug for the + menu / chat path).
      expect(panelProps.current).not.toBeNull();
      expect(panelProps.current.runId).toBe('run-inplace-1');
    } finally {
      window.removeEventListener('workflowPanelApplicationConfigsChange', appHandler);
      window.removeEventListener('workflowPanelTriggerDataChange', triggerHandler);
    }
  });

  /**
   * Found by the side-panel application e2e (PANELAPP-001): the tab opened on
   * "no interfaces configured", with no Application sub-tab, even though its
   * host had resolved both pages before mounting this.
   *
   * The dispatch above fires on mount too, so an empty initial state is not
   * neutral - it broadcast [] and wiped the interfaces the host had just handed
   * down. What the host knows has to be in the FIRST emission, not only after
   * the canvas has loaded a plan.
   */
  it('broadcasts the interfaces the host handed down, instead of an empty first emission', () => {
    const appEvents: any[] = [];
    const appHandler = (e: Event) => appEvents.push((e as CustomEvent).detail);
    window.addEventListener('workflowPanelApplicationConfigsChange', appHandler);

    try {
      render(
        <WorkflowBuilderPanelContent
          workflowId="wf-1"
          runId="run-1"
          applicationFirst
          initialApplicationConfigs={[
            { interfaceId: 'iface-entry', label: 'Entry', actionMapping: {}, isEntryInterface: true },
            { interfaceId: 'iface-details', label: 'Details', actionMapping: {} },
          ]}
        />,
      );

      expect(appEvents.length).toBeGreaterThan(0);
      expect(appEvents[0].configs, 'the FIRST emission already carries them').toHaveLength(2);
      expect(appEvents[0].configs[0].interfaceId).toBe('iface-entry');

      // ...and the canvas still owns the list once it has one of its own.
      act(() => {
        canvasProps.current.onApplicationConfigsChange([
          { interfaceId: 'iface-entry', label: 'Entry', actionMapping: {}, nodeId: 'interface:entry' },
        ]);
      });
      const last = appEvents[appEvents.length - 1];
      expect(last.configs).toHaveLength(1);
      expect(last.configs[0].nodeId, 'the canvas list, not the seed').toBe('interface:entry');
    } finally {
      window.removeEventListener('workflowPanelApplicationConfigsChange', appHandler);
    }
  });
});
