// @vitest-environment jsdom
/**
 * Explore refinements: sort + rating / date / price filters.
 *
 * These run CLIENT-side over the page the backend returned, so the whole
 * contract lives in this component: which cards survive each filter, in which
 * order each sort puts them, and what the grid says when a filter empties it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
const searchParamsState = vi.hoisted(() => ({ params: new URLSearchParams() }));
const routerMock = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => searchParamsState.params,
  usePathname: () => '/app/marketplace',
  useRouter: () => routerMock,
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn().mockResolvedValue(undefined) }),
}));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getAuthUrl: vi.fn(), connect: vi.fn() },
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: false, numericUserId: null }),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ status: null, isLoading: false, isCloudLinked: false, isInstallCloudLinked: false }),
}));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

const orchestratorApiMock = vi.hoisted(() => ({
  getMarketplacePublications: vi.fn(),
  searchPublications: vi.fn(),
  getMyPublications: vi.fn(),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: orchestratorApiMock }));

const publicationServiceMock = vi.hoisted(() => ({
  getAcquiredApplications: vi.fn(),
  getPurchases: vi.fn(),
  getRemoteMarketplacePublications: vi.fn(),
  searchRemotePublications: vi.fn(),
  acquirePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquireRemotePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: publicationServiceMock,
}));
vi.mock('@/components/marketplace/CategoryFilter', () => ({
  CategoryFilter: () => <div data-testid="category-filter" />,
}));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: (props: { publication: { title: string } }) => (
    <div data-testid="pub-card">{props.publication.title}</div>
  ),
  PublicationCardSkeleton: () => <div data-testid="card-skeleton" />,
}));

import MarketplacePage from '../page';

// The date filter is a rolling window over the real clock. Rather than freezing
// time (fake timers fight testing-library's waitFor), the fixture ages sit far
// from every window boundary - 1 and 3 days against a 7-day window, 45 against
// 30 and 90, 200 against 90 and 365 - so the expected sets hold whenever the
// suite runs.
const daysAgo = (n: number) => new Date(Date.now() - n * 24 * 60 * 60 * 1000).toISOString();

/**
 * One fixture per axis under test.
 *
 * Server order is deliberately NOT any of the sortable orders, so a passing
 * "popular keeps the server order" assertion cannot be an accident of the
 * fixtures already being sorted.
 */
const PUBLICATIONS = [
  {
    id: 'p-fresh', title: 'Fresh Free', displayMode: 'APPLICATION', publisherId: '9',
    averageRating: 3.2, reviewCount: 5, useCount: 1, creditsPerUse: 0, publishedAt: daysAgo(3),
  },
  {
    id: 'p-top', title: 'Top Rated', displayMode: 'APPLICATION', publisherId: '9',
    averageRating: 4.8, reviewCount: 12, useCount: 4, creditsPerUse: 10, publishedAt: daysAgo(45),
  },
  {
    id: 'p-installed', title: 'Most Installed', displayMode: 'APPLICATION', publisherId: '9',
    averageRating: 4.0, reviewCount: 2, useCount: 99, creditsPerUse: 0, publishedAt: daysAgo(200),
  },
  {
    id: 'p-unrated', title: 'Unrated Newcomer', displayMode: 'APPLICATION', publisherId: '9',
    averageRating: 0, reviewCount: 0, useCount: 0, creditsPerUse: 0, publishedAt: daysAgo(1),
  },
  {
    id: 'p-undated', title: 'No Date', displayMode: 'APPLICATION', publisherId: '9',
    averageRating: 5, reviewCount: 1, useCount: 0, creditsPerUse: 25, publishedAt: undefined,
  },
];

const titlesInOrder = () => screen.getAllByTestId('pub-card').map((el) => el.textContent);

const renderWith = async (query: string) => {
  searchParamsState.params = new URLSearchParams(query);
  render(<MarketplacePage />);
  await screen.findAllByTestId('pub-card').catch(() => []);
};

beforeEach(() => {
  vi.clearAllMocks();
  cleanup();
  searchParamsState.params = new URLSearchParams();
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({ publications: PUBLICATIONS });
  publicationServiceMock.getAcquiredApplications.mockResolvedValue({ applications: [] });
  publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [] });
});

