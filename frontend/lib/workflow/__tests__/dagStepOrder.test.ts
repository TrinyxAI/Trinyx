/**
 * DAG reading order for the run panel.
 *
 * The list used to render in arrival order (WebSocket push order, or backend row
 * order for a single epoch), so a workflow whose branches run in parallel showed
 * its nodes shuffled and a re-fired epoch reshuffled them again. These pin the
 * three properties that make the order readable and STABLE: a node is always
 * below every node feeding it, siblings follow the canvas, and a loop's back
 * edge does not deadlock the sort or push the body to the bottom.
 */
import { describe, expect, it } from 'vitest';
import { computeDagOrder, sortByDagOrder } from '@/lib/workflow/dagStepOrder';

/** Node at a canvas position - the sibling tie-break reads it. */
const n = (id: string, x = 0, y = 0) => ({ id, position: { x, y } });
const e = (source: string, target: string) => ({ source, target });

/** Node ids in render order, for readable assertions. */
function ordered(nodes: { id: string }[], edges: { source: string; target: string }[]): string[] {
  const order = computeDagOrder(nodes, edges);
  return [...order.entries()].sort((a, b) => a[1] - b[1]).map(([id]) => id);
}

describe('computeDagOrder - a run reads the way it executes', () => {
  it('puts the trigger first and the terminal node last on a linear chain', () => {
    // Deliberately handed to the sort BACKWARDS: arrival order must not survive.
    const nodes = [n('last'), n('middle'), n('trigger')];
    const edges = [e('trigger', 'middle'), e('middle', 'last')];

    expect(ordered(nodes, edges)).toEqual(['trigger', 'middle', 'last']);
  });

  it('places a merge below EVERY branch feeding it, not below the shortest', () => {
    // trigger → short → merge
    // trigger → long1 → long2 → long3 → merge
    // Shortest-path depth would rank the merge at 2 and draw it above long2/long3,
    // i.e. above half the nodes whose output it consumes.
    const nodes = [
      n('trigger'), n('short', 0, 0), n('long1', 0, 100), n('long2', 0, 100),
      n('long3', 0, 100), n('merge'),
    ];
    const edges = [
      e('trigger', 'short'), e('short', 'merge'),
      e('trigger', 'long1'), e('long1', 'long2'), e('long2', 'long3'), e('long3', 'merge'),
    ];

    const result = ordered(nodes, edges);
    expect(result[0]).toBe('trigger');
    expect(result[result.length - 1]).toBe('merge');
    for (const upstream of ['short', 'long1', 'long2', 'long3']) {
      expect(result.indexOf(upstream)).toBeLessThan(result.indexOf('merge'));
    }
  });

  it('keeps each branch contiguous instead of interleaving them rank by rank', () => {
    // trigger → a1 → a2 → merge
    // trigger → b1 → b2 → merge
    // Rank-by-rank (breadth-first) gives a1, b1, a2, b2 - you cannot follow a
    // chain without jumping. Reading one branch to its end is the point.
    const nodes = [
      n('trigger'), n('a1', 100, 0), n('a2', 200, 0),
      n('b1', 100, 200), n('b2', 200, 200), n('merge', 300, 100),
    ];
    const edges = [
      e('trigger', 'a1'), e('a1', 'a2'), e('a2', 'merge'),
      e('trigger', 'b1'), e('b1', 'b2'), e('b2', 'merge'),
    ];

    expect(ordered(nodes, edges)).toEqual(['trigger', 'a1', 'a2', 'b1', 'b2', 'merge']);
  });

  it('emits one whole DAG before the next when the canvas has several triggers', () => {
    // "the last nodes of EACH dag at the bottom": two independent graphs must
    // not be interleaved, or neither reads as a sequence.
    const nodes = [
      n('t1', 0, 0), n('t1-step', 100, 0),
      n('t2', 0, 400), n('t2-step', 100, 400), n('t2-end', 200, 400),
    ];
    const edges = [
      e('t1', 't1-step'),
      e('t2', 't2-step'), e('t2-step', 't2-end'),
    ];

    expect(ordered(nodes, edges)).toEqual(['t1', 't1-step', 't2', 't2-step', 't2-end']);
  });

  it('orders parallel branches by canvas position so the list matches what is drawn', () => {
    // Same depth, so only the position separates them. `y` then `x` covers both
    // layout directions: horizontal siblings differ in y, vertical ones share a
    // y and differ in x.
    const horizontal = ordered(
      [n('trigger'), n('bottom', 200, 300), n('top', 200, 10)],
      [e('trigger', 'bottom'), e('trigger', 'top')],
    );
    expect(horizontal).toEqual(['trigger', 'top', 'bottom']);

    const vertical = ordered(
      [n('trigger'), n('right', 300, 200), n('left', 10, 200)],
      [e('trigger', 'right'), e('trigger', 'left')],
    );
    expect(vertical).toEqual(['trigger', 'left', 'right']);
  });

  it('lays a loop out in flow order instead of deadlocking on its back edge', () => {
    // trigger → loop → body → (back to loop). Every node in the cycle keeps a
    // predecessor forever, so plain Kahn emits nothing for them.
    const nodes = [n('trigger'), n('loop'), n('body'), n('after')];
    const edges = [
      e('trigger', 'loop'), e('loop', 'body'), e('body', 'loop'), e('loop', 'after'),
    ];

    const result = ordered(nodes, edges);
    expect(result).toHaveLength(4);
    expect(result[0]).toBe('trigger');
    expect(result.indexOf('loop')).toBeLessThan(result.indexOf('body'));
    expect(result.indexOf('loop')).toBeLessThan(result.indexOf('after'));
  });

  it('emits every node even when the whole graph is one cycle', () => {
    // No entry point at all: the tie-break has to force-start somewhere or the
    // sort loops forever.
    const result = ordered([n('a'), n('b'), n('c')], [e('a', 'b'), e('b', 'c'), e('c', 'a')]);
    expect(result).toHaveLength(3);
    expect(new Set(result)).toEqual(new Set(['a', 'b', 'c']));
  });

  it('ignores self-loops and edges pointing outside the published nodes', () => {
    // Both appear transiently while the canvas rebuilds; either would leave a
    // node blocked forever on a predecessor that will never be emitted.
    const result = ordered(
      [n('trigger'), n('step')],
      [e('trigger', 'step'), e('step', 'step'), e('ghost', 'step'), e('step', 'ghost')],
    );
    expect(result).toEqual(['trigger', 'step']);
  });

  it('returns an empty map for an empty canvas so callers keep their own order', () => {
    expect(computeDagOrder([], []).size).toBe(0);
    expect(computeDagOrder([], [e('a', 'b')]).size).toBe(0);
  });

  it('still ranks every node when the graph has no edges at all', () => {
    const order = computeDagOrder([n('a'), n('b')], []);
    expect(order.size).toBe(2);
  });
});

