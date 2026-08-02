import { describe, it, expect } from 'vitest';
import type { Edge } from 'reactflow';
import { classifyBackEdges, isAncestor, withDerivedBackEdges } from '../backEdgeDetection';

/**
 * Which edges close a loop.
 *
 * The flag used to be stamped once, when the user drew the connection, which went stale in both
 * directions: deleting the forward path left an edge permanently mislabelled (and failing
 * validation with "target must be an ancestor"), while drawing the loop-back BEFORE the forward
 * path existed left it never labelled - reported as a cycle error and saved without its marker,
 * so the engine would deadlock on it.
 */
const edge = (id: string, source: string, target: string, data?: Record<string, unknown>): Edge => ({
  id,
  source,
  target,
  ...(data ? { data } : {}),
});

describe('isAncestor', () => {
  it('finds a node reachable forward from the target', () => {
    const edges = [edge('e1', 'a', 'b'), edge('e2', 'b', 'c')];
    expect(isAncestor('a', 'c', edges)).toBe(true);
  });

  it('returns false when the target is downstream, not upstream', () => {
    const edges = [edge('e1', 'a', 'b'), edge('e2', 'b', 'c')];
    expect(isAncestor('c', 'a', edges)).toBe(false);
  });

  it('terminates on a graph that already contains a cycle', () => {
    const edges = [edge('e1', 'a', 'b'), edge('e2', 'b', 'a')];
    expect(isAncestor('a', 'b', edges)).toBe(true);
  });
});

describe('classifyBackEdges', () => {
  it('keeps the edge the user drew backwards as the loop-back', () => {
    const edges = [
      edge('fwd', 'a', 'b'),
      edge('loop', 'b', 'a', { isBackEdge: true }),
    ];
    expect(classifyBackEdges(edges)).toEqual(new Set(['loop']));
  });

  it('un-flags an edge that no longer closes a loop', () => {
    // The forward path was deleted, so b -> a is now just an ordinary edge.
    const edges = [edge('loop', 'b', 'a', { isBackEdge: true })];
    expect(classifyBackEdges(edges).has('loop')).toBe(false);
  });

  it('flags a loop drawn BEFORE the forward path existed', () => {
    // Never stamped at connect time, because at that moment 'a' was not yet an ancestor.
    // 'trigger' anchors the traversal so the answer is the edge that closes the cycle
    // (b -> a), not whichever node the walk happened to start from.
    const edges = [
      edge('entry', 'trigger', 'a'),
      edge('loop', 'b', 'a'),
      edge('fwd', 'a', 'b'),
    ];
    expect(classifyBackEdges(edges)).toEqual(new Set(['loop']));
  });

  it('leaves an acyclic graph completely untouched', () => {
    const edges = [edge('e1', 'a', 'b'), edge('e2', 'b', 'c'), edge('e3', 'a', 'c')];
    expect(classifyBackEdges(edges).size).toBe(0);
  });

  it('breaks a longer cycle at exactly one edge', () => {
    const edges = [
      edge('e1', 'trigger', 'a'),
      edge('e2', 'a', 'b'),
      edge('e3', 'b', 'c'),
      edge('e4', 'c', 'a'),
    ];
    const result = classifyBackEdges(edges);
    expect(result.size).toBe(1);
    expect(result.has('e4')).toBe(true);
  });

  it('handles two independent loops', () => {
    const edges = [
      edge('a1', 'a', 'b'),
      edge('a2', 'b', 'a', { isBackEdge: true }),
      edge('b1', 'x', 'y'),
      edge('b2', 'y', 'x', { isBackEdge: true }),
    ];
    expect(classifyBackEdges(edges)).toEqual(new Set(['a2', 'b2']));
  });
});

describe('withDerivedBackEdges', () => {
  it('writes the flag the canvas AND the saved plan both read', () => {
    // Both used to answer this question from different places: the canvas recomputed it while
    // the plan read a flag stamped at connect time, so an edge could render orange and still be
    // saved as a forward dependency.
    const edges = [edge('fwd', 'a', 'b'), edge('loop', 'b', 'a')];

    const derived = withDerivedBackEdges(edges);

    expect(derived.find((e) => e.id === 'loop')?.data?.isBackEdge).toBe(true);
    expect(derived.find((e) => e.id === 'fwd')?.data?.isBackEdge).toBe(false);
  });

  it('clears a flag left behind when the loop was broken', () => {
    const edges = [edge('loop', 'b', 'a', { isBackEdge: true })];
    expect(withDerivedBackEdges(edges)[0].data?.isBackEdge).toBe(false);
  });

  it('leaves a While node its own loop-back handle', () => {
    // That connection is already modelled as an iterate port; marking it again would describe
    // the same loop twice and turn a working While into a declared back-edge.
    const edges = [
      edge('body', 'while-1', 'step'),
      edge('back', 'step', 'while-1', undefined),
    ];
    edges[1].targetHandle = 'while-while-1-loop-back';

    const derived = withDerivedBackEdges(edges);

    expect(derived.find((e) => e.id === 'back')?.data?.isBackEdge).toBe(false);
  });

  it('preserves identity for edges whose classification did not change', () => {
    const edges = [edge('fwd', 'a', 'b', { isBackEdge: false })];
    expect(withDerivedBackEdges(edges)[0]).toBe(edges[0]);
  });
});
