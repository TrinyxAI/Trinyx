// @vitest-environment jsdom
/**
 * What a generated asset can do FROM the Files page.
 *
 * <p>Two things, and both are wiring this page owns: looking back at what has been generated (the
 * same list the dialog shows, in the place the assets actually live), and opening the form again on
 * the recipe of one of them. The list and the dialog have their own suites; what is pinned here is
 * the plumbing between them, which is where the mistakes are - a recipe left behind so the NEXT
 * "Generate" opens on someone else's prompt, a Regenerate control offered where the dialog cannot
 * be opened, a selection left standing over a list the bulk actions cannot address.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { act, render, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const refresh = vi.fn();
const entries: StorageExplorerEntry[] = [{
  id: 'file-1',
  storageType: 'S3_FILE',
  sourceType: 'S3_FILE',
  fileName: 'plain.png',
  mimeType: 'image/png',
  sizeBytes: 10,
  formattedSize: '10 B',
  createdAt: '2026-08-24T10:00:00Z',
  workflowId: null,
  workflowName: null,
  projectId: null,
  runId: null,
  stepKey: null,
  epoch: null,
  s3Key: 'tenant/plain.png',
  contentType: 'image/png',
}];

vi.mock('@/app/workflows/builder/components/inspector/useStorageExplorer', () => ({
  useStorageExplorer: () => ({
    sort: 'date' as const,
    direction: 'desc' as const,
    setSort: vi.fn(),
    entries,
    totalElements: 1, totalPages: 1, currentPage: 0, pageSize: 50,
    loading: false, error: null, search: '', sourceTypeFilter: '',
    dateFrom: '', dateTo: '', fileType: '_all', parentFolderId: null,
    setSearch: vi.fn(), setSourceTypeFilter: vi.fn(), setDateFrom: vi.fn(),
    setDateTo: vi.fn(), setFileType: vi.fn(), navigateToFolder: vi.fn(),
    setPage: vi.fn(), setPageSize: vi.fn(), refresh,
  }),
}));

vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    getFolderTrail: vi.fn().mockResolvedValue([]), createFolder: vi.fn(),
    moveEntries: vi.fn(), deleteEntries: vi.fn(), renameEntry: vi.fn(),
  },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));
vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DragOverlay: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  PointerSensor: class {}, useSensor: () => ({}), useSensors: () => [],
}));
vi.mock('../FolderCard', () => ({ FolderCard: () => null, VirtualFolderCard: () => null }));
vi.mock('../FilesExplorerBody', () => ({
  FilesExplorerBody: ({ onOpenFile }: any) => (
    <div data-testid="file-grid">
      <button type="button" onClick={() => onOpenFile?.(entries[0])}>grid-open</button>
    </div>
  ),
}));
vi.mock('../FileCard', () => ({ FileCard: () => null }));

/** The file viewer, reporting what it was handed. */
const detailProps = vi.hoisted(() => vi.fn());
vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: (props: Record<string, unknown>) => {
    detailProps(props);
    return <div data-testid="file-detail" data-entry-id={String(props.entryId ?? '')} />;
  },
}));

const routerPush = vi.fn();
const routerReplace = vi.fn();
const searchParams = new URLSearchParams();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: routerPush, replace: routerReplace }),
  usePathname: () => '/en/app/files',
  useSearchParams: () => searchParams,
}));

vi.mock('../FileFilterBar', () => ({ FileFilterBar: () => <div data-testid="filter-bar" /> }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { downloadAndSave: vi.fn(), uploadGeneric: vi.fn() },
}));

const api = vi.hoisted(() => ({ getModels: vi.fn() }));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { getModels: api.getModels },
}));

const gate = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => gate.canMutate,
}));

/**
 * The history list, reduced to a marker that can fire either of its two callbacks. Its own suite
 * covers what it draws; this file is about what the page does with what it reports.
 */
const RECIPE = { model: 'flux-1.1-pro', kind: 'image', prompt: 'a lighthouse at dusk' };
const HISTORY_ENTRY = {
  id: 'gen-1',
  fileName: 'gen.png',
  mimeType: 'image/png',
  sizeBytes: 2048,
  formattedSize: '2.0 KB',
  createdAt: '2026-08-24T10:00:00Z',
  s3Key: 'tenant/gen.png',
  provenance: RECIPE,
};
vi.mock('@/components/generation/GenerationHistoryList', () => ({
  GenerationHistoryList: ({ onReuse, onOpen }: any) => (
    <div data-testid="generation-history">
      <button type="button" onClick={() => onReuse(HISTORY_ENTRY)}>history-reuse</button>
      <button type="button" onClick={() => onOpen(HISTORY_ENTRY)}>history-open</button>
    </div>
  ),
}));

const modalProps = vi.hoisted(() => vi.fn());
vi.mock('@/components/chat/CreateGenerationModal', () => ({
  CreateGenerationModal: (props: Record<string, unknown>) => {
    modalProps(props);
    return props.isOpen
      ? <div data-testid="generation-modal">
          <button type="button" onClick={props.onClose as () => void}>modal-close</button>
        </div>
      : null;
  },
}));

