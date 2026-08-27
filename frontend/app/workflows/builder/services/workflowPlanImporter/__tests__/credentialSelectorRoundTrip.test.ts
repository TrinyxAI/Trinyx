import { describe, it, expect, vi } from 'vitest';
import type { Node } from 'reactflow';

import { processSteps } from '../../../utils/stepProcessor';
import { createPlanGeneratorContext } from '../../../utils/planGeneratorContext';
import { createStepNodes } from '../StepNodeCreator';
import type { BuilderNodeData } from '../../../types';

// The tool-metadata fetch is irrelevant here and would hit the network: what is
// under test is which credential fields survive a save and a reload.
vi.mock('../ToolDataService', () => ({
  ToolDataService: {
    getFromBatchCache: vi.fn().mockReturnValue(undefined),
    fetchToolData: vi.fn().mockResolvedValue({}),
    fetchToolsBatch: vi.fn().mockResolvedValue(new Map()),
  },
}));

/**
 * A step that decides WHICH account it runs on at run time must survive a save
 * and a reload.
 *
 * <p>This is a regression guard for a defect that made the feature erase itself.
 * The exporter wrote `credentialSelector`; the importer did not read it back, so
 * on reload the inspector rendered the credential PICKER, which auto-persists the
 * account's default id on first render with no user action, and the next save
 * wrote a pin with no selector. A workflow serving several accounts silently
 * became a single-account one, running on the default key, with a green run and
 * no message anywhere.
 */

function stepNode(toolData: Record<string, unknown>): Node<BuilderNodeData> {
  return {
    id: 'n-publish',
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'instagram/publish',
      label: 'Publish',
      kind: 'mcp',
      toolData,
      paramExpressions: { caption: 'hello' },
    } as unknown as BuilderNodeData,
  };
}

async function exportThenImport(node: Node<BuilderNodeData>) {
  const ctx = createPlanGeneratorContext([node], []);
  processSteps(ctx);
  const exported = ctx.plan.mcps!;
  expect(exported).toHaveLength(1);
  const { nodes } = await createStepNodes(exported as never, new Set(), 0, 0, 0);
  return { exported: exported[0] as Record<string, unknown>, reimported: nodes[0] };
}

describe('credential selector round-trip (save -> reload)', () => {
  it('keeps the run-time account expression on the node after a reload', async () => {
    const { exported, reimported } = await exportThenImport(
      stepNode({ credentialSelector: '{{item.ig_account}}' })
    );

    expect(exported.credentialSelector).toBe('{{item.ig_account}}');
    expect((reimported.data as never as Record<string, never>).toolData).toBeTruthy();
    expect(
      ((reimported.data as never as Record<string, Record<string, unknown>>).toolData)
        .credentialSelector
    ).toBe('{{item.ig_account}}');
  });

  it('does not write a credential pin alongside the expression', async () => {
    // The two modes answer one question, so a plan carrying both would say two
    // different things and the runtime would have to pick. The exporter writes the
    // selector INSTEAD of the pin, even when a stale auto-filled id is present on
    // the node.
    const { exported } = await exportThenImport(
      stepNode({ credentialSelector: '{{item.ig_account}}', selectedCredentialId: 99 })
    );

    expect(exported.credentialSelector).toBe('{{item.ig_account}}');
    expect(exported.selectedCredentialId).toBeUndefined();
  });

  it('leaves a statically pinned step exactly as it was', async () => {
    // The other half: a workflow written before any of this existed must round-trip
    // to the same pin, with no selector appearing from nowhere.
    const { exported, reimported } = await exportThenImport(
      stepNode({ selectedCredentialId: 42 })
    );

    expect(exported.selectedCredentialId).toBe(42);
    expect(exported.credentialSelector).toBeUndefined();
    expect(
      ((reimported.data as never as Record<string, Record<string, unknown>>).toolData)
        .selectedCredentialId
    ).toBe(42);
  });

  it('carries a blank expression rather than dropping it, so the mode survives the reload', async () => {
    // Dropping it here is what made clearing the field revert the step: the mode
    // was lost on the way out, the picker came back on the way in, and it
    // auto-persisted the account default. Blank is a state (dynamic, not filled in
    // yet) and the run says so out loud instead of guessing an account.
    const { exported, reimported } = await exportThenImport(
      stepNode({ credentialSelector: '   ' })
    );

    expect(exported.credentialSelector).toBe('');
    expect(
      ((reimported.data as never as Record<string, Record<string, unknown>>).toolData)
        .credentialSelector
    ).toBe('');
  });

  it('survives the round-trip when an agent wrote it as a NUMBER', async () => {
    // The documentation says a credential id also works, so an agent-built plan can
    // legitimately carry a number here. Type-tested for string, that reads as 'no
    // selector': the inspector renders the picker, the picker auto-persists the
    // account default, and the next save writes a pin. Same erase-itself defect,
    // through a type the first guard did not cover.
    const { exported, reimported } = await exportThenImport(
      stepNode({ credentialSelector: 42 })
    );

    expect(exported.credentialSelector).toBe('42');
    expect(
      ((reimported.data as never as Record<string, Record<string, unknown>>).toolData)
        .credentialSelector
    ).toBe('42');
  });

  it('reads a snake_case selector, the spelling the backend parser also accepts', async () => {
    // A plan stored through set_plan can carry credential_selector, and the backend
    // treats it as a live selector. Read as "no selector" here, the inspector renders
    // the picker, the picker auto-persists the account default, and the next save
    // writes a pin - the erase-itself defect through the other spelling.
    const ctx = createPlanGeneratorContext([], []);
    const { nodes } = await createStepNodes(
      [
        {
          id: 'instagram/publish',
          label: 'Publish',
          type: 'mcp',
          credential_selector: '{{item.ig_account}}',
        },
      ] as never,
      new Set(),
      0,
      0,
      0
    );
    void ctx;

    expect(
      ((nodes[0].data as never as Record<string, Record<string, unknown>>).toolData)
        .credentialSelector
    ).toBe('{{item.ig_account}}');
  });
});
