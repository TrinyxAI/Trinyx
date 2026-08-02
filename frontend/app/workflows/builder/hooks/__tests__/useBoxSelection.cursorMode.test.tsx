// @vitest-environment jsdom
/**
 * The canvas cursor mode (hand vs selection box) is a USER preference, shared by
 * every workflow, so it lives in localStorage rather than per-graph state. These
 * tests pin the default, the hydration, the write-through, the derived
 * box-selection flag the canvas reads, and the gate that keeps a persisted
 * selection mode out of surfaces that offer no control to leave it.
 */
import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act, render, fireEvent } from '@testing-library/react';
import type { ReactFlowInstance } from 'reactflow';

import {
  useBoxSelection,
  readStoredCursorMode,
  CANVAS_CURSOR_MODE_STORAGE_KEY,
} from '../useBoxSelection';

const renderBoxSelection = (allowBoxSelection = true) =>
  renderHook(() =>
    useBoxSelection({
      instance: null,
      nodes: [],
      onSelectionChange: vi.fn(),
      onNodesChange: vi.fn(),
      allowBoxSelection,
    }),
  );

beforeEach(() => {
  window.localStorage.clear();
});

describe('useBoxSelection - persisted cursor mode', () => {
  it('defaults to pan (hand) when nothing was ever stored', () => {
    const { result } = renderBoxSelection();
    expect(result.current.cursorMode).toBe('pan');
    expect(result.current.isBoxSelectionEnabled).toBe(false);
  });

  it('hydrates the stored selection mode on mount', () => {
    window.localStorage.setItem(CANVAS_CURSOR_MODE_STORAGE_KEY, 'selection');
    const { result } = renderBoxSelection();
    expect(result.current.cursorMode).toBe('selection');
    expect(result.current.isBoxSelectionEnabled).toBe(true);
  });

  it('persists the mode so every other workflow opens with it', () => {
    const { result } = renderBoxSelection();
    act(() => result.current.setCursorMode('selection'));

    expect(result.current.cursorMode).toBe('selection');
    expect(window.localStorage.getItem(CANVAS_CURSOR_MODE_STORAGE_KEY)).toBe('selection');

    // A canvas mounted afterwards (another workflow) picks the same mode up.
    const second = renderBoxSelection();
    expect(second.result.current.cursorMode).toBe('selection');
  });

  it('persists the switch back to pan instead of only clearing the flag', () => {
    window.localStorage.setItem(CANVAS_CURSOR_MODE_STORAGE_KEY, 'selection');
    const { result } = renderBoxSelection();
    act(() => result.current.setCursorMode('pan'));

    expect(result.current.isBoxSelectionEnabled).toBe(false);
    expect(window.localStorage.getItem(CANVAS_CURSOR_MODE_STORAGE_KEY)).toBe('pan');
  });

  it('treats an unknown stored value as the pan default', () => {
    window.localStorage.setItem(CANVAS_CURSOR_MODE_STORAGE_KEY, 'lasso-9000');
    expect(readStoredCursorMode()).toBe('pan');
    const { result } = renderBoxSelection();
    expect(result.current.cursorMode).toBe('pan');
  });

  it('falls back to pan when localStorage reads throw (private mode)', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    try {
      expect(readStoredCursorMode()).toBe('pan');
    } finally {
      spy.mockRestore();
    }
  });

  it('keeps the chosen mode for the session when localStorage writes throw', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded');
    });
    try {
      const { result } = renderBoxSelection();
      act(() => result.current.setCursorMode('selection'));
      expect(result.current.cursorMode).toBe('selection');
      expect(result.current.isBoxSelectionEnabled).toBe(true);
    } finally {
      spy.mockRestore();
    }
  });
});

describe('useBoxSelection - surfaces without a cursor-mode control', () => {
  it('ignores a persisted selection mode when box selection is not allowed', () => {
    // Run mode / the read-only preview render no cursor select, so honouring the
    // stored mode would kill left-drag panning with no way to restore it.
    window.localStorage.setItem(CANVAS_CURSOR_MODE_STORAGE_KEY, 'selection');
    const { result } = renderBoxSelection(false);

    expect(result.current.cursorMode).toBe('selection');
    expect(result.current.isBoxSelectionEnabled).toBe(false);
  });

  it('keeps the stored preference intact for the editor to pick back up', () => {
    window.localStorage.setItem(CANVAS_CURSOR_MODE_STORAGE_KEY, 'selection');
    renderBoxSelection(false);

    expect(window.localStorage.getItem(CANVAS_CURSOR_MODE_STORAGE_KEY)).toBe('selection');
    expect(renderBoxSelection(true).result.current.isBoxSelectionEnabled).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Switching mode must also abort a drag already in flight. Exercised against a
// REAL mousedown on the container: the clearing branch is unreachable from a
// hook rendered without a mounted canvas element.
// ---------------------------------------------------------------------------
describe('useBoxSelection - switching mode mid-drag', () => {
  const fakeInstance = {
    screenToFlowPosition: ({ x, y }: { x: number; y: number }) => ({ x, y }),
    getNodes: () => [],
  } as unknown as ReactFlowInstance;

  type Box = ReturnType<typeof useBoxSelection>;

  const renderCanvas = () => {
    const box: { current: Box | null } = { current: null };
    function Harness() {
      const value = useBoxSelection({
        instance: fakeInstance,
        nodes: [],
        onSelectionChange: vi.fn(),
        onNodesChange: vi.fn(),
      });
      box.current = value;
      return <div data-testid="canvas" ref={value.containerRef} />;
    }
    const utils = render(<Harness />);
    return { box, ...utils };
  };

  it('drops an in-progress selection rectangle when switching back to the hand', () => {
    window.localStorage.setItem(CANVAS_CURSOR_MODE_STORAGE_KEY, 'selection');
    const { box, getByTestId } = renderCanvas();
    expect(box.current?.isBoxSelectionEnabled).toBe(true);

    // Start a drag on the canvas background.
    act(() => {
      fireEvent.mouseDown(getByTestId('canvas'), { clientX: 10, clientY: 20 });
    });
    expect(box.current?.isSelecting).toBe(true);
    expect(box.current?.selectionStart).toEqual({ x: 10, y: 20 });

    // Switching to the hand mid-drag must abort it, not leave a ghost rectangle.
    act(() => box.current!.setCursorMode('pan'));
    expect(box.current?.isSelecting).toBe(false);
    expect(box.current?.selectionStart).toBeNull();
    expect(box.current?.selectionEnd).toBeNull();
  });

  it('does not arm a selection drag while in hand mode', () => {
    const { box, getByTestId } = renderCanvas();
    act(() => {
      fireEvent.mouseDown(getByTestId('canvas'), { clientX: 10, clientY: 20 });
    });
    expect(box.current?.isSelecting).toBe(false);
    expect(box.current?.selectionStart).toBeNull();
  });
});