import { FileBrowser } from '../FileBrowser';

function renderBrowser() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <FileBrowser />
    </QueryClientProvider>,
  );
}

function lastModalProps(): Record<string, unknown> {
  return modalProps.mock.calls[modalProps.mock.calls.length - 1][0];
}

function lastDetailProps(): Record<string, unknown> {
  return detailProps.mock.calls[detailProps.mock.calls.length - 1][0];
}

beforeEach(() => {
  gate.canMutate = true;
  refresh.mockClear();
  modalProps.mockClear();
  detailProps.mockClear();
  api.getModels.mockReset();
  api.getModels.mockResolvedValue({
    models: [{ model: 'flux-1.1-pro', kind: 'image', label: 'FLUX 1.1 Pro' }],
    count: 1,
    kinds: ['image'],
  });
});
afterEach(() => cleanup());

describe('FileBrowser - the generated assets', () => {
  it('offers the history beside the control that generates', async () => {
    renderBrowser();

    expect(await screen.findByText('generatedAssets')).toBeDefined();
  });

  it('offers nothing to look back at where this install does not serve generation', async () => {
    // 404 means the feature is absent: there is no history to browse, so the way in is not drawn.
    const { ApiError } = await import('@/lib/api/api-client');
    api.getModels.mockRejectedValue(new ApiError('not served', 404));

    renderBrowser();

    await waitFor(() => expect(screen.queryByText('generatedAssets')).toBeNull());
  });

  it('swaps the file grid for the history, and back', async () => {
    renderBrowser();

    fireEvent.click(await screen.findByText('generatedAssets'));

    expect(screen.getByTestId('generation-history')).toBeDefined();
    // The grid and its filters belong to the files, not to the history: leaving them up would
    // offer a search that narrows something no longer on screen.
    expect(screen.queryByTestId('file-grid')).toBeNull();
    expect(screen.queryByTestId('filter-bar')).toBeNull();

    fireEvent.click(screen.getByText('generatedAssets'));
    expect(screen.queryByTestId('generation-history')).toBeNull();
    expect(screen.getByTestId('file-grid')).toBeDefined();
  });

  it('opens the dialog on a reused recipe, and forgets it once the dialog closes', async () => {
    // The forgetting is the half that bites: kept, the next plain "Generate" would open on the
    // last asset's prompt, and the reader would generate a variant of something they did not pick.
    renderBrowser();

    fireEvent.click(await screen.findByText('generatedAssets'));
    fireEvent.click(screen.getByText('history-reuse'));

    // The dialog is lazy: it arrives one tick after the click, which is the whole point of
    // warming it on hover.
    await screen.findByTestId('generation-modal');
    expect(lastModalProps().initialRecipe).toEqual(RECIPE);

    fireEvent.click(screen.getByText('modal-close'));
    fireEvent.click(screen.getByText('generate'));

    expect(lastModalProps().initialRecipe).toBeNull();
  });

  it('opens a generated asset in the one file viewer, addressed by its id', async () => {
    renderBrowser();

    fireEvent.click(await screen.findByText('generatedAssets'));
    fireEvent.click(screen.getByText('history-open'));

    expect(screen.getByTestId('file-detail').getAttribute('data-entry-id')).toBe('gen-1');
  });

  it('lets the viewer offer Regenerate, and routes it to the dialog', async () => {
    renderBrowser();

    fireEvent.click(await screen.findByText('generatedAssets'));
    fireEvent.click(screen.getByText('history-open'));

    const onRegenerate = lastDetailProps().onRegenerate as (r: unknown) => void;
    expect(onRegenerate).toBeTypeOf('function');
    act(() => onRegenerate(RECIPE));

    await screen.findByTestId('generation-modal');
    expect(lastModalProps().initialRecipe).toEqual(RECIPE);
  });

  it('offers a read-only member no history, and asks the catalogue nothing on their behalf', async () => {
    // A VIEWER cannot start a generation, so there is no way in and no reason to spend a request
    // finding out what the catalogue holds - the same rule the Generate control already applies.
    gate.canMutate = false;
    renderBrowser();

    await waitFor(() => expect(screen.getByTestId('file-grid')).toBeDefined());
    expect(screen.queryByText('generatedAssets')).toBeNull();
    expect(api.getModels).not.toHaveBeenCalled();
  });

  it('offers no Regenerate control on a file opened by a read-only member', async () => {
    // Passing the callback anyway would have the viewer ask for a recipe on every file it opens,
    // for a button that could not lead anywhere.
    gate.canMutate = false;
    renderBrowser();

    fireEvent.click(screen.getByText('grid-open'));

    await screen.findByTestId('file-detail');
    expect(lastDetailProps().onRegenerate).toBeUndefined();
  });

  it('does offer it on a file opened by a member who can generate', async () => {
    // The other half: without this, "no button" would pass for a page that never offers one.
    renderBrowser();
    await screen.findByText('generatedAssets');

    fireEvent.click(screen.getByText('grid-open'));

    await screen.findByTestId('file-detail');
    expect(lastDetailProps().onRegenerate).toBeTypeOf('function');
  });
});
