/**
 * @vitest-environment jsdom
 *
 * The edit/run toggle and the run identity bar are two of the three canvas-chrome
 * surfaces. Both are shown over the nodes in BOTH modes, so what is pinned here:
 *
 *  - the two mode buttons are the shared `canvasChromeButtonClass`, and the
 *    active one wears the active state (that state used to live in a separately
 *    positioned slider div, which is gone);
 *  - the toggle, the read-only badge and the run bar all sit on the shared square
 *    surface - nothing round survives;
 *  - the toggle is centred when the canvas has room and anchors left when it does
 *    not, so it never collides with the run bar on a narrow canvas.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const modeRef = vi.hoisted(() => ({ value: 'edit' as 'run' | 'edit' }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/app/workflow/wf-1',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getLatestWorkflowRun: vi.fn() } }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ runId: 'run-1', setRunId: vi.fn(), viewingEpoch: null, setViewingEpoch: vi.fn(), pinnedVersion: null }),
}));
vi.mock('@/lib/workflow/canvasEmbedding', () => ({ isEmbeddedWorkflowCanvas: () => false }));
vi.mock('@/components/workflow/run-panel/RunSummaryBar', () => ({
  RunSummaryBar: () => <div data-testid="run-summary-bar" />,
}));

/** Controllable ResizeObserver - the toggle measures its canvas with one. */
const observers: Array<(entries: Array<{ contentRect: { width: number } }>) => void> = [];
class TestResizeObserver {
  constructor(cb: (entries: Array<{ contentRect: { width: number } }>) => void) { observers.push(cb); }
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = TestResizeObserver;

/** Report a canvas width to every mounted observer. */
function resizeCanvasTo(width: number) {
  act(() => { observers.forEach((cb) => cb([{ contentRect: { width } }])); });
}

import { canvasChromeSurfaceClass, canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
import { WorkflowModeToggle } from '@/components/workflow/WorkflowModeToggle';

const runInfo = { runId: 'run-1', status: 'RUNNING', planVersion: 3 } as never;

beforeEach(() => { observers.length = 0; modeRef.value = 'edit'; });
afterEach(cleanup);

describe('WorkflowModeToggle - mode buttons', () => {
  it('styles both mode buttons with the compact chrome control style', () => {
    // Compact, not standard: the toggle has to stay level with the run bar it
    // shares the canvas top edge with.
    render(<WorkflowModeToggle workflowId="wf-1" mode="edit" />);
    expect(screen.getByTestId('workflow-mode-edit').className).toBe(canvasChromeCompactButtonClass(true));
    expect(screen.getByTestId('workflow-mode-run').className).toBe(canvasChromeCompactButtonClass(false));
  });

  it('moves the active state to the button for the current mode', () => {
    render(<WorkflowModeToggle workflowId="wf-1" mode="run" currentRunInfo={runInfo} />);
    expect(screen.getByTestId('workflow-mode-run').className).toBe(canvasChromeCompactButtonClass(true));
    expect(screen.getByTestId('workflow-mode-edit').className).toBe(canvasChromeCompactButtonClass(false));
  });

  it('announces the selection, which is otherwise carried by colour alone', () => {
    render(<WorkflowModeToggle workflowId="wf-1" mode="edit" />);
    expect(screen.getByTestId('workflow-mode-edit').getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('workflow-mode-run').getAttribute('aria-pressed')).toBe('false');
  });

  it('seats the pair on the shared square surface', () => {
    render(<WorkflowModeToggle workflowId="wf-1" mode="edit" />);
    const group = screen.getByTestId('workflow-mode-edit').parentElement!;
    for (const token of canvasChromeSurfaceClass.split(/\s+/)) {
      expect(group.className, `toggle lost ${token}`).toContain(token);
    }
  });
});

describe('WorkflowModeToggle - nothing round left on the canvas', () => {
  it('drops the pill shape from the toggle', () => {
    const { container } = render(<WorkflowModeToggle workflowId="wf-1" mode="edit" />);
    expect(container.innerHTML).not.toContain('rounded-full');
  });

  it('drops it from the read-only badge too (marketplace preview)', () => {
    const { container } = render(<WorkflowModeToggle workflowId="wf-1" mode="run" showReadOnlyBadge />);
    expect(container.innerHTML).not.toContain('rounded-full');
    const badge = screen.getByText('workflow.mode.readOnly').parentElement!;
    expect(badge.className).toContain('rounded-2xl');
  });

  it('drops it from the run bar, and from the history chip that stands in for it', () => {
    const { container, rerender } = render(
      <WorkflowModeToggle workflowId="wf-1" mode="run" currentRunInfo={runInfo} />,
    );
    const bar = container.querySelector('[data-run-info-panel]') as HTMLElement;
    for (const token of canvasChromeSurfaceClass.split(/\s+/)) {
      expect(bar.className, `run bar lost ${token}`).toContain(token);
    }

    // No run info yet: the standalone history chip is the only route into the
    // run history, and it floats with no card under it - so it paints its own
    // surface while staying the shared square control.
    rerender(<WorkflowModeToggle workflowId="wf-1" mode="run" currentRunInfo={null} />);
    const chip = container.querySelector('[data-run-history-fallback]') as HTMLElement;
    expect(chip.className).toContain('rounded-xl');
    expect(chip.className).not.toContain('rounded-full');
    expect(chip.className).toContain('bg-[var(--bg-primary)]/95');
  });

  it('leaves no drop shadow on any of the three floating surfaces', () => {
    // A shadow on a small chip floating over the canvas reads as a raised
    // bubble; the hairline border is what separates it from the nodes.
    const { container, rerender } = render(
      <WorkflowModeToggle workflowId="wf-1" mode="run" currentRunInfo={runInfo} />,
    );
    const toggle = screen.getByTestId('workflow-mode-edit').parentElement!;
    const bar = container.querySelector('[data-run-info-panel]') as HTMLElement;
    expect(toggle.className).not.toContain('shadow');
    expect(bar.className).not.toContain('shadow');

    rerender(<WorkflowModeToggle workflowId="wf-1" mode="run" currentRunInfo={null} />);
    const chip = container.querySelector('[data-run-history-fallback]') as HTMLElement;
    expect(chip.className).not.toContain('shadow-[');
  });
});

describe('WorkflowModeToggle - staying centred and reachable', () => {
  it('centres the toggle once the canvas has room for it', () => {
    render(<WorkflowModeToggle workflowId="wf-1" mode="edit" />);
    resizeCanvasTo(1200);
    const anchor = screen.getByTestId('workflow-mode-edit').closest('.absolute') as HTMLElement;
    expect(anchor.className).toContain('left-1/2');
    expect(anchor.className).toContain('-translate-x-1/2');
  });

  it('anchors it left on a phone-width canvas, where centring would meet the run bar', () => {
    render(<WorkflowModeToggle workflowId="wf-1" mode="run" currentRunInfo={runInfo} />);
    resizeCanvasTo(380);
    const anchor = screen.getByTestId('workflow-mode-edit').closest('.absolute') as HTMLElement;
    expect(anchor.className).toContain('left-2');
    expect(anchor.className).not.toContain('left-1/2');
  });

  it('needs more room before centring when the run bar shares the top edge', () => {
    // 700px fits the toggle alone but not the toggle AND the run bar.
    const { rerender } = render(<WorkflowModeToggle workflowId="wf-1" mode="edit" />);
    resizeCanvasTo(700);
    const anchor = () => screen.getByTestId('workflow-mode-edit').closest('.absolute') as HTMLElement;
    expect(anchor().className).toContain('left-1/2');

    rerender(<WorkflowModeToggle workflowId="wf-1" mode="run" currentRunInfo={runInfo} />);
    expect(anchor().className).toContain('left-2');
  });

  it('still centres on a phone-width EDIT canvas, where nothing else claims the middle', () => {
    // Edit mode has no run bar - only the corner add-node button - so the old
    // 640px floor exiled the toggle to the left corner on every phone for
    // nothing. The floor now only has to clear the ~88px toggle itself.
    render(<WorkflowModeToggle workflowId="wf-1" mode="edit" />);
    resizeCanvasTo(430);
    const anchor = screen.getByTestId('workflow-mode-edit').closest('.absolute') as HTMLElement;
    expect(anchor.className).toContain('left-1/2');
  });

  it('gives up centring on a canvas too narrow even for the toggle alone', () => {
    render(<WorkflowModeToggle workflowId="wf-1" mode="edit" />);
    resizeCanvasTo(300);
    const anchor = screen.getByTestId('workflow-mode-edit').closest('.absolute') as HTMLElement;
    expect(anchor.className).toContain('left-2');
    expect(anchor.className).not.toContain('left-1/2');
  });
});
