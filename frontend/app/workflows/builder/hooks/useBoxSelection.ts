import * as React from 'react';
import type { Node, OnNodesChange, ReactFlowInstance, XYPosition } from 'reactflow';
import type { BuilderNodeData } from '../types';

interface UseBoxSelectionProps {
  instance: ReactFlowInstance | null;
  nodes: Node<BuilderNodeData>[];
  onSelectionChange: (selectedIds: string[]) => void;
  onForceNodesUpdate?: (nodes: Node<BuilderNodeData>[]) => void;
  onNodesChange: OnNodesChange;
  /**
   * Whether the canvas offers box selection at all. The stored cursor mode is a
   * GLOBAL preference, but only the editor exposes the select that changes it -
   * so run mode and the read-only preview must ignore a persisted `selection`
   * mode, or a user who armed it in the editor would land there with left-drag
   * panning dead and no control to restore it.
   */
  allowBoxSelection?: boolean;
}

/**
 * How a left-drag on the canvas behaves.
 * - `pan`: drag moves the viewport (the historical default).
 * - `selection`: drag draws a selection box over the nodes.
 */
export type CanvasCursorMode = 'pan' | 'selection';

/**
 * The mode is a user preference, not per-graph state, so every workflow opens
 * with the last choice. Read at mount: two canvases mounted at the same time
 * (the main builder plus a sub-workflow side panel) each keep the value they
 * started with until remount, and cross-tab is not live either.
 */
export const CANVAS_CURSOR_MODE_STORAGE_KEY = 'workflow:cursorMode';

/** Reads the stored mode, falling back to `pan` when absent or unreadable. */
export function readStoredCursorMode(): CanvasCursorMode {
  if (typeof window === 'undefined') return 'pan';
  try {
    return window.localStorage.getItem(CANVAS_CURSOR_MODE_STORAGE_KEY) === 'selection'
      ? 'selection'
      : 'pan';
  } catch {
    // Private mode / disabled storage - behave like a fresh user.
    return 'pan';
  }
}


interface UseBoxSelectionResult {
  isBoxSelectionEnabled: boolean;
  cursorMode: CanvasCursorMode;
  setCursorMode: (mode: CanvasCursorMode) => void;
  isSelecting: boolean;
  selectionStart: XYPosition | null;
  selectionEnd: XYPosition | null;
  handleSelectionChange: (selection: { nodes?: Array<{ id: string }> }) => void;
  containerRef: React.RefObject<HTMLDivElement | null>;
  selectionJustEndedRef: React.RefObject<boolean>;
}

