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
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';

const _byWorkflow = new Map<string, Node<BuilderNodeData>[]>();
/** Last canvas that published, for readers that cannot name a workflow. */
let _latest: Node<BuilderNodeData>[] = [];
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

/** Drop stored nodes (canvas unmounted, workspace switch). */
export function clearCanvasNodes(workflowId?: string) {
  if (workflowId) {
    const wasLatest = _byWorkflow.get(workflowId) === _latest;
    _byWorkflow.delete(workflowId);
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
  notify();
}
