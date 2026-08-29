// @vitest-environment jsdom
/**
 * How a list's drag behaves before anything is dropped: what the floating preview says, and
 * what it takes to start a drag at all.
 *
 * Both are defects the folder lists shipped with. Dragging a FOLDER set no preview, so nesting
 * one dragged nothing visible. And with a single pointer sensor asking a finger to travel six
 * pixels, the browser claimed the gesture as a scroll first and fired `pointercancel` - which
 * cancels the drag - so a card could not be dragged with a finger at all.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';
import type { DragEndEvent, DragStartEvent } from '@dnd-kit/core';

// What it TAKES to start a drag lives in `useDragSensors`, and is pinned there.
vi.mock('@/lib/dnd/useDragSensors', () => ({ useDragSensors: () => [] }));

vi.mock('next/navigation', () => ({
  usePathname: () => '/en/app/workflow',
  useSearchParams: () => new URLSearchParams(),
}));

const actions = vi.hoisted(() => ({
  assignToFolder: vi.fn(),
  moveFolder: vi.fn(),
}));

vi.mock('@/hooks/useResourceFolderActions', () => ({
  useResourceFolderActions: () => ({
    allFolders: [],
    loadingFolders: false,
    busy: false,
    loadAllFolders: vi.fn(),
    createFolder: vi.fn(),
    renameFolder: vi.fn(),
    deleteFolder: vi.fn(),
    moveFolder: actions.moveFolder,
    assignToFolder: actions.assignToFolder,
  }),
}));

import { useListFolders } from '../useListFolders';

const labels = {
  actionFailed: '', createFailed: '', renameFailed: '', deleteFailed: '', moveFailed: '',
  moved: '', movedToFolder: () => '', movedToTopLevel: () => '',
};

function setup(selected: Set<string> = new Set()) {
  return renderHook(() => useListFolders({
    kind: 'workflow',
    reload: vi.fn(),
    selectedIds: selected,
    clearSelection: vi.fn(),
    searching: false,
    canMutate: true,
    labels,
    notify: vi.fn(),
  }));
}

const dragStart = (data: Record<string, unknown>) =>
  ({ active: { data: { current: data } } }) as unknown as DragStartEvent;

const dragEnd = (active: Record<string, unknown>, over: Record<string, unknown> | null) =>
  ({
    active: { data: { current: active } },
    over: over ? { data: { current: over } } : null,
  }) as unknown as DragEndEvent;

const onFolder = (folderId: string | null) => ({ type: 'folder', folderId });

beforeEach(() => {
  actions.assignToFolder.mockReset().mockResolvedValue(1);
  actions.moveFolder.mockReset().mockResolvedValue({});
});
afterEach(() => cleanup());

describe('useListFolders - starting a drag', () => {
  it('names a dragged folder from the tile itself', () => {
    const { result } = setup();

    act(() => result.current.handleDragStart(dragStart({ type: 'folder', folderId: 'f1', name: 'Q4' }), () => undefined));

    expect(result.current.activeDrag).toEqual({ label: 'Q4', count: 1 });
  });

  it('names a dragged card through the list that owns the rows', () => {
    const { result } = setup();

    act(() => result.current.handleDragStart(
      dragStart({ type: 'resource', resourceId: 'w1' }), (id) => (id === 'w1' ? 'Weekly digest' : undefined)));

    expect(result.current.activeDrag).toEqual({ label: 'Weekly digest', count: 1 });
  });

  it('carries the whole selection when the drag starts on a selected card', () => {
    const { result } = setup(new Set(['w1', 'w2', 'w3']));

    act(() => result.current.handleDragStart(dragStart({ type: 'resource', resourceId: 'w1' }), () => 'Weekly digest'));

    expect(result.current.activeDrag).toEqual({ label: 'Weekly digest', count: 3 });
  });

  it('carries one card when the drag starts beside the selection', () => {
    const { result } = setup(new Set(['w2', 'w3']));

    act(() => result.current.handleDragStart(dragStart({ type: 'resource', resourceId: 'w1' }), () => 'Weekly digest'));

    expect(result.current.activeDrag).toEqual({ label: 'Weekly digest', count: 1 });
  });

  it('shows nothing for a drag of something it does not recognise', () => {
    const { result } = setup();

    act(() => result.current.handleDragStart(dragStart({ type: 'something-else' }), () => 'x'));

    expect(result.current.activeDrag).toBeNull();
  });
});

/**
 * Dropping. The list decides what a release means from what was picked up and what it landed
 * on, and one of those targets is now a crumb of the folder path - which is how a card gets
 * back OUT of a folder, the one move the path is there to offer.
 */
