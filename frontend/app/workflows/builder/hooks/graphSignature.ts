'use client';

import type { Node, Edge } from 'reactflow';

/**
 * Volatile/runtime keys in node data that are NOT user edits.
 * These change on their own (streaming updates, mode transitions, UI state), so
 * anything that answers "did the user change the workflow?" must ignore them.
 */
export const VOLATILE_DATA_KEYS = new Set([
  // Run-mode status (set by streaming / useRunStateProcessing)
  'status', 'statusCounts', 'metrics',
  // Loop iteration counters (set during execution)
  'currentIteration', 'maxIterations', 'totalIterations', 'completedItems',
  // Decision evaluation state
  'selectedBranch',
  // UI runtime state (set by usePreparedGraph)
  'highlightState', 'selectedLoopChildId', 'isPreviewMode', 'validationIssues',
  // Callback functions (already ignored by JSON.stringify, listed for clarity)
  'onDeleteNode', 'onDuplicateNode', 'onTogglePreview', 'onNodeUpdate',
  'onExtractLoopChild', 'onNoteUpdate', 'onLoopClick', 'onLoopChildClick',
  'onCreateNode', 'onConnect',
]);

/** Strip volatile keys from a data object for stable hashing. */
export function stripVolatile(data: Record<string, any> | undefined): Record<string, any> {
  if (!data) return {};
  const clean: Record<string, any> = {};
  for (const key of Object.keys(data)) {
    if (VOLATILE_DATA_KEYS.has(key)) continue;
    // For loopChildren, recursively strip volatile keys from each child
    if (key === 'loopChildren' && Array.isArray(data[key])) {
      clean[key] = data[key].map((child: Record<string, any>) => stripVolatile(child));
    } else {
      clean[key] = data[key];
    }
  }
  return clean;
}

/**
 * Canonical signature of the graph a user can actually edit and save.
 *
 * Deliberately narrower than `JSON.stringify(nodes)`: React Flow writes measured
 * dimensions and selection flags straight back into node objects after mount, so a
 * raw stringify reports "changed" on a workflow nobody touched. Only the fields
 * below are persisted by the save path, so only they define a user edit.
 *
 * Shared by `useDirtyState` (arms Save) and `useHistory` (arms Undo) so the two
 * cannot disagree about what an edit is.
 */
export function computeGraphSignature(nodesList: Node[], edgesList: Edge[]): string {
  const nodesData = nodesList.map(n => ({
    id: n.id,
    type: n.type,
    position: n.position,
    data: stripVolatile(n.data as Record<string, any>),
  }));
  const edgesData = edgesList.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle,
    targetHandle: e.targetHandle,
    // Loop-back settings live on the edge. Without them, editing a back-edge's condition or
    // iteration cap left the workflow "clean" and the change was lost on navigate-away.
    backEdgeCondition: (e.data as Record<string, unknown> | undefined)?.backEdgeCondition,
    backEdgeMaxIterations: (e.data as Record<string, unknown> | undefined)?.backEdgeMaxIterations,
  }));
  return JSON.stringify({ nodes: nodesData, edges: edgesData });
}
