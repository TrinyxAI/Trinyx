import type { Edge } from 'reactflow';

/**
 * Back-edge classification for the builder graph.
 *
 * A back-edge is an edge pointing at a node that already ran earlier in the flow: drawing one
 * turns that part of the graph into a loop. The backend treats such an edge as a re-entry rather
 * than a dependency, so the two sides must agree on which edges they are.
 */

/**
 * Check if targetId is an ancestor of sourceId, following the given edges forward.
 * If true, an edge from source to target closes a loop.
 */
export function isAncestor(targetId: string, sourceId: string, edges: Edge[]): boolean {
  const successors = new Map<string, Set<string>>();
  for (const edge of edges) {
    const targets = successors.get(edge.source) || new Set<string>();
    targets.add(edge.target);
    successors.set(edge.source, targets);
  }

  const visited = new Set<string>([targetId]);
  const queue = [targetId];

  while (queue.length > 0) {
    const current = queue.shift()!;
    if (current === sourceId) return true;
    for (const next of successors.get(current) || []) {
      if (!visited.has(next)) {
        visited.add(next);
        queue.push(next);
      }
    }
  }
  return false;
}

/**
 * Decide which edges are back-edges, from the graph alone.
 *
 * <p>Recomputed on every render rather than stamped once at connect time, because a one-shot
 * stamp goes stale in both directions:
 * <ul>
 *   <li>delete the forward path and a flagged edge is no longer a loop, yet stays flagged (it
 *       then fails validation with "target must be an ancestor");</li>
 *   <li>draw B to A BEFORE the A..B path exists and it is never flagged, so it is reported as a
 *       cycle error and serialized without its marker.</li>
 * </ul>
 *
 * <p>Order of precedence:
 * <ol>
 *   <li>an edge the user explicitly drew backwards stays the back-edge, as long as it still
 *       closes a loop - the edge you drew is the one that breaks the cycle;</li>
 *   <li>any cycle still left afterwards has its closing edge detected by DFS, so a graph that
 *       became cyclic later still resolves to something the engine can run.</li>
 * </ol>
 */
export function classifyBackEdges(edges: Edge[]): Set<string> {
  const flagged = edges.filter((e) => e.data?.isBackEdge === true);
  const forward = edges.filter((e) => e.data?.isBackEdge !== true);

  const backEdgeIds = new Set<string>();
  const stillForward = [...forward];

  // 1. Keep flagged edges that still close a loop; demote the others to normal edges.
  for (const edge of flagged) {
    if (isAncestor(edge.target, edge.source, forward)) {
      backEdgeIds.add(edge.id);
    } else {
      stillForward.push(edge);
    }
  }

  // 2. Break any remaining cycle at its DFS back-edge.
  for (const id of findCycleClosingEdges(stillForward)) {
    backEdgeIds.add(id);
  }

  return backEdgeIds;
}

/**
 * Return the edges with `data.isBackEdge` recomputed from the graph.
 *
 * <p>The single place both the canvas and the saved plan get their answer from, so what the user
 * sees in orange is exactly what the engine is told to loop on. A While node's own loop-back
 * handle is left alone: it is already modelled as an iterate port, and marking it again would
 * describe the same loop twice.
 */
export function withDerivedBackEdges(edges: Edge[]): Edge[] {
  const backEdgeIds = classifyBackEdges(edges);
  return edges.map((edge) => {
    const isWhileLoopBack = edge.targetHandle?.endsWith('-loop-back') === true;
    const isBackEdge = !isWhileLoopBack && backEdgeIds.has(edge.id);
    if (edge.data?.isBackEdge === isBackEdge) return edge;
    return { ...edge, data: { ...edge.data, isBackEdge } };
  });
}

/**
 * Classic DFS back-edge detection: an edge whose target is on the current DFS stack closes a
 * cycle. Returns the ids of those edges.
 */
function findCycleClosingEdges(edges: Edge[]): Set<string> {
  const outgoing = new Map<string, Edge[]>();
  const allNodes = new Set<string>();
  for (const edge of edges) {
    const list = outgoing.get(edge.source) || [];
    list.push(edge);
    outgoing.set(edge.source, list);
    allNodes.add(edge.source);
    allNodes.add(edge.target);
  }

  const closing = new Set<string>();
  const visited = new Set<string>();
  const onStack = new Set<string>();

  const visit = (nodeId: string): void => {
    visited.add(nodeId);
    onStack.add(nodeId);
    for (const edge of outgoing.get(nodeId) || []) {
      if (onStack.has(edge.target)) {
        closing.add(edge.id);
      } else if (!visited.has(edge.target)) {
        visit(edge.target);
      }
    }
    onStack.delete(nodeId);
  };

  // Start from nodes with no incoming edge (triggers first), then anything left over so a
  // fully cyclic component is still visited.
  const hasIncoming = new Set(edges.map((e) => e.target));
  for (const nodeId of allNodes) {
    if (!hasIncoming.has(nodeId) && !visited.has(nodeId)) visit(nodeId);
  }
  for (const nodeId of allNodes) {
    if (!visited.has(nodeId)) visit(nodeId);
  }

  return closing;
}
