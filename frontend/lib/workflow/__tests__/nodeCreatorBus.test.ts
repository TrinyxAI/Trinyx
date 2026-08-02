/**
 * @vitest-environment jsdom
 *
 * The node palette lives in the side panel (app-layout tree) while the builder
 * that creates the node lives in the page tree. Everything they exchange goes
 * through this bus, so a silent break here means "clicking a palette item does
 * nothing" - the failure mode is invisible, not loud.
 */
import { describe, expect, it, vi } from 'vitest';
import {
  CREATE_NODE_REQUEST_EVENT,
  NODE_CREATOR_VISIBILITY_EVENT,
  publishNodeCreatorVisibility,
  requestCreateNode,
  type CreateNodeRequestDetail,
  type NodeCreatorVisibilityDetail,
} from '@/lib/workflow/nodeCreatorBus';

function capture<T>(eventName: string, fn: () => void): T[] {
  const seen: T[] = [];
  const handler = (e: Event) => seen.push((e as CustomEvent<T>).detail);
  window.addEventListener(eventName, handler);
  try { fn(); } finally { window.removeEventListener(eventName, handler); }
  return seen;
}

describe('requestCreateNode', () => {
  it('carries the picked item and the workflow it belongs to', () => {
    const seen = capture<CreateNodeRequestDetail>(CREATE_NODE_REQUEST_EVENT, () => {
      requestCreateNode({ workflowId: 'wf-1', item: 'if-else' });
    });
    expect(seen).toEqual([{ workflowId: 'wf-1', item: 'if-else' }]);
  });

  it('passes a fully built palette item through untouched (object payloads)', () => {
    const item = { id: 'tool-slack_send', kind: 'tool', toolData: { toolSlug: 'slack_send' } };
    const seen = capture<CreateNodeRequestDetail>(CREATE_NODE_REQUEST_EVENT, () => {
      requestCreateNode({ workflowId: 'wf-1', item });
    });
    // Identity matters: the builder re-uses this object as the node payload.
    expect(seen[0].item).toBe(item);
  });
});

describe('publishNodeCreatorVisibility', () => {
  it('announces both open and close so the builder can react to either', () => {
    const seen = capture<NodeCreatorVisibilityDetail>(NODE_CREATOR_VISIBILITY_EVENT, () => {
      publishNodeCreatorVisibility({ workflowId: 'wf-1', open: true });
      publishNodeCreatorVisibility({ workflowId: 'wf-1', open: false });
    });
    expect(seen).toEqual([
      { workflowId: 'wf-1', open: true },
      { workflowId: 'wf-1', open: false },
    ]);
  });
});

describe('event names', () => {
  it('are stable - the builder listens for these exact strings', () => {
    // A rename on one side only would silently disconnect the palette.
    expect(CREATE_NODE_REQUEST_EVENT).toBe('workflowCreateNodeRequest');
    expect(NODE_CREATOR_VISIBILITY_EVENT).toBe('workflowNodeCreatorVisibilityChange');
  });

  it('is inert without a listener (no throw)', () => {
    expect(() => requestCreateNode({ item: 'note' })).not.toThrow();
    expect(() => publishNodeCreatorVisibility({ open: true })).not.toThrow();
    expect(vi.isMockFunction(requestCreateNode)).toBe(false);
  });
});
