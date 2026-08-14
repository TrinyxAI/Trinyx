/**
 * DAG reading order for the run panel's step list.
 *
 * The panel used to render steps in whatever order the aggregate arrived in
 * (WebSocket push order for "all epochs", backend row order for a single one),
 * which is roughly first-execution order and drifts apart from the graph as
 * soon as branches run in parallel or an epoch re-fires only part of the DAG.
 * Ordering by depth instead gives the list a stable, readable direction that
 * matches the canvas: triggers first, each branch's last node last.
 *
 * Dependency-free (no React, no reactflow import beyond types) so it stays
 * trivially unit-testable, like its neighbour `runFormatting.ts`.
 */

/** Minimal node shape: an id and, optionally, where it sits on the canvas. */
export interface DagOrderNode {
  id: string;
  position?: { x: number; y: number };
}

/** Minimal edge shape: reactflow's `source`/`target` carry node ids. */
export interface DagOrderEdge {
  source: string;
  target: string;
}

/**
 * Order every node into a single reading sequence: `Map<nodeId, index>` where 0
 * is the first row to render.
 *
 * A topological sort with a **deepest-ready-first** tie-break, which buys three
 * properties a plain breadth-first ranking does not:
 *
 * - **A node never renders above anything feeding it.** Depth is the LONGEST
 *   path from a root, so a merge sitting after both a 1-node and a 4-node branch
 *   lands below all five, not above half of them.
 * - **A branch stays contiguous.** Preferring the deepest ready node follows the
 *   chain you are reading to its end before starting a sibling, instead of
 *   interleaving both branches rank by rank.
 * - **Each DAG stays in one block.** A trigger is the only node at depth 0, so
 *   every node still pending in the DAG being read outranks the next trigger:
 *   with several triggers on one canvas, each DAG is emitted whole, its terminal
 *   nodes last, before the next one starts. That is the shape the panel is for.
 *
 * Two more rules where the graph is not a clean tree:
 *
 * - **Cycles are expected, not an error.** A loop's back edge (`…:iterate` in
 *   the plan) makes the body genuinely cyclic and stalls Kahn's algorithm. The
 *   stall is broken by force-emitting the remaining node with the fewest
 *   unsatisfied predecessors, which is the loop's entry point: the body then
 *   lays out after it in flow order instead of collapsing to the bottom.
 * - **Equally deep candidates are ordered by canvas position, `y` then `x`.**
 *   One comparison covers both layout directions: under the horizontal (`LR`)
 *   layout same-rank nodes share an `x` and differ in `y`; under the vertical
 *   (`TB`) one they share a `y`, so it falls through to `x`. Nodes without a
 *   position fall back to their order in the input.
 *
 * Returns an EMPTY map when there is nothing to order (no nodes). Callers treat
 * an empty map as "no opinion" and keep the order they were given, which is
 * what happens when the panel is open with no canvas mounted.
 */
