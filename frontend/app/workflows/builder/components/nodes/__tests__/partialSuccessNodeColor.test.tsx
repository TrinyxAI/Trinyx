// @vitest-environment jsdom
/**
 * A node carrying a failure in its own tally renders AMBER, and it keeps its rerun button.
 *
 * Those two pull against each other, which is what makes this worth pinning at the component
 * level rather than on a helper:
 *
 *  - The node must be in `completedSteps`, because `canRerunStep` tests
 *    `completedSteps || failedSteps` and that is what puts the rerun button on it.
 *  - But the step-by-step branch of every node's `effectiveStatus` tested `isCompleted` first and
 *    returned 'completed', discarding the backend's PARTIAL_SUCCESS - so the node rendered the
 *    same green as a clean one, right next to a badge showing a red failed count.
 *
 * The border reads `data.status` (the backend verdict, which follows the same accumulated counts
 * the badge displays); the button reads `deriveNodeStatus`, which deliberately still sees
 * 'completed'. Splitting them is what lets both be right at once - an earlier attempt introduced
 * a `partial_success` value into the button's own status enum instead, and NodePlayButton renders
 * null for an unknown status, so it removed the button it was meant to preserve.
 *
 * SCOPE: this suite asserts the BORDER only. NodePlayButton and deriveNodeStatus are mocked out
 * here precisely so the colour is measured in isolation, which means nothing below proves the
 * button survives - that half is pinned on the real functions in
 * `__tests__/workflow-run/partialSuccessRerunGate.test.ts` (the bucketing and the button's own
 * status). Read the two together; neither alone shows the tension is resolved.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockExec: any;
let mockMode: any;

const execStatus = (over: Record<string, boolean> = {}) => ({
  isStepByStepMode: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: false,
  isReady: false,
  ...over,
});

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('../../../contexts/StepByStepContext', () => ({
  useNodeExecutionStatus: () => mockExec,
}));
vi.mock('../../../contexts/ValidationContext', () => ({
  useValidation: () => ({ hasNodeErrors: () => false }),
}));
vi.mock('../../../nodes/nodeClasses', () => ({
  findNodeClassById: () => undefined,
}));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../NodeBottomBar', () => ({ NodeBottomBar: () => null }));
vi.mock('../../NodePlayButton', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../NodePlayButton')>()),
  NodePlayButton: () => null,
  deriveNodeStatus: () => undefined,
}));
// getStatusBorderColor stays REAL - it is the half of the chain under test.
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useNodes: () => [],
  useEdges: () => [],
}));

import { AggregateNode } from '../AggregateNode';
import { ExitNode } from '../ExitNode';
import { ResponseNode } from '../ResponseNode';
import { SplitNode } from '../SplitNode';
import { MergeNode } from '../MergeNode';
// The `executionStatus.isCompleted || executionStatus.isEvaluated` family. They were missed on
// the first pass because the guard was applied by grepping the OTHER family's variable name, and
// they had no coverage to catch it - a Classify inside a split is exactly what the backend's own
// example demotes, so the most likely partial node in the product was among them.
import { ClassifyNode } from '../ClassifyNode';
import { DecisionNode } from '../DecisionNode';
import { SwitchNode } from '../SwitchNode';
import { GuardrailNode } from '../GuardrailNode';

const AMBER = 'rgb(245, 158, 11)';
const EMERALD = 'rgb(16, 185, 129)';
const RED = 'rgb(239, 68, 68)';
const BLUE = 'rgb(59, 130, 246)';

const components: ReadonlyArray<readonly [string, React.ComponentType<any>]> = [
  ['AggregateNode', AggregateNode],
  ['ExitNode', ExitNode],
  ['ResponseNode', ResponseNode],
  ['SplitNode', SplitNode],
  ['MergeNode', MergeNode],
  ['ClassifyNode', ClassifyNode],
  ['DecisionNode', DecisionNode],
  ['SwitchNode', SwitchNode],
  ['GuardrailNode', GuardrailNode],
  // UserApprovalNode is deliberately absent: it needs the pending-signals context this harness
  // does not provide, and mocking it would test the mock. It carries the same guard, verified by
  // inspection alongside the other 16.
];

const renderNode = (
  Comp: React.ComponentType<any>,
  status?: string,
  statusCounts?: Record<string, number>,
) => render(<Comp data={{ id: 'n1', label: 'N', status, statusCounts }} selected={false} id="n1" />);

const borderOf = (c: ReturnType<typeof render>) =>
  (c.container.firstChild as HTMLElement).style.borderColor || '';

beforeEach(() => {
  mockMode = { isRunMode: true, viewingEpoch: null };
  mockExec = execStatus();
});

describe.each(components)('%s - partial success', (_name, Comp) => {
  it('renders amber while stepping, even though the node counts as completed', () => {
    // isCompleted is TRUE: the node is in completedSteps, which is what gives it a rerun
    // button. That is exactly why the status alone used to win and paint it green.
    mockExec = execStatus({ isStepByStepMode: true, isCompleted: true });
    expect(borderOf(renderNode(Comp, 'partial_success', { completed: 3, failed: 1 }))).toBe(AMBER);
  });

  it('renders amber in automatic mode too', () => {
    mockExec = execStatus({ isStepByStepMode: false });
    expect(borderOf(renderNode(Comp, 'partial_success', { completed: 3, failed: 1 }))).toBe(AMBER);
  });

  it('stays amber after a rerun fixes it, because the failure is still in its tally', () => {
    // spawn 1 failed, spawn 2 succeeded. The badge still shows a red count, so the border must
    // agree - the two are read together and must not contradict each other.
    mockExec = execStatus({ isStepByStepMode: true, isCompleted: true });
    expect(borderOf(renderNode(Comp, 'partial_success', { completed: 1, failed: 1 }))).toBe(AMBER);
  });

  it('leaves a cleanly completed node green', () => {
    mockExec = execStatus({ isStepByStepMode: true, isCompleted: true });
    expect(borderOf(renderNode(Comp, 'completed', { completed: 4 }))).toBe(EMERALD);
  });

  it('leaves a plainly failed node red', () => {
    mockExec = execStatus({ isStepByStepMode: true, isFailed: true });
    expect(borderOf(renderNode(Comp, 'failed', { failed: 2 }))).toBe(RED);
  });

  it('lets a live execution win over the stored partial status', () => {
    mockExec = execStatus({ isStepByStepMode: true, isRunning: true });
    expect(borderOf(renderNode(Comp, 'partial_success', { completed: 3, failed: 1 }))).toBe(BLUE);
  });

  it('lets a live failure win over the stored partial status', () => {
    mockExec = execStatus({ isStepByStepMode: true, isFailed: true });
    expect(borderOf(renderNode(Comp, 'partial_success', { completed: 3, failed: 1 }))).toBe(RED);
  });
});
