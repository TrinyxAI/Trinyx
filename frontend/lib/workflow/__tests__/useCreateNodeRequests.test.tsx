/**
 * @vitest-environment jsdom
 *
 * The palette moved into the side panel, so the pick that used to be a direct
 * call is now a window event - and a window event reaches EVERY listener. Two
 * canvases are routinely mounted at once (the page canvas plus a sub-workflow or
 * application canvas in a panel tab), and some of them are read-only. These
 * guards are the whole reason a pick lands on one canvas and not the others.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

import { REVEAL_NODE_EVENT, requestCreateNode, requestRevealNode } from '@/lib/workflow/nodeCreatorBus';
import { useCreateNodeRequests } from '@/lib/workflow/useCreateNodeRequests';

function Canvas({ workflowId, isPreviewOnly, onCreate }: {
  workflowId?: string;
  isPreviewOnly?: boolean;
  onCreate: (item: unknown) => void;
}) {
  useCreateNodeRequests({ workflowId, isPreviewOnly, onCreate });
  return null;
}

afterEach(cleanup);

describe('useCreateNodeRequests', () => {
  it('creates the picked item on the canvas the request names', () => {
    const onCreate = vi.fn();
    render(<Canvas workflowId="wf-1" onCreate={onCreate} />);

    act(() => requestCreateNode({ workflowId: 'wf-1', item: 'core:decision' }));

    expect(onCreate).toHaveBeenCalledWith('core:decision');
  });

  it('ignores a pick aimed at ANOTHER canvas', () => {
    const mine = vi.fn();
    const other = vi.fn();
    render(<><Canvas workflowId="wf-1" onCreate={mine} /><Canvas workflowId="wf-2" onCreate={other} /></>);

    act(() => requestCreateNode({ workflowId: 'wf-2', item: 'core:merge' }));

    expect(mine).not.toHaveBeenCalled();
    expect(other).toHaveBeenCalledWith('core:merge');
  });

  it('never creates on a read-only surface (run canvas / marketplace preview)', () => {
    const onCreate = vi.fn();
    render(<Canvas workflowId="wf-1" isPreviewOnly onCreate={onCreate} />);

    act(() => requestCreateNode({ workflowId: 'wf-1', item: 'core:decision' }));

    expect(onCreate).not.toHaveBeenCalled();
  });

  it('accepts an unaddressed pick - the palette does not always know the workflow', () => {
    const onCreate = vi.fn();
    render(<Canvas workflowId="wf-1" onCreate={onCreate} />);

    act(() => requestCreateNode({ item: 'core:wait' }));

    expect(onCreate).toHaveBeenCalledWith('core:wait');
  });

  it('names the node the canvas must pan onto', () => {
    // The palette takes canvas width now, so a click-add can land outside the
    // visible viewport - and ReactFlow's onlyRenderVisibleElements keeps an
    // off-screen node out of the DOM entirely. The node saves fine; the user just
    // sees nothing happen. The canvas needs the id to pan.
    const seen: unknown[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener(REVEAL_NODE_EVENT, handler);
    try {
      requestRevealNode('split-123');
      requestRevealNode('');
    } finally {
      window.removeEventListener(REVEAL_NODE_EVENT, handler);
    }
    // The empty id is dropped rather than sending the canvas hunting for nothing.
    expect(seen).toEqual([{ nodeId: 'split-123' }]);
  });

  it('stops listening once the canvas unmounts', () => {
    const onCreate = vi.fn();
    const { unmount } = render(<Canvas workflowId="wf-1" onCreate={onCreate} />);

    unmount();
    act(() => requestCreateNode({ workflowId: 'wf-1', item: 'core:decision' }));

    expect(onCreate).not.toHaveBeenCalled();
  });
});