export function useBoxSelection({
  instance,
  nodes,
  onSelectionChange,
  onForceNodesUpdate,
  onNodesChange,
  allowBoxSelection = true,
}: UseBoxSelectionProps): UseBoxSelectionResult {
  // Seeded to the SSR-safe default and hydrated from localStorage in an effect:
  // reading storage in the initializer would render a different tree on the
  // server than on the client (hydration mismatch).
  const [cursorMode, setCursorModeState] = React.useState<CanvasCursorMode>('pan');
  const isBoxSelectionEnabled = allowBoxSelection && cursorMode === 'selection';
  const [selectionStart, setSelectionStart] = React.useState<XYPosition | null>(null);
  const [selectionEnd, setSelectionEnd] = React.useState<XYPosition | null>(null);
  const [isSelecting, setIsSelecting] = React.useState(false);

  const selectionEndRef = React.useRef<XYPosition | null>(null);
  // Guard: prevents handlePaneClick from clearing selection right after box-select ends
  const selectionJustEndedRef = React.useRef(false);
  const nodesRef = React.useRef(nodes);
  const onForceNodesUpdateRef = React.useRef(onForceNodesUpdate);
  const onNodesChangeRef = React.useRef(onNodesChange);
  const onSelectionChangeRef = React.useRef(onSelectionChange);
  const containerRef = React.useRef<HTMLDivElement | null>(null);

  // Update refs when props change
  React.useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  React.useEffect(() => {
    onForceNodesUpdateRef.current = onForceNodesUpdate;
  }, [onForceNodesUpdate]);

  React.useEffect(() => {
    onNodesChangeRef.current = onNodesChange;
  }, [onNodesChange]);

  React.useEffect(() => {
    onSelectionChangeRef.current = onSelectionChange;
  }, [onSelectionChange]);

  React.useEffect(() => {
    selectionEndRef.current = selectionEnd;
  }, [selectionEnd]);

  // Restore the persisted preference once mounted (see the state seed above).
  React.useEffect(() => {
    setCursorModeState(readStoredCursorMode());
  }, []);

  const setCursorMode = React.useCallback((mode: CanvasCursorMode) => {
    setCursorModeState(mode);
    try {
      window.localStorage.setItem(CANVAS_CURSOR_MODE_STORAGE_KEY, mode);
    } catch {
      // Storage unavailable - the mode still applies for this session.
    }
    if (mode === 'pan') {
      // Leaving selection mode mid-drag would otherwise leave a ghost rectangle.
      setSelectionStart(null);
      setSelectionEnd(null);
      setIsSelecting(false);
    }
  }, []);

  const handleSelectionMove = React.useCallback(
    (event: MouseEvent) => {
      if (!isSelecting || !instance || !selectionStart) return;

      event.preventDefault();
      event.stopPropagation();
      const position = instance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });
      setSelectionEnd(position);
    },
    [isSelecting, instance, selectionStart],
  );

  const handleSelectionEnd = React.useCallback(() => {
    const currentSelectionEnd = selectionEndRef.current;
    if (!isSelecting || !instance || !selectionStart || !currentSelectionEnd) {
      setIsSelecting(false);
      setSelectionStart(null);
      setSelectionEnd(null);
      selectionEndRef.current = null;
      return;
    }

    const minX = Math.min(selectionStart.x, currentSelectionEnd.x);
    const maxX = Math.max(selectionStart.x, currentSelectionEnd.x);
    const minY = Math.min(selectionStart.y, currentSelectionEnd.y);
    const maxY = Math.max(selectionStart.y, currentSelectionEnd.y);

    const selectedNodeIds: string[] = [];
    const allNodes = nodesRef.current;
    const instanceNodes = instance.getNodes();
    const instanceNodesMap = new Map(instanceNodes.map(n => [n.id, n]));

    allNodes.forEach((node) => {
      const instanceNode = instanceNodesMap.get(node.id);
      const nodeX = instanceNode?.positionAbsolute?.x ?? node.positionAbsolute?.x ?? node.position.x;
      const nodeY = instanceNode?.positionAbsolute?.y ?? node.positionAbsolute?.y ?? node.position.y;

      const nodeAny = node as unknown as { measured?: { width?: number; height?: number }; width?: number; height?: number };
      const nodeWidth = nodeAny.measured?.width ?? nodeAny.width ?? (typeof node.style?.width === 'number' ? node.style.width : 150);
      const nodeHeight = nodeAny.measured?.height ?? nodeAny.height ?? (typeof node.style?.minHeight === 'number' ? node.style.minHeight : 40);

      const nodeLeft = nodeX;
      const nodeRight = nodeX + nodeWidth;
      const nodeTop = nodeY;
      const nodeBottom = nodeY + nodeHeight;

      const isIntersecting = !(
        nodeRight < minX ||
        nodeLeft > maxX ||
        nodeBottom < minY ||
        nodeTop > maxY
      );

      if (isIntersecting) {
        selectedNodeIds.push(node.id);
      }
    });

    // Only update selectedNodeIds - usePreparedGraph handles the visual
    // `selected` prop on nodes.  Do NOT call setNodes/onNodesChange with
    // select changes here; that creates new node objects and triggers an
    // infinite render loop (nodes change → usePreparedGraph → ReactFlow → …).
    onSelectionChangeRef.current(selectedNodeIds);

    // Prevent the subsequent click event (mouseup → click) from clearing selection
    // via handlePaneClick. The click fires ~0ms after mouseup on the same position.
    selectionJustEndedRef.current = true;
    setTimeout(() => { selectionJustEndedRef.current = false; }, 200);

    setIsSelecting(false);
    setSelectionStart(null);
    setSelectionEnd(null);
    selectionEndRef.current = null;
  }, [isSelecting, instance, selectionStart]);

  // Event listeners for selection
  React.useEffect(() => {
    if (isSelecting) {
      window.addEventListener('mousemove', handleSelectionMove);
      window.addEventListener('mouseup', handleSelectionEnd);
      return () => {
        window.removeEventListener('mousemove', handleSelectionMove);
        window.removeEventListener('mouseup', handleSelectionEnd);
      };
    }
  }, [isSelecting, handleSelectionMove, handleSelectionEnd]);

  // MouseDown handler on container for selection
  React.useEffect(() => {
    const container = containerRef.current;
    if (!container || !isBoxSelectionEnabled) return;

    const handleMouseDown = (event: MouseEvent) => {
      if (!instance) return;
      // Left button only: middle/right drag is what still PANS the canvas while
      // selection mode is armed (panOnDrag={[1, 2]}), so arming a selection here
      // would swallow the only escape hatch and clear the current selection.
      if (event.button !== 0) return;
      const target = event.target as HTMLElement;
      if (target.closest('.react-flow__node')) return;
      if (target.closest('button') || target.closest('[role="button"]')) return;
      if (target.closest('[data-node-creator-panel]')) return;
      if (target.closest('[data-inspector-panel]')) return;

      event.preventDefault();
      event.stopPropagation();

      const position = instance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });
      setSelectionStart(position);
      setSelectionEnd(position);
      setIsSelecting(true);
    };

    container.addEventListener('mousedown', handleMouseDown);
    return () => {
      container.removeEventListener('mousedown', handleMouseDown);
    };
  }, [isBoxSelectionEnabled, instance]);

  const handleSelectionChange = React.useCallback(
    (selection: { nodes?: Array<{ id: string }> }) => {
      if (isSelecting) {
        const ids = selection.nodes?.map((node) => node.id) ?? [];
        onSelectionChange(ids);
      }
    },
    [onSelectionChange, isSelecting],
  );

  return {
    isBoxSelectionEnabled,
    cursorMode,
    setCursorMode,
    isSelecting,
    selectionStart,
    selectionEnd,
    handleSelectionChange,
    containerRef,
    selectionJustEndedRef,
  };
}
