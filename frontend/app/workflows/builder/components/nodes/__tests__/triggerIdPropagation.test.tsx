// @vitest-environment jsdom
/**
 * The "several chat triggers open the wrong tab" fix only works if the backend
 * step id actually REACHES the play button from each surface that renders one.
 * NodePlayButton's own handling and the panel-side resolver are covered
 * elsewhere; these tests pin the two wirings in between, which are a single prop
 * pass each and would otherwise be deletable with the suite still green.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

const playProps: Array<Record<string, unknown>> = [];
let mockMode: Record<string, unknown>;

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
// ONE mock for this module, not two. The alias specifier and the relative one
// resolve to the same file, so a second registration only made which factory
// wins depend on resolution order - and the two had drifted apart.
vi.mock('../../NodePlayButton', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../NodePlayButton')>()),
  NodePlayButton: (props: Record<string, unknown>) => {
    playProps.push(props);
    return <div data-testid="play" />;
  },
  deriveNodeStatus: () => 'ready',
}));

// StepRowActions' collaborators - kept out of the way, they are not under test.
vi.mock('@/app/workflows/builder/nodes/nodeClasses', () => ({ findNodeClassById: () => null }));
vi.mock('@/app/workflows/builder/components/nodes/TriggerNodePinButton', () => ({
  TriggerNodePinButton: () => null,
}));
// Replaced wholesale, NOT spread from the original: this module's side-panel
// payloads reach next-intl's navigation entry, which vitest cannot resolve.
// That is why the fireable-trigger predicate lives in its own leaf module.
vi.mock('@/app/workflows/builder/hooks/useNodeContextualButtons', () => ({
  deriveNodeContextFlags: () => ({
    isTriggerNode: true, isChatTrigger: true, isManualTrigger: false, isFormTrigger: false,
    isWebhookTrigger: false, isScheduleTrigger: false, isWorkflowsTriggerNode: false,
    isTablesTrigger: false, isErrorTrigger: false, triggerVariant: 'message',
  }),
  useNodeContextualButtons: () => [],
}));

import { NodeBottomBar } from '../NodeBottomBar';
import { StepRowActions } from '@/components/workflow/StepRowActions';

const stepStatus = (over: Record<string, unknown> = {}) => ({
  isStepByStepMode: false,
  isReady: true,
  canExecute: true,
  isExecuting: false,
  isRerunning: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: false,
  canRerun: false,
  executeStep: () => {},
  rerunStep: () => {},
  ...over,
});

beforeEach(() => {
  playProps.length = 0;
  mockMode = { isRunMode: true, isPreviewOnly: false, setViewingEpoch: vi.fn() };
});

const lastPlay = () => playProps[playProps.length - 1];

describe('triggerId reaches the play button from every surface', () => {
  it('the node bottom bar forwards the resolved backend step id', () => {
    render(
      <NodeBottomBar
        borderColor="#000"
        isRunning={false}
        playButton={{
          nodeId: 'chat-trigger-2',
          variant: 'message',
          isAutoMode: true,
          isTriggerNode: true,
          stepByStepStatus: stepStatus({ stepId: 'trigger:sales_chat' }) as never,
        }}
      />,
    );
    expect(lastPlay().triggerId).toBe('trigger:sales_chat');
  });

  it('the node bottom bar passes nothing when the run context has no step id', () => {
    render(
      <NodeBottomBar
        borderColor="#000"
        isRunning={false}
        playButton={{
          nodeId: 'chat-trigger-2',
          variant: 'message',
          isAutoMode: true,
          isTriggerNode: true,
          stepByStepStatus: stepStatus() as never,
        }}
      />,
    );
    // Undefined degrades to type matching; it must never be a node id.
    expect(lastPlay().triggerId).toBeUndefined();
  });

  it('the run-info step row forwards the step alias as the trigger id', () => {
    render(
      <StepRowActions
        step={{ alias: 'trigger:support_chat' }}
        matchedNode={{ id: 'chat-trigger-1', position: { x: 0, y: 0 }, data: { id: 'chat-trigger', kind: 'entry', label: 'Support Chat' } } as never}
        workflowId="wf-1"
        isStepByStep={false}
        isRunActive
      />,
    );
    expect(lastPlay().triggerId).toBe('trigger:support_chat');
  });
});