describe('useListFolders - finishing a drag', () => {
  it('files the dragged card into the folder it was dropped on', async () => {
    const { result } = setup();

    await act(async () => {
      await result.current.handleDragEnd(
        dragEnd({ type: 'resource', resourceId: 'w1' }, onFolder('f1')));
    });

    expect(actions.assignToFolder).toHaveBeenCalledWith('f1', ['w1'], expect.anything());
  });

  it('files the whole selection when the card dropped was one of the selected', async () => {
    const { result } = setup(new Set(['w1', 'w2']));

    await act(async () => {
      await result.current.handleDragEnd(
        dragEnd({ type: 'resource', resourceId: 'w1' }, onFolder('f1')));
    });

    expect(actions.assignToFolder).toHaveBeenCalledWith('f1', ['w1', 'w2'], expect.anything());
  });

  it('takes a card back OUT of the folder when it is dropped on the top-level crumb', async () => {
    // The crumb's droppable carries a null folder id. This was a dead target until the drag
    // context started covering the page header.
    const { result } = setup();

    await act(async () => {
      await result.current.handleDragEnd(
        dragEnd({ type: 'resource', resourceId: 'w1' }, onFolder(null)));
    });

    expect(actions.assignToFolder).toHaveBeenCalledWith(null, ['w1'], expect.anything());
  });

  it('nests a folder dropped on another folder', async () => {
    const { result } = setup();

    await act(async () => {
      await result.current.handleDragEnd(
        dragEnd({ type: 'folder', folderId: 'f1' }, onFolder('f2')));
    });

    expect(actions.moveFolder).toHaveBeenCalledWith('f1', 'f2', expect.anything());
  });

  it('un-nests a folder dropped on the top-level crumb', async () => {
    const { result } = setup();

    await act(async () => {
      await result.current.handleDragEnd(
        dragEnd({ type: 'folder', folderId: 'f1' }, onFolder(null)));
    });

    expect(actions.moveFolder).toHaveBeenCalledWith('f1', null, expect.anything());
  });

  it('refuses to nest a folder inside itself', async () => {
    const { result } = setup();

    await act(async () => {
      await result.current.handleDragEnd(
        dragEnd({ type: 'folder', folderId: 'f1' }, onFolder('f1')));
    });

    expect(actions.moveFolder).not.toHaveBeenCalled();
  });

  it('does nothing when the release lands on no target at all', async () => {
    const { result } = setup();

    await act(async () => {
      await result.current.handleDragEnd(dragEnd({ type: 'resource', resourceId: 'w1' }, null));
    });

    expect(actions.assignToFolder).not.toHaveBeenCalled();
  });

  it('does nothing when the release lands on something that is not a folder', async () => {
    const { result } = setup();

    await act(async () => {
      await result.current.handleDragEnd(
        dragEnd({ type: 'resource', resourceId: 'w1' }, { type: 'something-else' }));
    });

    expect(actions.assignToFolder).not.toHaveBeenCalled();
  });

  it('clears the floating preview however the drag ended', async () => {
    const { result } = setup();
    act(() => result.current.handleDragStart(dragStart({ type: 'resource', resourceId: 'w1' }), () => 'x'));
    expect(result.current.activeDrag).not.toBeNull();

    await act(async () => {
      await result.current.handleDragEnd(dragEnd({ type: 'resource', resourceId: 'w1' }, null));
    });

    expect(result.current.activeDrag).toBeNull();
  });
});
