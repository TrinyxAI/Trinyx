/**
 * @vitest-environment jsdom
 *
 * A rerun on an AUTOMATIC run must be confirmed first.
 *
 * On a stepped run the rerun stops at the target and waits for the user, so it costs
 * nothing. In automatic mode the SAME click reruns the target and then lets the whole
 * downstream chain run again unattended, spending paid calls and sending real messages.
 * The gate lives in the provider so every rerun surface (canvas bar, context menu,
 * inspector) inherits it from the one `rerunStep` they all call.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as React from 'react';
import { render, screen, cleanup, fireEvent, act, waitFor } from '@testing-library/react';
import { StepByStepProvider, useStepByStep } from '../StepByStepContext';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: vi.fn(() => ({ viewingEpoch: null, isRunMode: true })),
}));

// Key-echo translations (same pattern as the other component tests).
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const STEP = 'mcp:step_a';

/** Captures the context so the test can drive `rerunStep` exactly like a UI surface does. */
let captured: ReturnType<typeof useStepByStep> = null;
function Probe() {
  captured = useStepByStep();
  return null;
}

function renderWith(isEnabled: boolean, onRerunStep: (stepId: string) => Promise<any>) {
  return render(
    <StepByStepProvider
      isEnabled={isEnabled}
      isPaused={false}
      readySteps={new Set<string>()}
      completedSteps={new Set<string>([STEP])}
      failedSteps={new Set<string>()}
      onExecuteStep={async () => undefined}
      onRerunStep={onRerunStep}
      currentEpoch={1}
    >
      <Probe />
    </StepByStepProvider>
  );
}

describe('rerun confirmation on an automatic run', () => {
  beforeEach(() => {
    captured = null;
    vi.mocked(useWorkflowMode).mockReturnValue(
      { viewingEpoch: null, isRunMode: true } as ReturnType<typeof useWorkflowMode>,
    );
  });
  afterEach(cleanup);

  it('asks for confirmation instead of rerunning immediately', async () => {
    const onRerunStep = vi.fn(async () => ({ ok: true }) as any);
    renderWith(false, onRerunStep);

    act(() => { void captured!.rerunStep(STEP); });

    await screen.findByRole('dialog');
    // The whole point: nothing has been sent to the backend yet.
    expect(onRerunStep).not.toHaveBeenCalled();
  });

  it('names the step the restart would start from', async () => {
    renderWith(false, vi.fn(async () => null));
    act(() => { void captured!.rerunStep(STEP); });

    await screen.findByRole('dialog');
    // Humanized from the backend step id so the user can vet WHICH node restarts.
    expect(screen.getByText('step a')).toBeTruthy();
  });

  it('runs the rerun once the user confirms, and returns its result', async () => {
    const response = { runId: 'r1' } as any;
    const onRerunStep = vi.fn(async () => response);
    renderWith(false, onRerunStep);

    let result: unknown = 'not-settled';
    act(() => { void captured!.rerunStep(STEP).then((r) => { result = r; }); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-accept')); });

    expect(onRerunStep).toHaveBeenCalledTimes(1);
    expect(onRerunStep).toHaveBeenCalledWith(STEP);
    await waitFor(() => expect(result).toBe(response));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('reruns nothing when the user cancels, and settles the caller with null', async () => {
    const onRerunStep = vi.fn(async () => ({}) as any);
    renderWith(false, onRerunStep);

    let result: unknown = 'not-settled';
    act(() => { void captured!.rerunStep(STEP).then((r) => { result = r; }); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-cancel')); });

    expect(onRerunStep).not.toHaveBeenCalled();
    // A dismissed rerun is a no-op, not an error: callers already handle null.
    await waitFor(() => expect(result).toBeNull());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('dismisses on a click outside the card', async () => {
    const onRerunStep = vi.fn(async () => null);
    renderWith(false, onRerunStep);
    act(() => { void captured!.rerunStep(STEP); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-overlay')); });

    expect(onRerunStep).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('dismisses on Escape', async () => {
    const onRerunStep = vi.fn(async () => null);
    renderWith(false, onRerunStep);
    act(() => { void captured!.rerunStep(STEP); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });

    expect(onRerunStep).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('propagates a backend failure to the caller, exactly like the ungated path', async () => {
    const boom = new Error('rerun refused');
    const onRerunStep = vi.fn(async () => { throw boom; });
    renderWith(false, onRerunStep);

    let caught: unknown = null;
    act(() => { void captured!.rerunStep(STEP).catch((e) => { caught = e; }); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-accept')); });

    await waitFor(() => expect(caught).toBe(boom));
  });
});

describe('rerun on a stepped run', () => {
  beforeEach(() => {
    captured = null;
    vi.mocked(useWorkflowMode).mockReturnValue(
      { viewingEpoch: null, isRunMode: true } as ReturnType<typeof useWorkflowMode>,
    );
  });
  afterEach(cleanup);

  it('reruns straight away with no confirmation', async () => {
    const response = { runId: 'r2' } as any;
    const onRerunStep = vi.fn(async () => response);
    renderWith(true, onRerunStep);

    let result: unknown = 'not-settled';
    await act(async () => { result = await captured!.rerunStep(STEP); });

    // The stepped rerun stops at the target and waits for the user: nothing to confirm.
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onRerunStep).toHaveBeenCalledWith(STEP);
    expect(result).toBe(response);
  });
});
