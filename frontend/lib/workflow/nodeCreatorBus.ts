'use client';

/**
 * Cross-tree bridge for the node palette ("Add Node").
 *
 * The palette moved out of the ReactFlow overlay into the workflow panel's
 * "Add Node" tab, which the SidePanel renders from the app-layout tree. The
 * builder that must actually create the node lives in the page tree, so the
 * two talk through window CustomEvents - the same pattern the trigger /
 * application / run panels already use.
 */

/** Palette item picked in the side panel → the builder creates the node. */
export const CREATE_NODE_REQUEST_EVENT = 'workflowCreateNodeRequest' as const;
/** The Add Node tab became visible / hidden (drives mobile selection reset). */
export const NODE_CREATOR_VISIBILITY_EVENT = 'workflowNodeCreatorVisibilityChange' as const;
/**
 * A node was just created from the palette → pan the canvas onto it.
 *
 * Needed because the palette now takes canvas WIDTH instead of floating over it:
 * a click-add lands on a fixed grid the narrower viewport may not cover, and
 * ReactFlow's `onlyRenderVisibleElements` keeps an off-screen node out of the DOM
 * entirely. The node exists and saves correctly, but the user sees nothing happen.
 */
export const REVEAL_NODE_EVENT = 'workflowRevealNode' as const;

export interface RevealNodeDetail {
  nodeId: string;
}

export interface CreateNodeRequestDetail {
  workflowId?: string;
  /** A palette id (string) or a fully built palette item (object). */
  item: unknown;
}

export interface NodeCreatorVisibilityDetail {
  workflowId?: string;
  open: boolean;
}

/** Ask the builder to add the picked palette item to the canvas. */
export function requestCreateNode(detail: CreateNodeRequestDetail): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<CreateNodeRequestDetail>(CREATE_NODE_REQUEST_EVENT, { detail }));
}

/** Ask the canvas to pan onto a node that was just created. */
export function requestRevealNode(nodeId: string): void {
  if (typeof window === 'undefined' || !nodeId) return;
  window.dispatchEvent(new CustomEvent<RevealNodeDetail>(REVEAL_NODE_EVENT, { detail: { nodeId } }));
}

/** Tell the builder whether the palette is currently on screen. */
export function publishNodeCreatorVisibility(detail: NodeCreatorVisibilityDetail): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<NodeCreatorVisibilityDetail>(NODE_CREATOR_VISIBILITY_EVENT, { detail }));
}
