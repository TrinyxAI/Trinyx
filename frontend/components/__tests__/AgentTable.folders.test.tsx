// @vitest-environment jsdom
/**
 * Folders on the Agents list. The tile design and the folder rules are pinned elsewhere;
 * what matters here is that this list is wired to the SAME machinery: it asks the server
 * for one level, opening a tile navigates into it, and a search steps out of the folders.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

const mocks = vi.hoisted(() => ({
  getAgentsPage: vi.fn(),
  listFolders: vi.fn(),
  createFolder: vi.fn(),
  assign: vi.fn(),
  selected: new Set<string>(),
}));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
// The lists keep the open folder in the address, so they read next/navigation. This fake
// router is URL-backed and re-renders on navigation, the way the real one does.
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: { getAgentsPage: mocks.getAgentsPage, getFleetTriggers: vi.fn().mockResolvedValue([]) },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { unpublishAgent: vi.fn() },
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/agents/AvatarPicker', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/publications/PublicationStatusIcon', () => ({ PublicationStatusIcon: () => null }));
// Drivable stand-in for the create modal: one button reports a CREATE (it carries the new
// agent's id), the other reports an edit (it carries nothing). That difference is the whole
// contract the list depends on to file a new agent into the folder being shown.
vi.mock('@/components/chat/CreateAgentModal', () => ({
  CreateAgentModal: ({ onAgentCreated }: { onAgentCreated: (id?: string) => void }) => (
    <div>
      <button type="button" onClick={() => onAgentCreated('a9')}>report create</button>
      <button type="button" onClick={() => onAgentCreated()}>report edit</button>
    </div>
  ),
}));
vi.mock('@/components/marketplace/PublishAgentModal', () => ({ default: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONFIGURATION_TAB: 'config',
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/lib/api/orchestrator/resource-folder.service', () => ({
  resourceFolderService: {
    list: mocks.listFolders,
    create: mocks.createFolder,
    rename: vi.fn(),
    move: vi.fn(),
    remove: vi.fn(),
    assign: mocks.assign,
  },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { cloneAgent: vi.fn(), deleteAgent: vi.fn() },
}));
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
import { AgentTable } from '../AgentTable';

const tile = (id: string, name: string, itemCount = 2) => ({
  id,
  name,
  parentFolderId: null,
  itemCount,
  subfolderCount: 0,
  lastModifiedAt: '2026-06-01T00:00:00Z',
  lastActivityAt: null,
  activityCount: null,
  preview: [{ id: 'a1', name: 'Nova', imageUrl: 'preset:purple' }],
});

function page(overrides: Record<string, unknown> = {}) {
  return {
    items: [{ id: 'a1', name: 'Loose agent', updatedAt: '2026-06-01T00:00:00Z' }],
    totalCount: 1,
    page: 0,
    size: 25,
    publicationStatuses: {},
    folders: [],
    folderTrail: [],
    ...overrides,
  };
}

function renderTable() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <AgentTable />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  // The address is shared state: without this, a test that opened a folder leaves the
  // next one starting inside it.
  fakeFolderRouter.reset();
  mocks.selected.clear();
  mocks.getAgentsPage.mockResolvedValue(page({ folders: [tile('f1', 'Support', 3)] }));
  mocks.listFolders.mockResolvedValue([{ id: 'f1', name: 'Support', parentFolderId: null }]);
  mocks.createFolder.mockResolvedValue({ id: 'f2', name: 'New', parentFolderId: null });
  mocks.assign.mockResolvedValue(2);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('AgentTable - folders', () => {
  it('asks the server for the top level and for its folder tiles', async () => {
    renderTable();

    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());
    expect(mocks.getAgentsPage).toHaveBeenCalledWith(
      expect.objectContaining({ folderId: 'root', includeFolders: true }),
    );
  });

  it('shows a tile per folder, counted in agents', async () => {
    renderTable();

    expect(await screen.findByText('Support')).toBeInTheDocument();
    expect(screen.getByText(/3 agents/)).toBeInTheDocument();
  });

  it('opening a folder re-asks the server for THAT level', async () => {
    renderTable();
    const folderTile = await screen.findByRole('button', { name: 'Support' });

    fireEvent.click(folderTile);

    await waitFor(() =>
      expect(mocks.getAgentsPage).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: 'f1', includeFolders: true }),
      ),
    );
  });

  it('shows the trail once inside a folder', async () => {
    renderTable();
    await screen.findByText('Support');
    expect(screen.queryByRole('button', { name: 'All agents' })).not.toBeInTheDocument();

    mocks.getAgentsPage.mockResolvedValue(page({
      items: [],
      totalCount: 0,
      folders: [],
      folderTrail: [{ id: 'f1', name: 'Support', parentFolderId: null }],
    }));
    fireEvent.click(screen.getByRole('button', { name: 'Support' }));

    expect(await screen.findByRole('button', { name: 'All agents' })).toBeInTheDocument();
  });

  it('a search looks through every folder: the tiles step aside', async () => {
    renderTable();
    await screen.findByText('Support');

    mocks.getAgentsPage.mockResolvedValue(page({ folders: [] }));
    fireEvent.change(screen.getByPlaceholderText(enMessages.emptyState.agent.searchPlaceholder as string), {
      target: { value: 'needle' },
    });

    await waitFor(() => expect(screen.queryByText('Support')).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /New folder/ })).not.toBeInTheDocument();
  });

  it('creates a folder where the user is standing', async () => {
    renderTable();
    await screen.findByText('Support');

    fireEvent.click(screen.getByRole('button', { name: /New folder/ }));
    fireEvent.change(screen.getByPlaceholderText('Folder name'), { target: { value: 'Growth' } });
    fireEvent.click(screen.getByRole('button', { name: /Create folder/ }));

    await waitFor(() => expect(mocks.createFolder).toHaveBeenCalledWith('agent', 'Growth', null));
  });

  it('files the selection through the agent list\'s own folders', async () => {
    mocks.selected.add('a1');
    renderTable();
    await screen.findByText('Support');

    fireEvent.click(screen.getByRole('button', { name: /Move to folder/ }));

    await waitFor(() => expect(mocks.listFolders).toHaveBeenCalledWith('agent'));
  });
  it('an agent created inside a folder is filed there, not back at the top level', async () => {
    fakeFolderRouter.navigate('/en/app/agent?folder=f1');
    mocks.getAgentsPage.mockResolvedValue(page({
      items: [],
      totalCount: 0,
      folders: [],
      folderTrail: [{ id: 'f1', name: 'Support', parentFolderId: null }],
    }));
    renderTable();
    await screen.findByRole('button', { name: 'All agents' });

    fireEvent.click(screen.getByRole('button', { name: /Create agent/i }));
    fireEvent.click(await screen.findByRole('button', { name: 'report create' }));

    // Without this the new agent lands at the top level and the folder you were standing
    // in looks like a filter that creation ignores.
    await waitFor(() => expect(mocks.assign).toHaveBeenCalledWith('agent', 'f1', ['a9']));
  });

  it('an agent EDITED inside a folder is not refiled', async () => {
    fakeFolderRouter.navigate('/en/app/agent?folder=f1');
    mocks.getAgentsPage.mockResolvedValue(page({
      items: [],
      totalCount: 0,
      folders: [],
      folderTrail: [{ id: 'f1', name: 'Support', parentFolderId: null }],
    }));
    renderTable();
    await screen.findByRole('button', { name: 'All agents' });

    fireEvent.click(screen.getByRole('button', { name: /Create agent/i }));
    fireEvent.click(await screen.findByRole('button', { name: 'report edit' }));

    // The same callback fires after an edit: filing then would move an agent the user only
    // opened to change.
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());
    expect(mocks.assign).not.toHaveBeenCalled();
  });

  it('creating at the TOP level files nothing at all', async () => {
    renderTable();
    await screen.findByText('Support');

    fireEvent.click(screen.getByRole('button', { name: /Create agent/i }));
    fireEvent.click(await screen.findByRole('button', { name: 'report create' }));

    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());
    expect(mocks.assign).not.toHaveBeenCalled();
  });
});

/**
 * A drop target only exists inside the context that owns the drag, and the folder path lives
 * in the page HEADER. With the drag context around the cards alone every crumb was inert, so
 * dragging a card out of a folder - the one move a folder path is there to offer - did nothing.
 */
describe('AgentTable - the drag surface', () => {
  it('covers the folder path, so a card can be dragged out of a folder onto it', async () => {
    mocks.getAgentsPage.mockResolvedValue(
      page({ folders: [], folderTrail: [{ id: 'f1', name: 'Support crew', parentFolderId: null }] }),
    );

    renderTable();

    const crumb = await screen.findByRole('button', { name: 'All agents' });
    expect(screen.getByTestId('drag-context')).toContainElement(crumb);
  });
});
