// @vitest-environment jsdom
/**
 * How a list changes the folder level it is showing.
 *
 * The defect this exists for: it used to be a router push. From a page loaded DIRECTLY on
 * `?folder=<id>` - a shared link, a reload, the browser restoring a tab - Next drops a push
 * that only REMOVES the query, which is exactly what leaving a folder asks for. Every way out
 * did nothing at all and the list looked frozen inside a folder the user had left.
 *
 * The router is mocked here in full, so a regression to `router.push` would still run: these
 * tests fail because the address does not change, not because something is undefined.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';

const routerCalls = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
const url = vi.hoisted(() => ({ pathname: '/en/app/workflow', search: '' }));

vi.mock('@dnd-kit/core', () => ({
  MouseSensor: class {},
  TouchSensor: class {},
  useSensor: () => ({}),
  useSensors: () => [],
}));

vi.mock('next/navigation', () => ({
  usePathname: () => url.pathname,
  useSearchParams: () => new URLSearchParams(url.search),
  useRouter: () => ({
    push: routerCalls.push,
    replace: routerCalls.replace,
    back: vi.fn(),
    forward: vi.fn(),
    refresh: vi.fn(),
    prefetch: vi.fn(),
  }),
}));

const assignToFolder = vi.hoisted(() => vi.fn());
vi.mock('@/hooks/useResourceFolderActions', () => ({
  useResourceFolderActions: () => ({
    allFolders: [], loadingFolders: false, busy: false,
    loadAllFolders: vi.fn(), createFolder: vi.fn(), renameFolder: vi.fn(),
    deleteFolder: vi.fn(), moveFolder: vi.fn(), assignToFolder,
  }),
}));

import { useListFolders } from '../useListFolders';

const labels = {
  actionFailed: '', createFailed: '', renameFailed: '', deleteFailed: '', moveFailed: '',
  moved: '', movedToFolder: () => '', movedToTopLevel: () => '',
};

const pushState = vi.fn();
const replaceState = vi.fn();
const originalPush = window.history.pushState;
const originalReplace = window.history.replaceState;

function setup(clearSelection = vi.fn()) {
  return renderHook(() => useListFolders({
    kind: 'workflow',
    reload: vi.fn(),
    selectedIds: new Set<string>(),
    clearSelection,
    searching: false,
    canMutate: true,
    labels,
    notify: vi.fn(),
  }));
}

beforeEach(() => {
  url.pathname = '/en/app/workflow';
  url.search = '';
  pushState.mockClear();
  replaceState.mockClear();
  routerCalls.push.mockClear();
  routerCalls.replace.mockClear();
  window.history.pushState = pushState as unknown as typeof window.history.pushState;
  window.history.replaceState = replaceState as unknown as typeof window.history.replaceState;
});
afterEach(() => {
  window.history.pushState = originalPush;
  window.history.replaceState = originalReplace;
  cleanup();
});

describe('useListFolders - going to a level', () => {
  it('opens a folder by putting it in the address', () => {
    const { result } = setup();

    act(() => result.current.navigateToFolder('f1'));

    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/workflow?folder=f1');
  });

  it('leaves a folder the page was loaded into, which a router push cannot do', () => {
    url.search = 'folder=f1';
    const { result } = setup();

    act(() => result.current.navigateToFolder(null));

    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/workflow');
    // The router is fully mocked and available: a regression to `router.push` would run
    // happily and silently leave the address alone. That is the bug.
    expect(routerCalls.push).not.toHaveBeenCalled();
  });

  it('leaves each level as a history STEP, so Back walks back out one folder at a time', () => {
    url.search = 'folder=f1';
    const { result } = setup();

    act(() => result.current.navigateToFolder('f2'));

    expect(pushState).toHaveBeenCalled();
    expect(replaceState).not.toHaveBeenCalled();
  });

  it('ignores the level already shown, so a crumb cannot stack a step that costs an extra Back', () => {
    url.search = 'folder=f1';
    const { result } = setup();

    act(() => result.current.navigateToFolder('f1'));

    expect(pushState).not.toHaveBeenCalled();
  });

  it('drops the selection when the level changes, so a filing cannot carry rows you can no longer see', () => {
    const clearSelection = vi.fn();
    const { result } = setup(clearSelection);

    act(() => result.current.navigateToFolder('f1'));

    expect(clearSelection).toHaveBeenCalled();
  });

  it('keeps every other parameter the page carries', () => {
    url.search = 'view=skills&folder=f1';
    const { result } = setup();

    act(() => result.current.navigateToFolder(null));

    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/workflow?view=skills');
  });
});

describe('useListFolders - a folder that is gone', () => {
  it('corrects the address in place, so Back cannot return to a dead folder', () => {
    url.search = 'folder=gone';
    const { result } = setup();

    act(() => result.current.applyListResponse({ folders: [], folderTrail: [], folderMissing: true }));

    expect(replaceState).toHaveBeenCalledWith(null, '', '/en/app/workflow');
    expect(pushState).not.toHaveBeenCalled();
  });

  it('leaves the address alone when the folder is fine', () => {
    url.search = 'folder=f1';
    const { result } = setup();

    act(() => result.current.applyListResponse({
      folders: [], folderTrail: [{ id: 'f1', name: 'Marketing', parentFolderId: null }] as never,
    }));

    expect(replaceState).not.toHaveBeenCalled();
    expect(pushState).not.toHaveBeenCalled();
  });
});

describe('useListFolders - opening a tile', () => {
  it('is ignored while the list is fetching, because the tiles on screen are the previous level', () => {
    const { result } = renderHook(() => useListFolders({
      kind: 'workflow', reload: vi.fn(), selectedIds: new Set<string>(), clearSelection: vi.fn(),
      searching: false, busy: true, canMutate: true, labels, notify: vi.fn(),
    }));

    act(() => result.current.openFolder({ id: 'f1' } as never));

    expect(pushState).not.toHaveBeenCalled();
  });
});