export function computeDagOrder(
  nodes: readonly DagOrderNode[],
  edges: readonly DagOrderEdge[],
): Map<string, number> {
  const order = new Map<string, number>();
  if (!nodes?.length) return order;

  const inputIndex = new Map<string, number>();
  nodes.forEach((n, i) => {
    // A duplicate id would corrupt the in-degree bookkeeping; first one wins.
    if (!inputIndex.has(n.id)) inputIndex.set(n.id, i);
  });

  const successors = new Map<string, string[]>();
  const inDegree = new Map<string, number>();
  for (const n of nodes) inDegree.set(n.id, 0);

  for (const e of edges ?? []) {
    // Ignore self-loops and edges pointing outside the published node set: both
    // exist transiently while the canvas is being rebuilt, and either would
    // leave a node permanently blocked.
    if (!e || e.source === e.target) continue;
    if (!inputIndex.has(e.source) || !inputIndex.has(e.target)) continue;
    const outs = successors.get(e.source);
    if (outs) outs.push(e.target);
    else successors.set(e.source, [e.target]);
    inDegree.set(e.target, (inDegree.get(e.target) ?? 0) + 1);
  }

  const depth = new Map<string, number>();
  for (const n of nodes) depth.set(n.id, 0);

  const remaining = new Set(inputIndex.keys());
  /** Nodes whose predecessors are all emitted, waiting to be picked. */
  const ready = nodes.filter((n) => (inDegree.get(n.id) ?? 0) === 0).map((n) => n.id);

  let position = 0;
  const release = (id: string) => {
    remaining.delete(id);
    order.set(id, position++);
    const myDepth = depth.get(id) ?? 0;
    for (const next of successors.get(id) ?? []) {
      if (!remaining.has(next)) continue;
      // Longest path: a node sinks below its deepest predecessor.
      if (myDepth + 1 > (depth.get(next) ?? 0)) depth.set(next, myDepth + 1);
      const left = (inDegree.get(next) ?? 0) - 1;
      inDegree.set(next, left);
      if (left <= 0) ready.push(next);
    }
  };

  while (remaining.size > 0) {
    if (ready.length === 0) {
      // Cycle: nothing is fully satisfied. Force the node with the fewest
      // unsatisfied predecessors (ties by input order) - on a loop that is its
      // entry node, the one already reachable from outside the cycle.
      let best: string | null = null;
      let bestKey = Number.POSITIVE_INFINITY;
      for (const id of remaining) {
        const key = (inDegree.get(id) ?? 0) * 1e6 + (inputIndex.get(id) ?? 0);
        if (key < bestKey) {
          bestKey = key;
          best = id;
        }
      }
      if (best == null) break;
      inDegree.set(best, 0);
      ready.push(best);
    }

    // Pick the DEEPEST ready node: that continues the branch being read instead
    // of hopping to a sibling, and keeps the current DAG ahead of the next
    // trigger (depth 0). A linear scan, not a heap - `ready` holds at most the
    // graph's width, and a canvas is tens of nodes.
    let pickAt = -1;
    for (let i = 0; i < ready.length; i += 1) {
      if (!remaining.has(ready[i])) continue;
      if (pickAt < 0 || compareCandidates(ready[i], ready[pickAt], depth, inputIndex, nodes) < 0) {
        pickAt = i;
      }
    }
    if (pickAt < 0) {
      ready.length = 0;
      continue;
    }
    const picked = ready[pickAt];
    ready.splice(pickAt, 1);
    release(picked);
  }

  return order;
}

/** Deepest first, then canvas position (`y` then `x`), then input order. */
function compareCandidates(
  a: string,
  b: string,
  depth: Map<string, number>,
  inputIndex: Map<string, number>,
  nodes: readonly DagOrderNode[],
): number {
  const da = depth.get(a) ?? 0;
  const db = depth.get(b) ?? 0;
  if (da !== db) return db - da;
  const pa = nodes[inputIndex.get(a) ?? 0]?.position;
  const pb = nodes[inputIndex.get(b) ?? 0]?.position;
  if (pa && pb) {
    if (pa.y !== pb.y) return pa.y - pb.y;
    if (pa.x !== pb.x) return pa.x - pb.x;
  }
  return (inputIndex.get(a) ?? 0) - (inputIndex.get(b) ?? 0);
}

/**
 * Sort `items` into DAG reading order, given how to find each item's node id.
 *
 * Anything the canvas does not know about (a step whose node was deleted since
 * the run, a step from a sub-workflow) keeps its incoming relative order and
 * lands AFTER the ordered rows rather than being dropped or scattered: the
 * panel must still show every step it was handed.
 *
 * A no-op returning the SAME array reference when there is no order to apply,
 * so a caller memoizing on it does not re-render for nothing.
 */
export function sortByDagOrder<T>(
  items: T[],
  order: Map<string, number>,
  nodeIdOf: (item: T) => string | undefined,
): T[] {
  if (order.size === 0 || items.length < 2) return items;
  const ranked = items.map((item, index) => {
    const id = nodeIdOf(item);
    const rank = id != null ? order.get(id) : undefined;
    return { item, index, rank: rank ?? Number.POSITIVE_INFINITY };
  });
  ranked.sort((a, b) => (a.rank !== b.rank ? a.rank - b.rank : a.index - b.index));
  return ranked.map((r) => r.item);
}
