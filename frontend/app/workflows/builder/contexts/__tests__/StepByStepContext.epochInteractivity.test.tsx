/**
 * @vitest-environment jsdom
 *
 * REGRESSION: which epoch view keeps the step-by-step controls alive.
 *
 * The rule used to be "interactive ONLY in the All-epochs view". That held while
 * a run opened unselected, but once the run history moved into the side panel
 * every run surface seeds a default epoch on the shared provider
 * (useDefaultEpochSelection: "a run is always read THROUGH an epoch"), so the
 * canvas is never on "All" by default. The old rule then hid EVERY play and
 * rerun button on non-trigger nodes and a step-by-step run could not be stepped
 * from the canvas at all - silently, with no error.
 *
 * The rule is now: All-epochs OR the run's NEWEST epoch is interactive; only a
 * HISTORICAL epoch is read-only. All three cases are pinned here.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as React from 'react';
import { renderHook } from '@testing-library/react';
import { StepByStepProvider, useNodeExecutionStatus } from '../StepByStepContext';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: vi.fn(() => ({ viewingEpoch: null, isRunMode: true })),
}));

vi.mock('../../utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) => label.toLowerCase().replace(/\s+/g, '_'),
}));

const STEP = 'core:step_a';

function wrapperWith(currentEpoch: number) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return (
      <StepByStepProvider
        isEnabled
        isPaused={false}
        readySteps={new Set<string>([STEP])}
        completedSteps={new Set<string>([STEP])}
        failedSteps={new Set<string>()}
        onExecuteStep={async () => undefined}
        currentEpoch={currentEpoch}
      >
        {children}
      </StepByStepProvider>
    );
  };
}

function statusAt(viewingEpoch: number | null, currentEpoch: number) {
  vi.mocked(useWorkflowMode).mockReturnValue({ viewingEpoch, isRunMode: true } as ReturnType<typeof useWorkflowMode>);
  return renderHook(() => useNodeExecutionStatus(STEP), { wrapper: wrapperWith(currentEpoch) }).result.current;
}

describe('useNodeExecutionStatus - epoch view gates the controls', () => {
  beforeEach(() => vi.clearAllMocks());

  it('keeps controls in the All-epochs view', () => {
    const status = statusAt(null, 2);
    expect(status.isStepByStepMode).toBe(true);
    expect(status.canRerun).toBe(true);
  });

  it('keeps controls while reading the run through its NEWEST epoch', () => {
    // The regression: a seeded default epoch equals currentEpoch, and that is
    // the live state, not history - the run must stay steppable.
    const status = statusAt(2, 2);
    expect(status.isStepByStepMode).toBe(true);
    expect(status.canRerun).toBe(true);
  });

  it('makes a HISTORICAL epoch read-only', () => {
    const status = statusAt(1, 2);
    expect(status.isStepByStepMode).toBe(false);
    expect(status.canRerun).toBe(false);
    expect(status.canExecute).toBe(false);
  });

  it('treats a pinned epoch as historical when the run has not fired yet (currentEpoch 0)', () => {
    // currentEpoch 0 means "no epoch has opened", so no epoch can be the live
    // one; guarding on > 0 keeps a stale/absent value from unlocking controls.
    const status = statusAt(1, 0);
    expect(status.isStepByStepMode).toBe(false);
    expect(status.canRerun).toBe(false);
  });
});
