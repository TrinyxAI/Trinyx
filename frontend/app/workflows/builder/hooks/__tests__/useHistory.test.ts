// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { useHistory } from '../useHistory';

const node = (id: string, position = { x: 0, y: 0 }): Node<BuilderNodeData> => ({
  id,
  type: 'flowNode',
  position,
  data: { id, label: id, kind: 'action' } as BuilderNodeData,
});

const edge = (source: string, target: string): Edge => ({ id: `edge-${source}-${target}`, source, target });

/** Drive the 300 ms debounce the hook uses before it records a snapshot. */
function flushDebounce() {
  act(() => {
    vi.advanceTimersByTime(400);
  });
}

interface Props {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  workflowLoaded: boolean;
}

function setup(initial: Props) {
  const setNodes = vi.fn();
  const setEdges = vi.fn();
  const view = renderHook(
    ({ nodes, edges, workflowLoaded }: Props) => useHistory(nodes, edges, setNodes, setEdges, workflowLoaded),
    { initialProps: initial }
  );
  return { ...view, setNodes, setEdges };
}

describe('useHistory', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe('the load is not an edit', () => {
    it('leaves undo disabled after the loader paints the graph onto an empty canvas', () => {
      // The builder mounts with INITIAL_NODES = [] and useWorkflowLoader applies the
      // real graph a beat later, flipping workflowLoaded in the same batch.
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: [node('A'), node('B')], edges: [edge('A', 'B')], workflowLoaded: true });
      flushDebounce();

      expect(result.current.canUndo).toBe(false);
      expect(result.current.canRedo).toBe(false);
    });

    it('leaves undo disabled for a workflow with no plan (empty canvas, still loaded)', () => {
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: [], edges: [], workflowLoaded: true });
      flushDebounce();

      expect(result.current.canUndo).toBe(false);
    });

    it('records nothing while the workflow is still loading', () => {
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: [node('A')], edges: [], workflowLoaded: false });
      flushDebounce();

      expect(result.current.canUndo).toBe(false);
    });
  });

  describe('a real edit is undoable', () => {
    it('arms undo when a node is added after the load, and restores the loaded graph', () => {
      const loaded = [node('A')];
      const { result, rerender, setNodes, setEdges } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: loaded, edges: [], workflowLoaded: true });
      flushDebounce();
      expect(result.current.canUndo).toBe(false);

      rerender({ nodes: [...loaded, node('B')], edges: [], workflowLoaded: true });
      flushDebounce();
      expect(result.current.canUndo).toBe(true);

      act(() => {
        result.current.undo();
      });

      expect(setNodes).toHaveBeenCalledTimes(1);
      expect(setNodes.mock.calls[0][0].map((n: Node) => n.id)).toEqual(['A']);
      expect(setEdges).toHaveBeenCalledTimes(1);
    });

    it('arms undo when a node is moved', () => {
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: [node('A', { x: 0, y: 0 })], edges: [], workflowLoaded: true });
      flushDebounce();

      rerender({ nodes: [node('A', { x: 120, y: 40 })], edges: [], workflowLoaded: true });
      flushDebounce();

      expect(result.current.canUndo).toBe(true);
    });

    it('arms undo when an edge is connected', () => {
      const nodes = [node('A'), node('B')];
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes, edges: [], workflowLoaded: true });
      flushDebounce();

      rerender({ nodes, edges: [edge('A', 'B')], workflowLoaded: true });
      flushDebounce();

      expect(result.current.canUndo).toBe(true);
    });
  });

  describe('React Flow churn is not an edit', () => {
    it('ignores measured dimensions written back into the nodes after mount', () => {
      const loaded = [node('A')];
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: loaded, edges: [], workflowLoaded: true });
      flushDebounce();

      const measured = [{ ...node('A'), width: 260, height: 84, positionAbsolute: { x: 0, y: 0 } }];
      rerender({ nodes: measured as Node<BuilderNodeData>[], edges: [], workflowLoaded: true });
      flushDebounce();

      expect(result.current.canUndo).toBe(false);
    });

    it('ignores selection flags toggled by useSelection', () => {
      const loaded = [node('A')];
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: loaded, edges: [], workflowLoaded: true });
      flushDebounce();

      rerender({
        nodes: [{ ...node('A'), selected: true, dragging: false }] as Node<BuilderNodeData>[],
        edges: [],
        workflowLoaded: true,
      });
      flushDebounce();

      expect(result.current.canUndo).toBe(false);
    });

    it('ignores run-mode status streamed into node data', () => {
      const loaded = [node('A')];
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: loaded, edges: [], workflowLoaded: true });
      flushDebounce();

      const running = [{ ...node('A'), data: { ...node('A').data, status: 'RUNNING', metrics: { ms: 12 } } }];
      rerender({ nodes: running as Node<BuilderNodeData>[], edges: [], workflowLoaded: true });
      flushDebounce();

      expect(result.current.canUndo).toBe(false);
    });
  });

  describe('reloading re-baselines', () => {
    it('drops the previous stack when a second workflow is loaded into the same builder', () => {
      const { result, rerender } = setup({ nodes: [], edges: [], workflowLoaded: false });

      rerender({ nodes: [node('A')], edges: [], workflowLoaded: true });
      flushDebounce();
      rerender({ nodes: [node('A'), node('B')], edges: [], workflowLoaded: true });
      flushDebounce();
      expect(result.current.canUndo).toBe(true);

      // Navigating to another workflow: the loader lowers then raises workflowLoaded.
      rerender({ nodes: [node('A'), node('B')], edges: [], workflowLoaded: false });
      rerender({ nodes: [node('Z')], edges: [], workflowLoaded: true });
      flushDebounce();

      expect(result.current.canUndo).toBe(false);
    });
  });
});
