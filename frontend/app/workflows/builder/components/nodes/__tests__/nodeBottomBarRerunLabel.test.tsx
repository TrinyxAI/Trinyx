/**
 * @vitest-environment jsdom
 *
 * The restart button's label must describe what the click WILL DO, and that depends on the
 * run's persisted execution mode - not on isStepByStepMode, which folds terminality in and is
 * therefore false on a FINISHED stepped run. Keying off the wrong flag promised unattended
 * re-execution on the one kind of run where a restart executes nothing and waits for the user.
 *
 * Pins the NodeBottomBar -> NodePlayButton wiring, which nothing covered: swapping the flag
 * back at the call site left the whole nodes suite green.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as React from 'react';
import { render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false, viewingEpoch: null }),
}));

import { NodeBottomBar } from '../NodeBottomBar';

const stepStatus = (over: Record<string, boolean> = {}) => ({
  isStepByStepMode: false,
  isSteppedRun: false,
  isReady: false,
  canExecute: false,
  isExecuting: false,
  isRerunning: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: true,
  canRerun: true,
  executeStep: () => {},
  rerunStep: () => {},
  ...over,
});

function renderBar(over: Record<string, boolean>) {
  render(
    <NodeBottomBar
      borderColor="#000"
      isRunning={false}
      hover={{ isVisible: true }}
      playButton={{
        nodeId: 'n1',
        variant: 'play',
        isAutoMode: !over.isSteppedRun,
        isTriggerNode: false,
        stepByStepStatus: stepStatus(over),
      }}
    />,
  );
  return screen.getByTestId('node-rerun-button');
}

describe('NodeBottomBar - restart label follows the run mode', () => {
  beforeEach(() => vi.clearAllMocks());

  it('warns that the rest replays on its own when the run is automatic', () => {
    expect(renderBar({ isSteppedRun: false }).getAttribute('title')).toBe('rerunStepAuto');
  });

  it('keeps the plain label when the user is stepping the run', () => {
    expect(renderBar({ isSteppedRun: true, isStepByStepMode: true }).getAttribute('title')).toBe('rerunStep');
  });

  it('keeps the plain label on a FINISHED stepped run, where a restart executes nothing', () => {
    // isStepByStepMode is false here; only isSteppedRun still reports the truth.
    expect(renderBar({ isSteppedRun: true, isStepByStepMode: false }).getAttribute('title')).toBe('rerunStep');
  });
});
