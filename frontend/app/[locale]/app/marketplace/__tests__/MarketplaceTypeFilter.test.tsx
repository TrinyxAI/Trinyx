// @vitest-environment jsdom
/**
 * Explore's resource-type filter is a SELECT sitting beside the category one,
 * not a row of chips underneath the search bar.
 *
 * The two controls narrow the same grid, so they belong on the same line and in
 * the same shape; the chips read as a second, unrelated navigation level. What
 * matters beyond the looks is that the behaviour is unchanged: the choice still
 * rides the `type` query param (so Back / a pasted link restore the grid the
 * user was looking at) and it still scopes the grid to a single display mode.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  // Key-echo translator: assertions match raw keys (filterApplications, ...).
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

// The type filter is query-param backed (useQueryParamState): the router mock
// writes back into the params so a pick round-trips exactly like in the app.
const routerState = vi.hoisted(() => ({
  params: new URLSearchParams(),
  replace: vi.fn(),
}));
vi.mock('next/navigation', () => ({
  useSearchParams: () => routerState.params,
  usePathname: () => '/app/marketplace',
  useRouter: () => ({ replace: routerState.replace, push: vi.fn() }),
}));

vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn().mockResolvedValue(undefined) }),
}));

vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getAuthUrl: vi.fn(), connect: vi.fn() },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ isLoading: false, isCloudLinked: true, isInstallCloudLinked: true }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, numericUserId: 7 }),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true }));

const orchestratorApiMock = vi.hoisted(() => ({
  getMarketplacePublications: vi.fn(),
  searchPublications: vi.fn(),
  getMyPublications: vi.fn(),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: orchestratorApiMock }));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getAcquiredApplications: vi.fn().mockResolvedValue({ applications: [] }),
    getPurchases: vi.fn().mockResolvedValue({ purchases: [] }),
    getRemoteMarketplacePublications: vi.fn(),
    searchRemotePublications: vi.fn(),
  },
}));

// The category filter is the control the type select must sit NEXT TO: keep it
// as a marker so the layout assertion has something real to compare against.
vi.mock('@/components/marketplace/CategoryFilter', () => ({
  CategoryFilter: () => <div data-testid="category-filter" />,
}));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({
  default: () => null,
}));
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({ publication }: { publication: { id: string; title: string } }) => (
    <div data-testid="publication-card">{publication.title}</div>
  ),
  PublicationCardSkeleton: () => <div data-testid="card-skeleton" />,
}));

// Radix Select needs these; jsdom has neither.
beforeAll(() => {
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  (Element.prototype as unknown as { scrollIntoView: () => void }).scrollIntoView = () => {};
  (Element.prototype as unknown as { hasPointerCapture: () => boolean }).hasPointerCapture = () => false;
  (Element.prototype as unknown as { setPointerCapture: () => void }).setPointerCapture = () => {};
  (Element.prototype as unknown as { releasePointerCapture: () => void }).releasePointerCapture = () => {};
});

import MarketplacePage from '../page';

const APP_PUB = {
  id: 'pub-app-1',
  title: 'An Application',
  displayMode: 'APPLICATION',
  publicationType: 'WORKFLOW',
  creditsPerUse: 0,
};
const AGENT_PUB = {
  id: 'pub-agent-1',
  title: 'An Agent',
  displayMode: 'AGENT',
  publicationType: 'AGENT',
  creditsPerUse: 0,
};

beforeEach(() => {
  vi.clearAllMocks();
  routerState.params = new URLSearchParams();
  // The type filter is a QUERY PARAM answered by the backend, so the mock
  // answers it the same way. Pre-fix it was a client-side pass over one
  // popularity-ordered page, which meant the agents grid could only ever show
  // the agents that happened to rank inside that page.
  orchestratorApiMock.getMarketplacePublications.mockImplementation(
    async (_page: number, _size: number, _category?: string, refinements?: { displayMode?: string }) => {
      const all = [APP_PUB, AGENT_PUB];
      const rows = refinements?.displayMode
        ? all.filter((p) => p.displayMode === refinements.displayMode)
        : all;
      return { publications: rows, count: rows.length };
    });
});

afterEach(cleanup);

/** The type control, whatever it is drawn as. */
function typeSelect(): HTMLElement {
  return screen.getByRole('combobox', { name: 'filterByType' });
}

describe('Marketplace Explore - resource type is a select beside the category filter', () => {
  it('renders it as a menu next to the category filter, not as chips', async () => {
    render(<MarketplacePage />);

    const select = typeSelect();
    expect(select).toBeInTheDocument();
    // Same row as the category filter, which is the whole point of the move.
    expect(select.parentElement).toBe(screen.getByTestId('category-filter').parentElement);
    // The chips are gone: no standalone button carrying a type label.
    expect(screen.queryByRole('button', { name: 'filterApplications' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'filterAgents' })).not.toBeInTheDocument();
    await waitFor(() => expect(orchestratorApiMock.getMarketplacePublications).toHaveBeenCalled());
  });

  it('defaults to applications and asks the SERVER for that display mode', async () => {
    render(<MarketplacePage />);

    expect(typeSelect().textContent).toContain('filterApplications');
    expect(await screen.findByText('An Application')).toBeInTheDocument();
    expect(screen.queryByText('An Agent')).not.toBeInTheDocument();
    await waitFor(() => expect(orchestratorApiMock.getMarketplacePublications).toHaveBeenCalledWith(
      0, expect.any(Number), undefined, expect.objectContaining({ displayMode: 'APPLICATION' })));
  });

  it('offers exactly the two surfaced types', async () => {
    render(<MarketplacePage />);

    fireEvent.click(typeSelect());

    const options = await screen.findAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual(['filterApplications', 'filterAgents']);
  });

  it('writes the pick to the type query param, so Back and a pasted link restore it', async () => {
    render(<MarketplacePage />);

    fireEvent.click(typeSelect());
    fireEvent.click(await screen.findByRole('option', { name: 'filterAgents' }));

    // replace, not push: flipping a filter is not a step Back should walk through.
    await waitFor(() => {
      expect(routerState.replace).toHaveBeenCalledWith('/app/marketplace?type=agents', { scroll: false });
    });
  });

  it('scopes the grid to the type carried by the URL', async () => {
    // A pasted ?type=agents link lands on the agents grid, with the select
    // reading the same thing.
    routerState.params = new URLSearchParams('type=agents');

    render(<MarketplacePage />);

    expect(typeSelect().textContent).toContain('filterAgents');
    expect(await screen.findByText('An Agent')).toBeInTheDocument();
    expect(screen.queryByText('An Application')).not.toBeInTheDocument();
    await waitFor(() => expect(orchestratorApiMock.getMarketplacePublications).toHaveBeenCalledWith(
      0, expect.any(Number), undefined, expect.objectContaining({ displayMode: 'AGENT' })));
  });
});
