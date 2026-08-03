/**
 * @vitest-environment jsdom
 *
 * The sub-workflow side-panel tab is opened on ONE run (the sub-workflow's
 * pinned production run) and used to stop there: its Run tab was denied the run
 * history, so the run detail had no back arrow and no way to reach any other run
 * of that workflow. Allowing it only means something if the pick actually
 * reaches the canvas - which lives under this component's OWN
 * WorkflowModeProvider, not the page's - and if the rest of the tab then follows
 * the canvas wherever it goes next.
 *
 * The WorkflowModeProvider mock here is a REAL context with real state, because
 * every interesting case is about who writes that run id and who wins: the pick,
 * the canvas' own Edit/Run toggle, a workspace switch, or another surface
 * showing the same workflow.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const providerCalls = vi.hoisted(() => [] as Array<{ workflowId?: string; initialRunId?: string }>);
const providerMounts = vi.hoisted(() => [] as Array<{ workflowId?: string; initialRunId?: string }>);
const panelProps = vi.hoisted(() => ({ current: null as any }));
const canvasProps = vi.hoisted(() => ({ current: null as any }));
/** Handle on the innermost (canvas) provider, so a test can play the canvas. */
const canvasMode = vi.hoisted(() => ({ current: null as null | { runId: string | null; setRunId: (id: string | null) => void } }));
const setCanvasRunId = vi.hoisted(() => vi.fn());
/** The org-switch reset callback this component registers. */
const orgResetCallback = vi.hoisted(() => ({ current: null as null | (() => void) }));

vi.mock('@/contexts/WorkflowModeContext', async () => {
  const R = await import('react');
  const Ctx = R.createContext<any>(null);
  return {
    WorkflowModeProvider: ({ children, workflowId, initialRunId }: any) => {
      providerCalls.push({ workflowId, initialRunId });
      const [runId, setRunIdState] = R.useState<string | null>(initialRunId ?? null);
      R.useEffect(() => { providerMounts.push({ workflowId, initialRunId }); }, []);
      const setRunId = R.useCallback((id: string | null) => {
        setCanvasRunId(id);
        setRunIdState(id);
      }, []);
      const value = R.useMemo(() => ({ runId, setRunId }), [runId, setRunId]);
      // The canvas provider is the innermost one rendered, so it wins this slot.
      canvasMode.current = value as never;
      return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
    },
    useWorkflowMode: () => R.useContext(Ctx) ?? { runId: null, setRunId: () => {} },
  };
});
vi.mock('@/contexts/WorkflowRunContext', () => ({
  WorkflowRunProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({
  useOrgScopedReset: (cb: () => void) => { orgResetCallback.current = cb; },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun: vi.fn() } }));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null }));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: (props: any) => {
    panelProps.current = props;
    return <div data-testid="workflow-panel">{props.workflowCanvasSlot}</div>;
  },
}));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({
  WorkflowRunCanvas: (props: any) => {
    canvasProps.current = props;
    return <div data-testid="workflow-canvas" />;
  },
}));

import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';
import { requestBindRun } from '@/components/workflow/run-panel/runPanelBus';

beforeEach(() => { setCanvasRunId.mockReset(); });
afterEach(() => {
  providerCalls.length = 0;
  providerMounts.length = 0;
  panelProps.current = null;
  canvasProps.current = null;
  canvasMode.current = null;
  orgResetCallback.current = null;
  cleanup();
});

const canvasNode = () => screen.getByTestId('workflow-canvas');
/** This tab's identity on the run bus, as it hands it to its Run tab. */
const surfaceOf = () => panelProps.current.runSurfaceId as string;
/** Play the canvas moving its own run (Edit / Run toggle, agent-launched run). */
const canvasBindsItself = (runId: string | null) => act(() => { canvasMode.current!.setRunId(runId); });

