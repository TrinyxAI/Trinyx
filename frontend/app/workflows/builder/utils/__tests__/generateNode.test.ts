/**
 * generate core node - plan wiring (frontend layer of the backend contract).
 *
 * Like media, the generate config lives in the GENERIC `params` map
 * ({ model, credential_source, ...unified params }). Neither direction was
 * covered: both the export and the import could have been deleted with the
 * whole suite still green, and the field they carry is the one that decides
 * WHICH provider key runs the node and therefore whether the customer is
 * charged at all.
 *
 * These tests pin:
 *  - export: builder data (generateModel + generateCredentialSource + generateParams) -> cores[]
 *  - import: cores[] -> builder data
 *  - roundtrip: export -> import -> export leaves the core unchanged
 */
import { describe, it, expect, vi } from 'vitest';
import type { Node } from 'reactflow';
import { processTransformAndWaitNodes } from '../stepProcessor';
import { NodeCreationService } from '../../services/workflowPlanImporter/NodeCreationService';
import { DEFAULT_CREDENTIAL_SOURCE } from '../generateParams';
import type { BuilderNodeData } from '../../types';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), getTokenProvider: () => null },
}));
vi.mock('../../services/workflowPlanImporter/ToolDataService', () => ({
  ToolDataService: { fetchToolsBatch: vi.fn().mockResolvedValue(new Map()), getToolData: vi.fn() },
}));

function generateNode(extraData: Record<string, unknown>): Node<BuilderNodeData> {
  return {
    id: 'generate-1',
    type: 'flowNode',
    position: { x: 10, y: 20 },
    data: {
      id: 'generate-1',
      label: 'Make Clip',
      kind: 'generate',
      ...extraData,
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function runStepProcessor(node: Node<BuilderNodeData>) {
  const ctx: any = {
    nodes: [node],
    edges: [],
    plan: { cores: [] },
    stepLabelMap: new Map<string, string>(),
    stepPlanByNodeId: new Map<string, any>(),
  };
  processTransformAndWaitNodes(ctx);
  const core = ctx.plan.cores.find((c: any) => c.type === 'generate');
  expect(core).toBeDefined();
  return core;
}

function importCoreNode(coreNode: any) {
  const result = (NodeCreationService as any).createCoreNodesInline([coreNode], [], 100, 100);
  return result.nodes[0];
}

describe('stepProcessor - generate export (builder data -> plan params map)', () => {
  it('emits the config under the generic params map with the contract field names', () => {
    const core = runStepProcessor(generateNode({
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      generateParams: { prompt: 'a boat', duration_seconds: 10, aspect_ratio: '16:9' },
    }));

    expect(core.params).toEqual({
      model: 'seedance-2.0-fast',
      credential_source: 'user',
      prompt: 'a boat',
      duration_seconds: 10,
      aspect_ratio: '16:9',
    });
    // No dedicated config key like download_file's `download`
    expect(core).not.toHaveProperty('generate');
  });

  it('states the credential source even when the author never touched the control', () => {
    // Absent is not "the default" downstream, it is a DIFFERENT arrangement:
    // the author's own key first, billed by nobody, after the inspector had
    // quoted the platform price beside the node.
    const core = runStepProcessor(generateNode({
      generateModel: 'seedance-2.0-fast',
      generateParams: { prompt: 'a boat' },
    }));

    expect(core.params.credential_source).toBe(DEFAULT_CREDENTIAL_SOURCE);
  });

  it('keeps numbers as real JSON types, since a stringified size changes what is billed', () => {
    const core = runStepProcessor(generateNode({
      generateModel: 'seedance-2.0',
      generateParams: { prompt: 'x', duration_seconds: 10, n: 2, seed: 7 },
    }));

    expect(typeof core.params.duration_seconds).toBe('number');
    expect(core.params.duration_seconds).toBe(10);
    expect(core.params.n).toBe(2);
  });
});

describe('NodeCreationService - generate import (plan params map -> builder data)', () => {
  it('reads the model, the credential source and the remaining params back out', () => {
    const node = importCoreNode({
      id: 'core:make_clip',
      type: 'generate',
      label: 'Make Clip',
      params: {
        model: 'seedance-2.0-fast',
        credential_source: 'user',
        prompt: 'a boat',
        duration_seconds: 10,
      },
    });

    const d = node.data as any;
    expect(d.generateModel).toBe('seedance-2.0-fast');
    expect(d.generateCredentialSource).toBe('user');
    expect(d.generateParams).toEqual({ prompt: 'a boat', duration_seconds: 10 });
  });

  it('does not invent a credential source when the plan states none', () => {
    // A hand-written plan may legitimately omit it. The importer must not
    // fabricate a value here, because that would silently rewrite somebody
    // else's plan the first time it is opened in the builder.
    const node = importCoreNode({
      id: 'core:make_clip',
      type: 'generate',
      label: 'Make Clip',
      params: { model: 'seedance-2.0-fast', prompt: 'a boat' },
    });

    expect((node.data as any).generateCredentialSource).toBeUndefined();
  });
});

describe('generate roundtrip', () => {
  it('export -> import -> export leaves the core unchanged', () => {
    const first = runStepProcessor(generateNode({
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      generateParams: { prompt: 'a boat', duration_seconds: 10 },
    }));

    const imported = importCoreNode(first);
    const second = runStepProcessor({ ...generateNode({}), data: imported.data } as Node<BuilderNodeData>);

    expect(second.params).toEqual(first.params);
  });
});
