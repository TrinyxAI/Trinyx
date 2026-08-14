import * as React from 'react';
import { Node, Edge } from 'reactflow';
import { BuilderNodeData } from '../types';
import { computeGraphSignature } from './graphSignature';

interface HistoryEntry {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  /** Signature of the entry, so "did this change?" never re-derives it. */
  signature: string;
}

function snapshot(nodes: Node<BuilderNodeData>[], edges: Edge[]): HistoryEntry {
  return {
    nodes: JSON.parse(JSON.stringify(nodes)),
    edges: JSON.parse(JSON.stringify(edges)),
    signature: computeGraphSignature(nodes, edges),
  };
}

/**
 * Undo/redo stack for the builder canvas.
 *
 * Two rules keep "undoable" aligned with "the user changed something":
 *
 *  1. **The baseline is the graph as loaded, not as mounted.** `WorkflowBuilder`
 *     mounts with an empty graph (`INITIAL_NODES`) and `useWorkflowLoader` applies
 *     the real one afterwards. Seeding the stack at mount therefore recorded the
 *     load itself as an edit: Undo lit up on a workflow nobody had touched, and
 *     pressing it restored the EMPTY canvas (which a later save would persist).
 *     The baseline is re-seeded when `workflowLoaded` turns true.
 *  2. **An edit is a change of the saved graph, not of the React Flow objects.**
 *     React Flow writes measured dimensions back into the nodes after mount and
 *     `useSelection` toggles `selected`; a raw `JSON.stringify` comparison counted
 *     those as edits. Comparison goes through `computeGraphSignature`, the same
 *     signature `useDirtyState` uses to arm Save, so Undo and Save cannot disagree.
 */
export function useHistory(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  setNodes: (nodes: Node<BuilderNodeData>[]) => void,
  setEdges: (edges: Edge[]) => void,
  workflowLoaded: boolean = true
) {
  const [history, setHistory] = React.useState<HistoryEntry[]>(() => [snapshot(nodes, edges)]);
  const [historyIndex, setHistoryIndex] = React.useState(0);
  const isUndoRedoRef = React.useRef(false);
  const historyIndexRef = React.useRef(0);

  // Keep track of the latest nodes/edges in a ref for the timeout callback
  const nodesEdgesRef = React.useRef({ nodes, edges });

  React.useEffect(() => {
    nodesEdgesRef.current = { nodes, edges };
  }, [nodes, edges]);

  // Sync ref with state
  React.useEffect(() => {
    historyIndexRef.current = historyIndex;
  }, [historyIndex]);

  // Re-seed the baseline once the workflow is on the canvas. Declared after the
  // nodesEdgesRef effect on purpose: effects run in declaration order within a
  // commit, so the ref already holds the freshly loaded graph here (the loader
  // calls setNodes/setEdges and setWorkflowLoaded(true) in the same batch).
  React.useEffect(() => {
    if (!workflowLoaded) return;
    const { nodes: loadedNodes, edges: loadedEdges } = nodesEdgesRef.current;
    setHistory([snapshot(loadedNodes, loadedEdges)]);
    setHistoryIndex(0);
    historyIndexRef.current = 0;
  }, [workflowLoaded]);

  // Debounced save mechanism
  React.useEffect(() => {
    if (!workflowLoaded) {
      return;
    }
    if (isUndoRedoRef.current) {
      return;
    }

    const timeoutId = setTimeout(() => {
      const currentState = nodesEdgesRef.current;
      const currentSignature = computeGraphSignature(currentState.nodes, currentState.edges);

      setHistory((prevHistory) => {
        const currentIndex = historyIndexRef.current;
        const currentHistoryState = prevHistory[currentIndex];

        // Safety check
        if (!currentHistoryState) return prevHistory;

        if (currentSignature !== currentHistoryState.signature) {
          const newHistory = prevHistory.slice(0, currentIndex + 1);
          const newState = snapshot(currentState.nodes, currentState.edges);
          // Limit history to 50 states
          const updatedHistory = [...newHistory, newState].slice(-50);
          setHistoryIndex(updatedHistory.length - 1);
          return updatedHistory;
        }
        return prevHistory;
      });
    }, 300);

    return () => clearTimeout(timeoutId);
  }, [nodes, edges, workflowLoaded]); // Run when nodes or edges change

  const undo = React.useCallback(
    (onUndoStart?: () => void) => {
      if (historyIndex > 0) {
        isUndoRedoRef.current = true;
        const prevState = history[historyIndex - 1];
        setNodes(JSON.parse(JSON.stringify(prevState.nodes)));
        setEdges(JSON.parse(JSON.stringify(prevState.edges)));
        setHistoryIndex(historyIndex - 1);
        // Only call if it's actually a function (not an event object from onClick)
        if (typeof onUndoStart === 'function') onUndoStart();
        setTimeout(() => {
          isUndoRedoRef.current = false;
        }, 100);
      }
    },
    [history, historyIndex, setNodes, setEdges]
  );

  const redo = React.useCallback(
    (onRedoStart?: () => void) => {
      if (historyIndex < history.length - 1) {
        isUndoRedoRef.current = true;
        const nextState = history[historyIndex + 1];
        setNodes(JSON.parse(JSON.stringify(nextState.nodes)));
        setEdges(JSON.parse(JSON.stringify(nextState.edges)));
        setHistoryIndex(historyIndex + 1);
        // Only call if it's actually a function (not an event object from onClick)
        if (typeof onRedoStart === 'function') onRedoStart();
        setTimeout(() => {
          isUndoRedoRef.current = false;
        }, 100);
      }
    },
    [history, historyIndex, setNodes, setEdges]
  );

  return {
    undo,
    redo,
    canUndo: historyIndex > 0,
    canRedo: historyIndex < history.length - 1,
  };
}
