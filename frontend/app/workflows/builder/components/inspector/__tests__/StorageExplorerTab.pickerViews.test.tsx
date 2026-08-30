// @vitest-environment jsdom
/**
 * The picker's density and its browse-vs-search scope.
 *
 * Both were shipped once without a test and both were wrong in a way the existing suite could
 * not see, so the assertions here are written against the REAL caller shapes rather than
 * against convenient ones:
 *
 *  - a thumbnail column's picker opens with `initialFileType="images"`, which used to filter the
 *    folder rows away because a folder has no mime type;
 *  - the inspector's file field is a ~250px column, so the tile grid must not be its default;
 *  - the folder-aware root lists only loose, non-workflow files, so a SEARCH there would no
 *    longer reach the workflow output a media cell usually wants.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, act } from '@testing-library/react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars && 'count' in vars ? `${key}:${vars.count}` : key,
}));

const setSearch = vi.fn();
let lastHookOptions: Record<string, unknown> | undefined;
let hookState: { entries: StorageExplorerEntry[]; parentFolderId: string | null | undefined } = {
  entries: [],
  parentFolderId: null,
};
vi.mock('@/app/workflows/builder/components/inspector/useStorageExplorer', () => ({
  useStorageExplorer: (
    _workflowId?: string,
    _a?: unknown,
    _b?: unknown,
    options?: Record<string, unknown>,
  ) => {
    lastHookOptions = options;
    return {
      entries: hookState.entries,
      totalElements: hookState.entries.length,
      totalPages: 1,
      currentPage: 0,
      pageSize: 20,
      loading: false,
      error: null,
      search: '',
      setSearch,
      setPage: vi.fn(),
      refresh: vi.fn(),
      // Mirrors the real hook, which returns undefined unless it is folder-aware. Modelling it
      // keeps the breadcrumb's second guard reachable instead of always-true in tests.
      parentFolderId: options?.folderAware ? hookState.parentFolderId : undefined,
      navigateToFolder: vi.fn(),
    };
  },
}));

// Fully stubbed, like the sibling folders test: importing the real storage-api drags next-intl's
// navigation entry point in, which does not resolve under vitest.
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    deleteEntries: vi.fn().mockResolvedValue({ deletedCount: 0 }),
    getEntryPreview: vi.fn().mockResolvedValue(null),
  },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: () => ({ url: null, error: false }) }));
vi.mock('@/lib/stores/current-org-store', () => ({ getActiveOrgHeaderForRequest: () => ({}) }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { downloadAndSave: vi.fn() },
  getFileUrlById: () => 'url',
}));
vi.mock('@/lib/utils/url-auth', () => ({ openAuthedFileInNewTab: vi.fn() }));
vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: () => <div data-testid="file-detail" />,
}));

import { StorageExplorerTab } from '../StorageExplorerTab';
import { FILES_PICKER_VIEW_MODE_STORAGE_KEY } from '@/lib/files/filesViewPreferences';

function entry(id: string, isFolder: boolean, mimeType: string | null, fileName = id): StorageExplorerEntry {
  return {
    id, storageType: 'S3_FILE', sourceType: 'S3_FILE', fileName, mimeType, sizeBytes: 1,
    formattedSize: '1 B', createdAt: '2026-03-19T10:00:00Z', workflowId: null, workflowName: null,
    projectId: null, runId: null, stepKey: null, epoch: null, s3Key: `k/${id}`, contentType: mimeType,
    isFolder, childCount: isFolder ? 2 : null,
  } as StorageExplorerEntry;
}

beforeEach(() => {
  lastHookOptions = undefined;
  window.localStorage.clear();
  hookState = { entries: [], parentFolderId: null };
  vi.clearAllMocks();
});
afterEach(() => {
  cleanup();
  // Restored unconditionally: leaked fake timers turn one real failure into a file of noise.
  vi.useRealTimers();
});

describe('the file-type filter never hides folders', () => {
  it('shows folder rows even when the caller seeds an images-only filter', () => {
    // The exact shape a thumbnail column's picker opens in. A folder has mimeType null, so the
    // mime predicate rejected every one of them and the folder feature was inert right here.
    hookState.entries = [entry('f1', true, null, 'Reports'), entry('a', false, 'image/png', 'a.png')];

    const { getByText } = render(
      <StorageExplorerTab onSelect={vi.fn()} initialFileType="images" />,
    );

    expect(getByText('Reports')).toBeTruthy();
    expect(getByText('a.png')).toBeTruthy();
  });

  it('still filters FILES by the seeded type', () => {
    hookState.entries = [entry('f1', true, null, 'Reports'), entry('d', false, 'application/pdf', 'doc.pdf')];

    const { getByText, queryByText } = render(
      <StorageExplorerTab onSelect={vi.fn()} initialFileType="images" />,
    );

    expect(getByText('Reports')).toBeTruthy();
    expect(queryByText('doc.pdf')).toBeNull();
  });
});

describe('browsing is folder-scoped, searching is not', () => {
  it('opens folder-aware', () => {
    render(<StorageExplorerTab onSelect={vi.fn()} />);

    expect(lastHookOptions?.folderAware).toBe(true);
    expect(lastHookOptions?.virtualWorkflowFolders).toBe(true);
  });

  it('drops to the flat listing while a search is active, so the search spans the workspace', () => {
    // The folder-aware root lists only files with no parent folder AND no workflow. Searching
    // there would silently stop finding workflow output, which is the commonest thing a media
    // cell points at.
    vi.useFakeTimers();
    const { container } = render(<StorageExplorerTab onSelect={vi.fn()} />);
    const input = container.querySelector('input[type="text"], input:not([type])') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'invoice' } });
    act(() => { vi.advanceTimersByTime(350); });

    expect(setSearch).toHaveBeenCalledWith('invoice');
    expect(lastHookOptions?.folderAware).toBe(false);
  });

  it('and the picker drops its breadcrumb with the scope, which is what a user actually sees', () => {
    // The symmetric half of the explorer assertion below: the hook option is the mechanism, the
    // breadcrumb is the consequence someone would report.
    hookState.parentFolderId = 'folder-1';
    vi.useFakeTimers();
    const { container, queryByLabelText, getByLabelText } = render(<StorageExplorerTab onSelect={vi.fn()} />);
    expect(getByLabelText('back')).toBeTruthy();
    const input = container.querySelector('input[type="text"], input:not([type])') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'invoice' } });
    act(() => { vi.advanceTimersByTime(350); });

    expect(queryByLabelText('back')).toBeNull();
  });

  it('returns to folder-aware when the search is cleared', () => {
    vi.useFakeTimers();
    const { container } = render(<StorageExplorerTab onSelect={vi.fn()} />);
    const input = container.querySelector('input[type="text"], input:not([type])') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'invoice' } });
    act(() => { vi.advanceTimersByTime(350); });
    fireEvent.change(input, { target: { value: '' } });
    act(() => { vi.advanceTimersByTime(350); });

    expect(lastHookOptions?.folderAware).toBe(true);
  });
});

describe('density is the caller\'s choice, and the explorer keeps its rows', () => {
  it('defaults to the dense list, which is what a narrow container needs', () => {
    hookState.entries = [entry('a', false, 'image/png', 'a.png')];

    const { container } = render(<StorageExplorerTab onSelect={vi.fn()} />);

    // The compact row layout, not the tile grid.
    expect(container.querySelector('.grid-cols-2')).toBeNull();
  });

  it('opens in tiles when the caller asks for them', () => {
    hookState.entries = [entry('a', false, 'image/png', 'a.png')];

    const { container } = render(<StorageExplorerTab onSelect={vi.fn()} defaultView="grid" />);

    expect(container.querySelector('.grid-cols-2')).not.toBeNull();
  });

  it('remembers the user\'s switch under the picker\'s OWN key', () => {
    hookState.entries = [entry('a', false, 'image/png', 'a.png')];
    const { getByTitle } = render(
      <StorageExplorerTab onSelect={vi.fn()} defaultView="grid" viewScope="dialog" />,
    );

    fireEvent.click(getByTitle('viewList'));

    // A separate key from the Files page: choosing a dense picker must not flatten the browser.
    // usePersistentState writes a string RAW, so the stored value is `list`, not `"list"`.
    // Asserting the exact value is what pins the round trip through normalizeViewMode.
    expect(window.localStorage.getItem(`${FILES_PICKER_VIEW_MODE_STORAGE_KEY}.dialog`)).toBe('list');
    expect(window.localStorage.getItem('files.viewMode')).toBeNull();
  });

  it('offers no density toggle in explorer mode', () => {
    const { queryByTitle } = render(<StorageExplorerTab />);

    expect(queryByTitle('viewGrid')).toBeNull();
    expect(queryByTitle('viewList')).toBeNull();
  });

  it('keeps the explorer on rows even when the picker preference says grid', () => {
    // The side panel is narrow; a stored picker preference must not reach it.
    window.localStorage.setItem(`${FILES_PICKER_VIEW_MODE_STORAGE_KEY}.field`, 'grid');
    hookState.entries = [entry('a', false, 'image/png', 'a.png')];

    const { container } = render(<StorageExplorerTab />);

    expect(container.querySelector('.grid-cols-2')).toBeNull();
  });
});

describe('the explorer keeps its folder context when searching', () => {
  it('stays folder-aware while a search is active', () => {
    // The picker flattens on search; the EXPLORER must not. It is a browser - a search there
    // filters the folder you are standing in - and it has to keep agreeing with the full-page
    // Files browser, which is hard-coded folder-aware and shares this very body.
    vi.useFakeTimers();
    const { container } = render(<StorageExplorerTab />);
    const input = container.querySelector('input[type="text"], input:not([type])') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'invoice' } });
    act(() => { vi.advanceTimersByTime(350); });

    expect(setSearch).toHaveBeenCalledWith('invoice');
    expect(lastHookOptions?.folderAware).toBe(true);
  });

  it('keeps showing the breadcrumb while searching inside a folder', () => {
    hookState.parentFolderId = 'folder-1';
    vi.useFakeTimers();
    const { container, getByLabelText } = render(<StorageExplorerTab />);
    const input = container.querySelector('input[type="text"], input:not([type])') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'invoice' } });
    act(() => { vi.advanceTimersByTime(350); });

    // The back-up-one chevron is the breadcrumb's anchor; losing it means the context vanished.
    expect(getByLabelText('back')).toBeTruthy();
  });
});

describe('the two pickers remember their density separately', () => {
  it('a choice made in the wide dialog does not reach the narrow field', () => {
    // One shared key meant a single click in the modal re-imposed a five-across tile grid on a
    // ~250px inspector column, for a user who never opened the modal's picker at all.
    hookState.entries = [entry('a', false, 'image/png', 'a.png')];
    window.localStorage.setItem(`${FILES_PICKER_VIEW_MODE_STORAGE_KEY}.dialog`, 'grid');

    const { container } = render(
      <StorageExplorerTab onSelect={vi.fn()} viewScope="field" defaultView="list" />,
    );

    expect(container.querySelector('.grid-cols-2')).toBeNull();
  });

  it('and each honours its own stored choice', () => {
    hookState.entries = [entry('a', false, 'image/png', 'a.png')];
    window.localStorage.setItem(`${FILES_PICKER_VIEW_MODE_STORAGE_KEY}.dialog`, 'grid');

    const { container } = render(
      <StorageExplorerTab onSelect={vi.fn()} viewScope="dialog" defaultView="list" />,
    );

    expect(container.querySelector('.grid-cols-2')).not.toBeNull();
  });
});
