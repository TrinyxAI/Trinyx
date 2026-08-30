// @vitest-environment jsdom
/**
 * Folders on the Workflows list. What is pinned here is the wiring the user actually feels:
 * the page asks the server for ONE level (`folderId`), opening a tile navigates into it,
 * the trail appears once you are inside, and a search steps out of the folders entirely so
 * a workflow is findable wherever it was filed.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

const mocks = vi.hoisted(() => ({
  getWorkflowsPage: vi.fn(),
  // Relations are secondary card data, resolved for the whole page in one call.
  // Stubbed empty here: no card in these fixtures calls a sub-workflow.
  getWorkflowRelationsBatch: vi.fn().mockResolvedValue({}),
  listFolders: vi.fn(),
  createFolder: vi.fn(),
  removeFolder: vi.fn(),
  assign: vi.fn(),
  selected: new Set<string>(),
}));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
// The open folder lives in the address, so the test needs an address that really changes:
// this fake router re-renders on navigation, the way the real one does.
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflowsPage: mocks.getWorkflowsPage,
    cloneWorkflow: vi.fn(),
    deleteWorkflow: vi.fn(),
    saveWorkflowPlan: vi.fn(),
  },
}));
vi.mock('@/lib/api/orchestrator/resource-folder.service', () => ({
  resourceFolderService: {
    list: mocks.listFolders,
    create: mocks.createFolder,
    rename: vi.fn(),
    move: vi.fn(),
    remove: mocks.removeFolder,
    assign: mocks.assign,
  },
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/chat/CreateWorkflowModal', () => ({ CreateWorkflowModal: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({
  EmptyState: ({ title }: { title?: string }) => <div data-testid="empty-state">{title}</div>,
}));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/components/templates/TemplateGallery', () => ({ TemplateGallery: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrg: () => ({ currentOrgId: null }),
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({
    selectedIds: mocks.selected,
    toggle: vi.fn(),
    clear: vi.fn(),
    selectAll: vi.fn(),
  }),
}));
// dnd-kit needs a real DOM drag to do anything; render its context as a passthrough so the
// tiles and cards mount, and cover the filing itself through the bulk "Move to folder" path.
vi.mock('@dnd-kit/core', () => ({
  // A real element, not a fragment: what the drag context CONTAINS is the point (the folder
  // path has to be inside it to be a drop target).
  DndContext: ({ children }: { children: React.ReactNode }) => <div data-testid="drag-context">{children}</div>,
  DragOverlay: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  MouseSensor: class {},
  TouchSensor: class {},
  pointerWithin: () => [],
  rectIntersection: () => [],
  useSensor: () => ({}),
  useSensors: () => [],
  useDroppable: () => ({ setNodeRef: () => {}, isOver: false }),
  useDraggable: () => ({ setNodeRef: () => {}, attributes: {}, listeners: {}, isDragging: false }),
}));

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import WorkflowTable from '../WorkflowTable';

const tile = (id: string, name: string, itemCount = 2) => ({
  id,
  name,
  parentFolderId: null,
  itemCount,
  subfolderCount: 0,
  lastModifiedAt: '2026-06-01T00:00:00Z',
  lastActivityAt: null,
  activityCount: null,
  preview: [],
});

function page(overrides: Record<string, unknown> = {}) {
  return {
    workflows: [{ id: 'w1', name: 'Loose workflow', updatedAt: '2026-06-01T00:00:00Z', isPublished: false }],
    count: 1,
    totalCount: 1,
    page: 0,
    size: 25,
    folders: [],
    folderTrail: [],
    ...overrides,
  };
}

function renderTable() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <WorkflowTable />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  fakeFolderRouter.reset('/en/app/workflow');
  mocks.selected.clear();
  mocks.getWorkflowsPage.mockResolvedValue(page({ folders: [tile('f1', 'Marketing', 3)] }));
  mocks.listFolders.mockResolvedValue([{ id: 'f1', name: 'Marketing', parentFolderId: null }]);
  mocks.createFolder.mockResolvedValue({ id: 'f2', name: 'New', parentFolderId: null });
  mocks.assign.mockResolvedValue(2);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('WorkflowTable - folders', () => {
  it('asks the server for the top level and for its folder tiles', async () => {
    renderTable();

    await waitFor(() => expect(mocks.getWorkflowsPage).toHaveBeenCalled());
    expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(
      expect.objectContaining({ folderId: 'root', includeFolders: true }),
    );
  });

  it('shows a tile per folder, with what it holds', async () => {
    renderTable();

    expect(await screen.findByText('Marketing')).toBeInTheDocument();
    expect(screen.getByText(/3 workflows/)).toBeInTheDocument();
  });

  it('opening a folder re-asks the server for THAT level', async () => {
    renderTable();
    const folderTile = await screen.findByRole('button', { name: 'Marketing' });

    fireEvent.click(folderTile);

    await waitFor(() =>
      expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: 'f1', includeFolders: true }),
      ),
    );
  });

  it('opening a folder puts it in the ADDRESS, so the path can be shared and gone back from', async () => {
    renderTable();

    fireEvent.click(await screen.findByRole('button', { name: 'Marketing' }));

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('folder=f1'));
  });

  it('reads the folder from the address on arrival, so a shared link opens inside it', async () => {
    fakeFolderRouter.navigate('/en/app/workflow?folder=f1');

    renderTable();

    await waitFor(() =>
      expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: 'f1' }),
      ),
    );
  });

  it('leaving the folders clears the address rather than leaving a dead id in it', async () => {
    fakeFolderRouter.navigate('/en/app/workflow?folder=f1');
    mocks.getWorkflowsPage.mockResolvedValue(page({
      workflows: [],
      totalCount: 0,
      folders: [],
      folderTrail: [{ id: 'f1', name: 'Marketing', parentFolderId: null }],
    }));
    renderTable();

    fireEvent.click(await screen.findByRole('button', { name: 'All workflows' }));

    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
  });

  it('shows the trail once inside a folder, and leaves it out at the top level', async () => {
    renderTable();
    await screen.findByText('Marketing');
    expect(screen.queryByRole('button', { name: 'All workflows' })).not.toBeInTheDocument();

    mocks.getWorkflowsPage.mockResolvedValue(page({
      workflows: [],
      totalCount: 0,
      folders: [],
      folderTrail: [{ id: 'f1', name: 'Marketing', parentFolderId: null }],
    }));
    fireEvent.click(screen.getByRole('button', { name: 'Marketing' }));

    expect(await screen.findByRole('button', { name: 'All workflows' })).toBeInTheDocument();
  });

  it('an empty folder says so instead of offering to create a first workflow', async () => {
    renderTable();
    const folderTile = await screen.findByRole('button', { name: 'Marketing' });

    mocks.getWorkflowsPage.mockResolvedValue(page({
      workflows: [],
      totalCount: 0,
      folders: [],
      folderTrail: [{ id: 'f1', name: 'Marketing', parentFolderId: null }],
    }));
    fireEvent.click(folderTile);

    await waitFor(() => expect(screen.getByTestId('empty-state')).toHaveTextContent('This folder is empty'));
  });

  it('a search looks through every folder: the tiles step aside', async () => {
    renderTable();
    await screen.findByText('Marketing');

    mocks.getWorkflowsPage.mockResolvedValue(page({ folders: [] }));
    fireEvent.change(screen.getByPlaceholderText(enMessages.workflow.searchPlaceholder as string), {
      target: { value: 'needle' },
    });

    await waitFor(() => expect(screen.queryByText('Marketing')).not.toBeInTheDocument());
    // ... and the "New folder" action goes with them: there is no current level to create in.
    expect(screen.queryByRole('button', { name: /New folder/ })).not.toBeInTheDocument();
  });

  it('creates a folder where the user is standing', async () => {
    renderTable();
    await screen.findByText('Marketing');

    fireEvent.click(screen.getByRole('button', { name: /New folder/ }));
    fireEvent.change(screen.getByPlaceholderText('Folder name'), { target: { value: 'Growth' } });
    fireEvent.click(screen.getByRole('button', { name: /Create folder/ }));

    await waitFor(() => expect(mocks.createFolder).toHaveBeenCalledWith('workflow', 'Growth', null));
  });

  it('offers Move to folder on a selection and files the whole selection', async () => {
    mocks.selected.add('w1');
    mocks.selected.add('w2');
    renderTable();
    await screen.findByText('Marketing');

    fireEvent.click(screen.getByRole('button', { name: /Move to folder/ }));

    // The picker loads every level, not just the one on screen.
    await waitFor(() => expect(mocks.listFolders).toHaveBeenCalledWith('workflow'));
    // Scoped to the dialog: "Marketing" is also the name of the tile behind it.
    const picker = within(await screen.findByRole('dialog'));
    fireEvent.click(picker.getByRole('button', { name: /Marketing/ }));
    fireEvent.click(picker.getByRole('button', { name: /Move here/i }));

    await waitFor(() =>
      expect(mocks.assign).toHaveBeenCalledWith('workflow', 'f1', expect.arrayContaining(['w1', 'w2'])),
    );
  });

  it('opening a folder is a STEP, so the browser Back button walks back out of it', async () => {
    renderTable();

    fireEvent.click(await screen.findByRole('button', { name: 'Marketing' }));

    // `replace` would overwrite the level the user came from, and Back would then leave the
    // list entirely instead of going up one folder. That was the reported bug.
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('folder=f1'));
    expect(fakeFolderRouter.navigations.at(-1)).toEqual({
      url: '/en/app/workflow?folder=f1',
      method: 'push',
    });
  });

  it('the crumb of the folder you are IN is the page, not a link back to itself', async () => {
    fakeFolderRouter.navigate('/en/app/workflow?folder=f1');
    mocks.getWorkflowsPage.mockResolvedValue(page({
      workflows: [],
      totalCount: 0,
      folders: [],
      folderTrail: [{ id: 'f1', name: 'Marketing', parentFolderId: null }],
    }));
    renderTable();

    const currentCrumb = await screen.findByRole('button', { name: 'Marketing', current: 'page' });

    // Clickable, it would stack the same address over and over, and getting out of the
    // folder would then cost one Back per click.
    expect(currentCrumb).toBeDisabled();
    const before = fakeFolderRouter.navigations.length;
    fireEvent.click(currentCrumb);
    expect(fakeFolderRouter.navigations.length).toBe(before);
  });

  it('the up-one-level arrow goes to the folder ABOVE, not out of the folders', async () => {
    fakeFolderRouter.navigate('/en/app/workflow?folder=f2');
    mocks.getWorkflowsPage.mockResolvedValue(page({
      workflows: [],
      totalCount: 0,
      folders: [],
      folderTrail: [
        { id: 'f1', name: 'Marketing', parentFolderId: null },
        { id: 'f2', name: 'Q4', parentFolderId: 'f1' },
      ],
    }));
    renderTable();

    fireEvent.click(await screen.findByRole('button', { name: 'Up one level' }));

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('folder=f1'));
  });

  it('follows the server back to the top level when the folder is gone', async () => {
    renderTable();
    await screen.findByText('Marketing');

    mocks.getWorkflowsPage.mockResolvedValue(page({ folders: [tile('f1', 'Marketing', 3)], folderMissing: true }));
    fireEvent.click(screen.getByRole('button', { name: 'Marketing' }));

    // Navigating into it asked for f1; the answer said it is gone, so the address is taken
    // back to the top level and the next load asks for root.
    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
    await waitFor(() =>
      expect(mocks.getWorkflowsPage).toHaveBeenLastCalledWith(
        expect.objectContaining({ folderId: 'root' }),
      ),
    );
  });
});

describe('WorkflowTable - the drag surface', () => {
  it('covers the folder path, so a card can be dragged out of a folder onto it', async () => {
    // A drop target only exists inside the context that owns the drag. The path lives in the
    // page HEADER: with the context around the cards alone, every crumb was inert and the one
    // move a folder path is there to offer - taking a card back out - did nothing.
    mocks.getWorkflowsPage.mockResolvedValue(
      page({ folders: [], folderTrail: [{ id: 'f1', name: 'Marketing', parentFolderId: null }] }),
    );

    renderTable();

    const crumb = await screen.findByRole('button', { name: 'All workflows' });
    expect(screen.getByTestId('drag-context')).toContainElement(crumb);
  });
});