describe('Marketplace Explore - sort', () => {
  it('defaults to best-rated first, with the unrated pushed to the end', async () => {
    await renderWith('');

    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['No Date', 'Top Rated', 'Most Installed', 'Fresh Free', 'Unrated Newcomer'],
    ));
  });

  it('the default rating sort is STABLE, so the unrated tail keeps the backend popularity order instead of being shuffled', async () => {
    orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
      publications: [
        { id: 'u1', title: 'Unrated A', displayMode: 'APPLICATION', publisherId: '9', reviewCount: 0, useCount: 0, creditsPerUse: 0 },
        { id: 'r1', title: 'Rated', displayMode: 'APPLICATION', publisherId: '9', averageRating: 4, reviewCount: 3, useCount: 0, creditsPerUse: 0 },
        { id: 'u2', title: 'Unrated B', displayMode: 'APPLICATION', publisherId: '9', reviewCount: 0, useCount: 0, creditsPerUse: 0 },
        { id: 'u3', title: 'Unrated C', displayMode: 'APPLICATION', publisherId: '9', reviewCount: 0, useCount: 0, creditsPerUse: 0 },
      ],
    });

    await renderWith('');

    // Rated first, then A/B/C in the exact order the server sent them.
    await waitFor(() => expect(titlesInOrder()).toEqual(['Rated', 'Unrated A', 'Unrated B', 'Unrated C']));
  });

  it('sort=popular hands the order back to the backend ranking (which knows the favorites the client never receives)', async () => {
    await renderWith('sort=popular');

    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['Fresh Free', 'Top Rated', 'Most Installed', 'Unrated Newcomer', 'No Date'],
    ));
  });

  it('sort=recent orders by publish date and sorts a publication with no date last, not first', async () => {
    await renderWith('sort=recent');

    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['Unrated Newcomer', 'Fresh Free', 'Top Rated', 'Most Installed', 'No Date'],
    ));
  });

  it('sort=installs orders by use count', async () => {
    await renderWith('sort=installs');

    await waitFor(() => expect(titlesInOrder()[0]).toBe('Most Installed'));
  });

  it('an unknown sort value falls back to the default (best rated) instead of emptying the grid', async () => {
    await renderWith('sort=bogus');

    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['No Date', 'Top Rated', 'Most Installed', 'Fresh Free', 'Unrated Newcomer'],
    ));
  });
});

describe('Marketplace Explore - rating filter', () => {
  it('rating=rating4 keeps only publications averaging 4 or more', async () => {
    await renderWith('rating=rating4');

    // Order is the default best-rated one, so the survivors come back by average.
    await waitFor(() => expect(titlesInOrder()).toEqual(['No Date', 'Top Rated', 'Most Installed']));
  });

  it('rating=rating3 widens to 3 and up', async () => {
    await renderWith('rating=rating3');

    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['No Date', 'Top Rated', 'Most Installed', 'Fresh Free'],
    ));
  });

  it('an unrated publication is excluded by every rating filter (no reviews is not a 0-star score)', async () => {
    await renderWith('rating=rated');

    await waitFor(() => expect(titlesInOrder()).not.toContain('Unrated Newcomer'));
    expect(titlesInOrder()).toHaveLength(4);
  });
});

describe('Marketplace Explore - date filter', () => {
  it('date=d7 keeps only the last week', async () => {
    await renderWith('date=d7');

    await waitFor(() => expect(titlesInOrder()).toEqual(['Fresh Free', 'Unrated Newcomer']));
  });

  it('date=d90 widens the window and still drops the undated publication', async () => {
    await renderWith('date=d90');

    await waitFor(() => expect(titlesInOrder()).toEqual(['Top Rated', 'Fresh Free', 'Unrated Newcomer']));
  });
});

describe('Marketplace Explore - price filter', () => {
  it('price=free keeps only the zero-credit publications', async () => {
    await renderWith('price=free');

    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['Most Installed', 'Fresh Free', 'Unrated Newcomer'],
    ));
  });

  it('price=paid keeps only the ones that cost credits', async () => {
    await renderWith('price=paid');

    await waitFor(() => expect(titlesInOrder()).toEqual(['No Date', 'Top Rated']));
  });
});

describe('Marketplace Explore - filters combine and can be undone', () => {
  it('rating and price intersect rather than replacing each other', async () => {
    await renderWith('rating=rating4&price=free');

    await waitFor(() => expect(titlesInOrder()).toEqual(['Most Installed']));
  });

  it('an over-narrow filter says so and offers a way back, instead of claiming nothing was published', async () => {
    await renderWith('rating=rating4&date=d7');

    await waitFor(() => expect(screen.getByText('noFilterResults')).toBeInTheDocument());
    expect(screen.queryByText('emptyApplications')).not.toBeInTheDocument();
    expect(screen.getByTestId('marketplace-reset-filters-empty')).toBeInTheDocument();
  });

  it('reset clears rating, date AND price in ONE navigation (three sequential setters would each rebuild from the same stale snapshot and only the last would survive)', async () => {
    await renderWith('rating=rating4&date=d7&price=free');
    await screen.findByTestId('marketplace-reset-filters-empty');

    fireEvent.click(screen.getByTestId('marketplace-reset-filters-empty'));

    expect(routerMock.replace).toHaveBeenCalledTimes(1);
    expect(routerMock.replace).toHaveBeenCalledWith('/app/marketplace', { scroll: false });
  });

  it('reset leaves unrelated params (tab, type, sort) untouched', async () => {
    await renderWith('type=apps&sort=recent&rating=rating4&date=d7');
    await screen.findByTestId('marketplace-reset-filters-empty');

    fireEvent.click(screen.getByTestId('marketplace-reset-filters-empty'));

    expect(routerMock.replace).toHaveBeenCalledWith('/app/marketplace?type=apps&sort=recent', { scroll: false });
  });
});
