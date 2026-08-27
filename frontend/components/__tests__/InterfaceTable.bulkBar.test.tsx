// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * Interface list selection actions now float in the bottom-center SelectionActionBar.
 * The single-selection publish state machine (Share → Unshare → Pending Review) is the
 * regression-prone bit, so it is exercised through the bar here. Same conditional shape
 * is shared by AgentTable + DataSourceTable.
 */

const mocks = vi.hoisted(() => ({
  getInterfacesPage: vi.fn(),
  cloneInterface: vi.fn(),
  deleteInterface: vi.fn(),
  clear: vi.fn(),
}));

// The lists keep the open folder in the address, so they read next/navigation. This fake
// router is URL-backed and re-renders on navigation, the way the real one does.
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { cloneInterface: mocks.cloneInterface, deleteInterface: mocks.deleteInterface },
}));
vi.mock('@/lib/api/orchestrator/interface.service', () => ({
  interfaceService: { getInterfacesPage: mocks.getInterfacesPage },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { unpublishResource: vi.fn() },
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({ InterfaceThumbnail: () => null }));
vi.mock('@/components/publications/PublicationStatusIcon', () => ({ PublicationStatusIcon: () => null }));
vi.mock('@/components/chat/CreateInterfaceModal', () => ({ CreateInterfaceModal: () => null }));
vi.mock('@/components/marketplace/PublishResourceModal', () => ({ default: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({
    selectedIds: new Set<string>(['i1']),
    toggle: vi.fn(),
    clear: mocks.clear,
    selectAll: vi.fn(),
  }),
}));

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import { InterfaceTable } from '../InterfaceTable';

function renderTable() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <InterfaceTable />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

// Publication status now ships inline on the page envelope (publicationStatuses), so the bar's
// publish state machine reads it straight from the list page - no separate getAllMyPublications sweep.
const oneInterface = (publicationStatuses: Record<string, { status: string }> = {}) =>
  mocks.getInterfacesPage.mockResolvedValue({
    items: [{ id: 'i1', name: 'IF One', isPublic: false, isActive: true, htmlTemplate: '<div>x</div>' }],
    totalCount: 1, page: 0, size: 25, publicationStatuses,
  });

beforeEach(() => {
  // The address is shared state: without this, a test that opened a folder leaves the
  // next one starting inside it.
  fakeFolderRouter.reset();
});

describe('InterfaceTable - selection actions float + publish state machine in the bar', () => {
  it('an unpublished selection shows Clone + Share + Delete in the bar', async () => {
    oneInterface();
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    expect(within(bar).getByText('1 selected')).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Clone (1)' })).toBeInTheDocument();
    expect(await within(bar).findByRole('button', { name: 'Share' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Delete (1)' })).toBeInTheDocument();
  });

  it('a published selection swaps Share → Unshare', async () => {
    oneInterface({ i1: { status: 'ACTIVE' } });
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    expect(await within(bar).findByRole('button', { name: 'Unshare' })).toBeInTheDocument();
    expect(within(bar).queryByRole('button', { name: 'Share' })).not.toBeInTheDocument();
  });

  it('a pending-review selection shows a disabled "Pending Review" action', async () => {
    oneInterface({ i1: { status: 'PENDING_REVIEW' } });
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    const pending = await within(bar).findByRole('button', { name: 'Pending Review' });
    expect(pending).toBeDisabled();
  });

  it('the bar × clears the selection', async () => {
    oneInterface();
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(within(bar).getByTestId('selection-action-bar-clear'));
    expect(mocks.clear).toHaveBeenCalledTimes(1);
  });
});