describe('computeDagOrder - the same plan always gives the same order', () => {
  // The whole point of ordering by the graph rather than by arrival: the list
  // must not move between two openings of the same run. Nothing in here may
  // depend on Map/Set iteration luck, on the order edges were declared, or on
  // the order nodes happened to be queued.
  const NODES = [
    n('trigger', 0, 100), n('a1', 100, 0), n('a2', 200, 0),
    n('b1', 100, 200), n('b2', 200, 200), n('merge', 300, 100),
  ];
  const EDGES = [
    e('trigger', 'a1'), e('a1', 'a2'), e('a2', 'merge'),
    e('trigger', 'b1'), e('b1', 'b2'), e('b2', 'merge'),
  ];
  const EXPECTED = ['trigger', 'a1', 'a2', 'b1', 'b2', 'merge'];

  it('is a pure function of its inputs', () => {
    expect(ordered(NODES, EDGES)).toEqual(EXPECTED);
    expect(ordered(NODES, EDGES)).toEqual(EXPECTED);
  });

  it('does not depend on the order the nodes were handed over', () => {
    // The plan, the DB and the canvas can all hand the same graph over in a
    // different array order. Positions and depth decide, not arrival.
    const shuffles = [
      [5, 4, 3, 2, 1, 0],
      [2, 0, 4, 1, 5, 3],
      [3, 5, 1, 4, 0, 2],
    ];
    for (const shuffle of shuffles) {
      expect(ordered(shuffle.map((i) => NODES[i]), EDGES)).toEqual(EXPECTED);
    }
  });

  it('does not depend on the order the edges were declared', () => {
    const reversed = [...EDGES].reverse();
    const interleaved = [EDGES[3], EDGES[0], EDGES[4], EDGES[1], EDGES[5], EDGES[2]];
    expect(ordered(NODES, reversed)).toEqual(EXPECTED);
    expect(ordered(NODES, interleaved)).toEqual(EXPECTED);
  });

  it('still settles on ONE order when two nodes share a position', () => {
    // The position tie-break cannot separate them, so the input order breaks
    // the tie. It is arbitrary but total: the same input can never produce two
    // different results, which is what "stable across openings" needs.
    const stacked = [n('trigger'), n('x', 100, 50), n('y', 100, 50)];
    const edges = [e('trigger', 'x'), e('trigger', 'y')];
    const first = ordered(stacked, edges);
    expect(first).toEqual(['trigger', 'x', 'y']);
    expect(ordered(stacked, edges)).toEqual(first);
  });
});

describe('sortByDagOrder', () => {
  const order = new Map([['t', 0], ['a', 1], ['b', 2]]);

  it('reorders items by their node rank', () => {
    const items = [{ id: 'b' }, { id: 't' }, { id: 'a' }];
    expect(sortByDagOrder(items, order, (i) => i.id).map((i) => i.id)).toEqual(['t', 'a', 'b']);
  });

  it('keeps steps the canvas cannot resolve, in their incoming order, at the end', () => {
    // A node deleted since the run still has rows; dropping or scattering them
    // would silently hide execution data.
    const items = [{ id: 'gone-1' }, { id: 'b' }, { id: 'gone-2' }, { id: 't' }];
    expect(sortByDagOrder(items, order, (i) => i.id).map((i) => i.id))
      .toEqual(['t', 'b', 'gone-1', 'gone-2']);
  });

  it('returns the SAME array reference when there is no order to apply', () => {
    // The caller memoizes on this: a fresh array every render would re-render
    // the whole step list on every WebSocket push for nothing.
    const items = [{ id: 'b' }, { id: 'a' }];
    expect(sortByDagOrder(items, new Map(), (i) => i.id)).toBe(items);
    const single = [{ id: 'b' }];
    expect(sortByDagOrder(single, order, (i) => i.id)).toBe(single);
    // ...and a real sort does produce a new array rather than mutating the input.
    const sorted = sortByDagOrder(items, order, (i) => i.id);
    expect(sorted).not.toBe(items);
    expect(items.map((i) => i.id)).toEqual(['b', 'a']);
  });
});
