/**
 * Lightweight module-level store for canvas nodes.
 * Set by WorkflowBuilder, readable by any component that needs to
 * resolve step aliases to node data (e.g. for icons).
 *
 * Keyed by workflow: several canvases can be mounted at once (the page canvas
 * plus a sub-workflow or application canvas in a side-panel tab), and the run
 * panel resolves a step's icon and label through this store. With a single
 * global slot, whichever canvas rendered last won and the panel could label a
 * step with another workflow's node. Callers that know their workflow pass it;
 * the arg-less read stays for the legacy call sites, which run on the page
 * canvas only.
 */
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../types';

const _byWorkflow = new Map<string, Node<BuilderNodeData>[]>();
/** Last canvas that published, for readers that cannot name a workflow. */
let _latest: Node<BuilderNodeData>[] = [];
const _edgesByWorkflow = new Map<string, Edge[]>();
let _latestEdges: Edge[] = [];
const _listeners = new Set<() => void>();

/**
 * Watch for publishes. A module-level store is invisible to React, so a reader
 * that renders BEFORE the canvas publishes (the run panel opened straight on the
 * Run tab) would stay empty forever, and one that renders after a rename or a
 * deletion would keep the old labels. Renders are driven off this, not off a
 * one-off "node created" window event.
 */
export function subscribeCanvasNodes(listener: () => void): () => void {
  _listeners.add(listener);
  return () => { _listeners.delete(listener); };
}

function notify() {
  _listeners.forEach((listener) => listener());
}

export function setCanvasNodes(nodes: Node<BuilderNodeData>[], workflowId?: string) {
  _latest = nodes;
  if (workflowId) _byWorkflow.set(workflowId, nodes);
  notify();
}

export function getCanvasNodes(workflowId?: string): Node<BuilderNodeData>[] {
  if (workflowId) {
    // A named workflow NEVER falls back to another canvas: before it has
    // published (the mount window, when two canvases race) the honest answer is
    // "no nodes yet", which renders a neutral placeholder icon. Returning the
    // other canvas's nodes there is the mislabelling this keying exists to
    // prevent, just moved to a narrower window.
    return _byWorkflow.get(workflowId) ?? [];
  }
  return _latest;
}

/**
 * Publish the canvas EDGES, keyed exactly like the nodes.
 *
 * Kept as a separate entry point rather than a third argument to
 * {@link setCanvasNodes}: the builder holds nodes and edges in two independent
 * states and publishes them from two effects, so folding them into one call
 * would make each publish overwrite the other half with a stale copy.
 *
 * Readers that need the graph's SHAPE (the run panel, which orders its step
 * list by DAG depth) need these; readers that only resolve a step's icon do not
 * and can keep calling {@link getCanvasNodes} alone.
 */
export function setCanvasEdges(edges: Edge[], workflowId?: string) {
  _latestEdges = edges;
  if (workflowId) _edgesByWorkflow.set(workflowId, edges);
  notify();
}

export function getCanvasEdges(workflowId?: string): Edge[] {
  if (workflowId) {
    // Same rule as getCanvasNodes: a named workflow never borrows another
    // canvas's graph. No edges = no DAG order, and consumers fall back to the
    // order they were given - never to a foreign workflow's topology.
    return _edgesByWorkflow.get(workflowId) ?? [];
  }
  return _latestEdges;
}

/** Drop stored nodes and edges (canvas unmounted, workspace switch). */
export function clearCanvasNodes(workflowId?: string) {
  if (workflowId) {
    const wasLatest = _byWorkflow.get(workflowId) === _latest;
    const wasLatestEdges = _edgesByWorkflow.get(workflowId) === _latestEdges;
    _byWorkflow.delete(workflowId);
    _edgesByWorkflow.delete(workflowId);
    if (wasLatestEdges) {
      const edgeSurvivors = [..._edgesByWorkflow.values()];
      _latestEdges = edgeSurvivors.length > 0 ? edgeSurvivors[edgeSurvivors.length - 1] : [];
    }
    // The arg-less readers resolve step icons and labels through `_latest`. Left
    // pointing at the canvas that just unmounted it labels with a graph nobody is
    // looking at; blanked, those readers lose their icons entirely while another
    // canvas is still on screen. Hand it to a survivor when there is one - Map
    // iteration is insertion-ordered, so this is the most recent remaining
    // publisher.
    if (wasLatest) {
      const survivors = [..._byWorkflow.values()];
      _latest = survivors.length > 0 ? survivors[survivors.length - 1] : [];
    }
    notify();
    return;
  }
  _byWorkflow.clear();
  _latest = [];
  _edgesByWorkflow.clear();
  _latestEdges = [];
  notify();
}
