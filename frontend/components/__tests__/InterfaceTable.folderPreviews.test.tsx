// @vitest-environment jsdom
/**
 * A folder tile on the pages list previews what it holds by RENDERING those pages. The list
 * payload deliberately omits html (it is the heavy field), and the pages a folder holds are
 * never among the cards at that level, so nothing would ever load their templates: the face
 * stayed on its grey silhouettes forever. This pins the loading, and its two bounds.
 *
 * Same harness as InterfaceTable.visibilitySort: mocked services, next-intl echoed to keys.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  getInterfacesPage: vi.fn(),
  getInterface: vi.fn(),
}));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { cloneInterface: vi.fn(), deleteInterface: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/interface.service', () => ({
  interfaceService: { getInterfacesPage: mocks.getInterfacesPage, getInterface: mocks.getInterface },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { unpublishResource: vi.fn() },
}));
// The face's real thumbnail mounts a sandboxed iframe; expose the html it is handed instead.
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: ({ htmlTemplate }: { htmlTemplate: string }) => (
    <div data-testid="thumbnail" data-html={htmlTemplate} />
  ),
}));
vi.mock('@/components/chat/CreateInterfaceModal', () => ({ CreateInterfaceModal: () => null }));
vi.mock('@/components/marketplace/PublishResourceModal', () => ({ default: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({ selectedIds: new Set<string>(), toggle: vi.fn(), clear: vi.fn(), selectAll: vi.fn() }),
}));
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ children }: any) => children, DialogContent: ({ children }: any) => children,
  DialogHeader: ({ children }: any) => children, DialogTitle: ({ children }: any) => children,
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: any) => <div>{children}</div>,
  SelectTrigger: ({ children }: any) => <span>{children}</span>,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <div>{children}</div>,
  SelectItem: () => null,
}));
// Favorites load ASYNCHRONOUSLY, just after the list: the set's identity changes once, which
// rebuilds the row array and re-runs the template effects. The handle lets a test reproduce
// that mid-flight change, which is what used to double every template request.
const favorites = vi.hoisted(() => ({ setIds: (_ids: Set<string>) => {} }));
vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => {
    const [favoriteIds, setIds] = React.useState<Set<string>>(new Set());
    favorites.setIds = setIds;
    return { favoriteIds, toggleFavorite: vi.fn() };
  },
}));


// A real element in place of the drag context, so a test can assert what is INSIDE it: the
// folder path has to be within the context that owns the drag or its crumbs are dead targets.
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>();
  return {
    ...actual,
    DndContext: ({ children }: { children: React.ReactNode }) => (
      <div data-testid="drag-context">{children}</div>
    ),
    DragOverlay: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  };
});

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import { INTERFACE_FACE_CELLS } from '@/components/folders/InterfaceFolderFace';
import { InterfaceTable } from '../InterfaceTable';

const previewItem = (id: string) => ({ id, name: `Page ${id}` });

const tile = (id: string, previewIds: string[]) => ({
  id,
  name: `Folder ${id}`,
  parentFolderId: null,
  itemCount: previewIds.length,
  subfolderCount: 0,
  preview: previewIds.map(previewItem),
});

const page = (items: any[], folders: any[] = [], folderTrail: any[] = []) => ({
  items,
  totalCount: items.length,
  page: 0,
  size: 25,
  publicationStatuses: {},
  folders,
  folderTrail,
});

const intf = (id: string, name: string) => ({
  id, name, tenantId: 't', isPublic: false, isActive: true, updatedAt: '2026-06-01T00:00:00Z',
});

beforeEach(() => {
  fakeFolderRouter.reset();
  mocks.getInterface.mockImplementation((id: string) =>
    Promise.resolve({ id, name: id, htmlTemplate: `<h1>${id}</h1>`, cssTemplate: '', jsTemplate: '' }));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('InterfaceTable - a folder tile previews the pages it holds by rendering them', () => {
  it('loads the templates of the previewed pages, which are never among the cards', async () => {
    // The level shows one card (i1) and one folder holding two OTHER pages.
    mocks.getInterfacesPage.mockResolvedValue(page([intf('i1', 'Card One')], [tile('f1', ['p1', 'p2'])]));

    render(<InterfaceTable interfaceTypeFilter="html" />);

    await waitFor(() => expect(mocks.getInterface).toHaveBeenCalledWith('p1'));
    expect(mocks.getInterface).toHaveBeenCalledWith('p2');
    // ...and the face draws the real pages, not their silhouettes.
    await waitFor(() => {
      const rendered = screen.getAllByTestId('thumbnail').map((el) => el.getAttribute('data-html'));
      expect(rendered).toEqual(expect.arrayContaining(['<h1>p1</h1>', '<h1>p2</h1>']));
    });
    expect(screen.queryByText('Page p1')).not.toBeInTheDocument();
  });

  it('loads no more than the face draws, however many pages the folder holds', async () => {
    mocks.getInterfacesPage.mockResolvedValue(page([], [tile('f1', ['p1', 'p2', 'p3', 'p4', 'p5', 'p6'])]));

    render(<InterfaceTable interfaceTypeFilter="html" />);

    await waitFor(() => expect(mocks.getInterface).toHaveBeenCalledWith('p1'));
    expect(mocks.getInterface).toHaveBeenCalledTimes(INTERFACE_FACE_CELLS);
    // The pages past the face's cells are never asked for.
    expect(mocks.getInterface).not.toHaveBeenCalledWith(`p${INTERFACE_FACE_CELLS + 1}`);
  });

  it('spends the budget on whole faces, so no face is left half drawn', async () => {
    // Folders of unequal size: 3 + 4 + 4 + 4 fills the budget of 16 exactly, and the fifth
    // folder must be left alone rather than getting one page beside three silhouettes, which
    // reads as three pages that failed to load.
    const sizes = [3, 4, 4, 4, 4];
    const folders = sizes.map((size, i) =>
      tile(`f${i}`, Array.from({ length: size }, (_, j) => `f${i}p${j}`)));
    mocks.getInterfacesPage.mockResolvedValue(page([], folders));

    render(<InterfaceTable interfaceTypeFilter="html" />);

    await waitFor(() => expect(mocks.getInterface).toHaveBeenCalledWith('f0p0'));
    // 3 + 4 + 4 + 4 = 15, and the last face of 4 would overrun the budget of 16.
    expect(mocks.getInterface).toHaveBeenCalledTimes(15);
    expect(mocks.getInterface).toHaveBeenCalledWith('f3p3');
    expect(mocks.getInterface).not.toHaveBeenCalledWith('f4p0');
  });

  it('caps the whole level, so a folder-heavy page cannot turn into dozens of loads', async () => {
    // Seven full folders is 7 x INTERFACE_FACE_CELLS candidates, well over the level's budget.
    const budget = INTERFACE_FACE_CELLS * 4;
    const folders = ['a', 'b', 'c', 'd', 'e', 'f', 'g'].map((f) =>
      tile(f, Array.from({ length: INTERFACE_FACE_CELLS }, (_, i) => `${f}${i + 1}`)));
    mocks.getInterfacesPage.mockResolvedValue(page([], folders));

    render(<InterfaceTable interfaceTypeFilter="html" />);

    await waitFor(() => expect(mocks.getInterface).toHaveBeenCalledWith('a1'));
    expect(mocks.getInterface).toHaveBeenCalledTimes(budget);
  });

  it('never re-requests a template already on the wire when the list re-renders under it', async () => {
    // Hold every template request open, so the favourites can land while the batch is in flight.
    let release: () => void = () => {};
    const inFlight = new Promise<void>((resolve) => { release = resolve; });
    mocks.getInterface.mockImplementation(async (id: string) => {
      await inFlight;
      return { id, name: id, htmlTemplate: `<h1>${id}</h1>`, cssTemplate: '', jsTemplate: '' };
    });
    mocks.getInterfacesPage.mockResolvedValue(page([intf('i1', 'Card One')], [tile('f1', ['p1', 'p2'])]));

    render(<InterfaceTable interfaceTypeFilter="html" />);
    await waitFor(() => expect(mocks.getInterface).toHaveBeenCalledTimes(3));

    // The favourites arrive: the row array is rebuilt and both effects re-run, mid-flight.
    await act(async () => { favorites.setIds(new Set(['i1'])); });

    expect(mocks.getInterface).toHaveBeenCalledTimes(3);

    // And the batch still lands - claiming an id must not mean abandoning its request.
    await act(async () => { release(); await inFlight; });
    await waitFor(() => expect(screen.getAllByTestId('thumbnail').length).toBeGreaterThan(0));
  });

  it('asks for each template once - a re-render never re-fetches what it already has', async () => {
    mocks.getInterfacesPage.mockResolvedValue(page([intf('i1', 'Card One')], [tile('f1', ['p1'])]));

    render(<InterfaceTable interfaceTypeFilter="html" />);

    await waitFor(() => expect(mocks.getInterface).toHaveBeenCalledWith('p1'));
    // i1 (the card) + p1 (the folder's page), each exactly once.
    await waitFor(() => expect(mocks.getInterface).toHaveBeenCalledTimes(2));
    expect(mocks.getInterface.mock.calls.filter(([id]) => id === 'p1')).toHaveLength(1);
  });
});

/**
 * A drop target only exists inside the context that owns the drag, and the folder path lives
 * in the page HEADER. With the drag context around the cards alone every crumb was inert, so
 * dragging a card out of a folder - the one move a folder path is there to offer - did nothing.
 */
describe('InterfaceTable - the drag surface', () => {
  it('covers the folder path, so a card can be dragged out of a folder onto it', async () => {
    mocks.getInterfacesPage.mockResolvedValue(
      page([], [], [{ id: 'f1', name: 'Launch pages', parentFolderId: null }]),
    );

    render(<InterfaceTable interfaceTypeFilter="html" />);

    // This file renders without an intl provider, so a label comes through as its key.
    const crumb = await screen.findByRole('button', { name: 'folders.allInterfaces' });
    expect(screen.getByTestId('drag-context')).toContainElement(crumb);
  });
});