describe('WorkflowBuilderPanelContent - run history navigation', () => {
  it('opts its Run tab into the run history (the back arrow the sub-workflow tab lacked)', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    expect(panelProps.current.allowRunHistory).toBe(true);
  });

  it('gives its Run tab the surface id it listens on, so a pick there reaches it', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    expect(typeof surfaceOf()).toBe('string');
    expect(surfaceOf().length).toBeGreaterThan(0);
  });

  it('gives two tabs of the same workflow DIFFERENT surface ids', () => {
    // Otherwise a pick in one would move the other, which is the whole reason
    // the bus routes by surface and not by workflow alone.
    const seen: string[] = [];
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    seen.push(surfaceOf());
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    seen.push(surfaceOf());

    expect(seen[0]).not.toBe(seen[1]);
  });

  it('pushes the picked run into the canvas provider instead of destroying the canvas', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    expect(canvasProps.current.runId).toBe('run-1');
    const before = canvasNode();
    const mountsBefore = providerMounts.length;

    act(() => { requestBindRun({ workflowId: 'wf-sub', runId: 'run-2', surfaceId: surfaceOf() }); });

    // The provider's run id outranks the canvas prop downstream, so the switch is
    // only real once it has been pushed in.
    expect(setCanvasRunId).toHaveBeenCalledWith('run-2');
    expect(canvasMode.current!.runId).toBe('run-2');
    expect(canvasProps.current.runId).toBe('run-2');
    // ...and the canvas subtree survived: same DOM node, no provider rebuilt.
    expect(canvasNode()).toBe(before);
    expect(providerMounts.length).toBe(mountsBefore);
    // The panel (Application tab, interface rendering) follows the same run.
    expect(panelProps.current.runId).toBe('run-2');
  });

  it('honours picking the SAME run again after the canvas moved on its own', () => {
    // The canvas Run toggle rebinds the latest run without telling this tab, so
    // "go back to the one I picked" is a repeat of the id the tab already holds.
    // A binder that pushed once per distinct id would swallow it and the two
    // would diverge for good: the panel on one run, the canvas on another.
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    act(() => { requestBindRun({ workflowId: 'wf-sub', runId: 'run-2', surfaceId: surfaceOf() }); });
    canvasBindsItself('run-latest');
    setCanvasRunId.mockClear();

    act(() => { requestBindRun({ workflowId: 'wf-sub', runId: 'run-2', surfaceId: surfaceOf() }); });

    expect(setCanvasRunId).toHaveBeenCalledWith('run-2');
    expect(canvasMode.current!.runId).toBe('run-2');
  });

  it('leaves the canvas in charge between picks, so the Edit toggle actually leaves run mode', () => {
    // The embedded canvas swaps mode IN PLACE: its Edit toggle calls setRunId(null)
    // on this very provider. A binder comparing its run against the tab's would
    // put the run straight back and the toggle would do nothing.
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    act(() => { requestBindRun({ workflowId: 'wf-sub', runId: 'run-2', surfaceId: surfaceOf() }); });

    canvasBindsItself(null);

    expect(canvasMode.current!.runId).toBeNull();
    // ...and the rest of the tab follows it out of run mode, instead of keeping
    // the Application tab rendered against a run the canvas no longer shows.
    expect(panelProps.current.runId).toBeUndefined();
  });

  it('never pushes an in-place run back out, so an agent-launched run survives', () => {
    // Tab opened in EDIT mode; the canvas enters run mode on its own.
    const { rerender } = render(<WorkflowBuilderPanelContent workflowId="wf-sub" />);
    canvasBindsItself('agent-run');
    setCanvasRunId.mockClear();

    rerender(<WorkflowBuilderPanelContent workflowId="wf-sub" />);

    expect(setCanvasRunId).not.toHaveBeenCalled();
    expect(canvasMode.current!.runId).toBe('agent-run');
    // The tab adopts it, so the Application tab renders against the live run.
    expect(panelProps.current.runId).toBe('agent-run');
  });

  it('ignores a run picked in ANOTHER workflow', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    setCanvasRunId.mockClear();

    act(() => { requestBindRun({ workflowId: 'wf-parent', runId: 'run-9' }); });

    expect(setCanvasRunId).not.toHaveBeenCalled();
    expect(canvasProps.current.runId).toBe('run-1');
  });

  it('ignores a run picked on the PAGE showing the same workflow', () => {
    // A self-referencing sub-workflow node (or the tab picker) can open this
    // workflow beside the page that already shows it. The page's own pick carries
    // no surfaceId - it owns the route - and must not drag this tab along.
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    setCanvasRunId.mockClear();

    act(() => { requestBindRun({ workflowId: 'wf-sub', runId: 'run-9' }); });

    expect(setCanvasRunId).not.toHaveBeenCalled();
    expect(canvasProps.current.runId).toBe('run-1');
  });

  it('ignores a run picked in a SIBLING tab on the same workflow', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    setCanvasRunId.mockClear();

    act(() => { requestBindRun({ workflowId: 'wf-sub', runId: 'run-9', surfaceId: 'another-tab' }); });

    expect(setCanvasRunId).not.toHaveBeenCalled();
    expect(canvasProps.current.runId).toBe('run-1');
  });

  it('rebuilds both providers when the tab is re-opened on another run', () => {
    const { rerender } = render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    act(() => { requestBindRun({ workflowId: 'wf-sub', runId: 'run-2', surfaceId: surfaceOf() }); });
    providerMounts.length = 0;

    // openTab replaces the tab content with a new run - a genuine reset, unlike a
    // pick, so it must win over whatever the user had browsed to.
    rerender(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-7" />);

    expect(providerMounts.length).toBe(2);
    expect(providerMounts.every(p => p.initialRunId === 'run-7')).toBe(true);
    expect(canvasProps.current.runId).toBe('run-7');
    expect(panelProps.current.runId).toBe('run-7');
  });

  it('rebuilds BOTH providers on a workspace switch, back on the tab anchor', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    act(() => { requestBindRun({ workflowId: 'wf-sub', runId: 'run-2', surfaceId: surfaceOf() }); });
    providerMounts.length = 0;

    // This tab is keepMounted: without the rebuild it keeps rendering a canvas
    // holding whatever run it had drifted to under the previous workspace.
    act(() => { orgResetCallback.current?.(); });

    // A re-render is NOT enough: both providers hold a run of their own, and
    // theirs outranks the props, so only fresh ones actually drop the drift.
    expect(providerMounts.length).toBe(2);
    expect(providerMounts.every(p => p.initialRunId === 'run-1')).toBe(true);
    expect(canvasProps.current.runId).toBe('run-1');
    expect(panelProps.current.runId).toBe('run-1');
  });

  it('drops a canvas-bound run on a workspace switch, for a tab opened with no run', () => {
    // Most call sites open this tab in edit mode; the canvas then binds a run in
    // place. Clearing this tab's own state cannot reach that one.
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" />);
    canvasBindsItself('run-of-previous-org');
    providerMounts.length = 0;

    act(() => { orgResetCallback.current?.(); });

    expect(providerMounts.length).toBe(2);
    expect(canvasMode.current!.runId).toBeNull();
  });

  it('does not latch: the tab still works after a workspace round trip', () => {
    // Re-opening a side-panel tab on the SAME run reconciles this component with
    // byte-identical props, so nothing can re-adopt anything. A reset that had
    // emptied the anchor would leave the tab stuck in edit mode for good.
    render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);

    act(() => { orgResetCallback.current?.(); });
    act(() => { orgResetCallback.current?.(); });

    expect(canvasProps.current.runId).toBe('run-1');
    expect(canvasMode.current!.runId).toBe('run-1');
    expect(panelProps.current.runId).toBe('run-1');
  });

  it('re-adopts a tab genuinely re-opened on another run after a workspace switch', () => {
    const { rerender } = render(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-1" />);
    act(() => { orgResetCallback.current?.(); });

    rerender(<WorkflowBuilderPanelContent workflowId="wf-sub" runId="run-9" />);

    expect(canvasProps.current.runId).toBe('run-9');
    expect(canvasMode.current!.runId).toBe('run-9');
  });
});
