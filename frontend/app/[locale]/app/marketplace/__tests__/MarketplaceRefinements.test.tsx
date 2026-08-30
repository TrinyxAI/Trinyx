// @vitest-environment jsdom
/**
 * Explore refinements: type + sort + rating / date / price, and the paging that
 * comes with them.
 *
 * They used to run CLIENT-side over one popularity-ordered `page=0&size=50`
 * fetch, which quietly redefined every one of them as "...among the 50 most
 * popular publications". With 76 public publications, 26 could be reached by no
 * combination of clicks; a just-published app - no installs, no favorites, no
 * reviews, so last in popularity order - was exactly what fell off the end, and
 * "sort by recent" could not bring it back because it only re-sorted the page
 * already held. Only the search box hit a different endpoint, which is why
 * searching found apps the grid swore did not exist.
 *
 * So the contract under test is no longer "which cards does this component keep"
 * but "does each control reach the SERVER, and does the grid render what comes
 * back". The mock below therefore behaves like the backend: it filters, sorts
 * and pages the fixture according to the params it is handed. A control wired to
 * the wrong param, or not wired at all, produces the wrong set and fails here.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { render, screen, cleanup, waitFor, fireEvent, act } from '@testing-library/react';

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

/**
 * A refinement is a change of ADDRESS on the page already on screen, so it goes through the
 * history API rather than the router: returning a select to its fallback removes the last
 * query param, and a router replace of the bare pathname is dropped when the page was loaded
 * at it - so on a page opened directly on `?type=agents`, clearing the filter did nothing.
 */
const historyReplace = vi.fn();
const realReplaceState = window.history.replaceState;
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

type Pub = {
  id: string;
  title: string;
  displayMode: string;
  publisherId: string;
  averageRating?: number;
  reviewCount: number;
  useCount: number;
  creditsPerUse: number;
  publishedAt?: string;
};

/**
 * One fixture per axis under test.
 *
 * Declaration order is the backend's popularity order and is deliberately NOT
 * any of the sortable orders, so a passing "popular keeps the server order"
 * assertion cannot be an accident of the fixtures already being sorted.
 */
const PUBLICATIONS: Pub[] = [
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
    id: 'p-agent', title: 'An Agent', displayMode: 'AGENT', publisherId: '9',
    averageRating: 5, reviewCount: 1, useCount: 0, creditsPerUse: 25, publishedAt: daysAgo(10),
  },
];

/** Backend `rating` param to the predicate it stands for. */
const RATING_PREDICATES: Record<string, (p: Pub) => boolean> = {
  any: () => true,
  // Unrated has no average to compare, so it fails every constraint rather than
  // passing as a silent 0 - the same rule the SQL applies.
  rated: (p) => p.reviewCount > 0,
  min_3: (p) => p.reviewCount > 0 && (p.averageRating ?? 0) >= 3,
  min_4: (p) => p.reviewCount > 0 && (p.averageRating ?? 0) >= 4,
};

/** Stand-in for the marketplace endpoint: filters, sorts and pages like it does. */
function fakeMarketplace(
  page = 0,
  size = 24,
  _category?: string,
  refinements?: { displayMode?: string; sort?: string; rating?: string; days?: number; price?: string },
) {
  const r = refinements ?? {};
  let rows = PUBLICATIONS.filter((p) => {
    if (r.displayMode && p.displayMode !== r.displayMode) return false;
    if (!(RATING_PREDICATES[r.rating ?? 'any'] ?? RATING_PREDICATES.any)(p)) return false;
    if (r.days != null) {
      const at = p.publishedAt ? Date.parse(p.publishedAt) : NaN;
      if (Number.isNaN(at) || at < Date.now() - r.days * 24 * 60 * 60 * 1000) return false;
    }
    if (r.price === 'free' && p.creditsPerUse > 0) return false;
    if (r.price === 'paid' && p.creditsPerUse <= 0) return false;
    return true;
  });

  if (r.sort === 'recent') {
    rows = [...rows].sort((a, b) => Date.parse(b.publishedAt ?? '') - Date.parse(a.publishedAt ?? ''));
  } else if (r.sort === 'installs') {
    rows = [...rows].sort((a, b) => b.useCount - a.useCount);
  } else if (r.sort === 'rating') {
    rows = [...rows].sort((a, b) => {
      const ra = a.reviewCount > 0 ? (a.averageRating ?? 0) : -1;
      const rb = b.reviewCount > 0 ? (b.averageRating ?? 0) : -1;
      return rb - ra;
    });
  }
  // 'popular' (and anything unrecognised) keeps the declaration order.

  const start = page * size;
  return Promise.resolve({
    publications: rows.slice(start, start + size),
    count: rows.length,
    page,
    size,
    totalPages: Math.max(1, Math.ceil(rows.length / size)),
  });
}

const titlesInOrder = () => screen.getAllByTestId('pub-card').map((el) => el.textContent);

/** The refinements sent with the most recent grid fetch. */
const lastRefinements = () => {
  const calls = orchestratorApiMock.getMarketplacePublications.mock.calls;
  return calls[calls.length - 1]?.[3];
};

const renderWith = async (query: string) => {
  searchParamsState.params = new URLSearchParams(query);
  render(<MarketplacePage />);
  await waitFor(() => expect(orchestratorApiMock.getMarketplacePublications).toHaveBeenCalled());
};

afterEach(() => { window.history.replaceState = realReplaceState; });

beforeEach(() => {
  historyReplace.mockClear();
  window.history.replaceState = ((_d: unknown, _u: string, url?: string) =>
    historyReplace(url)) as unknown as typeof window.history.replaceState;
  vi.clearAllMocks();
  cleanup();
  searchParamsState.params = new URLSearchParams();
  orchestratorApiMock.getMarketplacePublications.mockImplementation(fakeMarketplace);
  publicationServiceMock.getAcquiredApplications.mockResolvedValue({ applications: [] });
  publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [] });
});

describe('Marketplace Explore - the type filter is a query, not a client-side pass', () => {
  it('asks the server for applications by default, and the agent never has to be filtered out here', async () => {
    await renderWith('');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ displayMode: 'APPLICATION' }));
    await waitFor(() => expect(titlesInOrder()).not.toContain('An Agent'));
  });

  it('?type=agents asks the server for AGENT, so the agents grid is the catalogue and not one page of it', async () => {
    await renderWith('type=agents');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ displayMode: 'AGENT' }));
    await waitFor(() => expect(titlesInOrder()).toEqual(['An Agent']));
  });
});

describe('Marketplace Explore - sort', () => {
  it('defaults to the backend popularity ranking (which knows the favorites the client never receives)', async () => {
    await renderWith('');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ sort: 'popular' }));
    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['Fresh Free', 'Top Rated', 'Most Installed', 'Unrated Newcomer'],
    ));
  });

  it('sort=recent is answered by the server, so the newest publication is reachable however unpopular it is', async () => {
    // The reported bug in one assertion: "Unrated Newcomer" has no installs, no
    // favorites and no reviews, so it ranks last by popularity. While sorting
    // happened in the browser it could only ever be re-sorted INSIDE the page
    // already fetched, and a page it never made it into could not put it first.
    await renderWith('sort=recent');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ sort: 'recent' }));
    await waitFor(() => expect(titlesInOrder()[0]).toBe('Unrated Newcomer'));
  });

  it('sort=installs is answered by the server', async () => {
    await renderWith('sort=installs');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ sort: 'installs' }));
    await waitFor(() => expect(titlesInOrder()[0]).toBe('Most Installed'));
  });

  it('sort=rating is answered by the server, unrated last', async () => {
    await renderWith('sort=rating');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ sort: 'rating' }));
    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['Top Rated', 'Most Installed', 'Fresh Free', 'Unrated Newcomer'],
    ));
  });

  it('an unknown sort value falls back to the default instead of emptying the grid', async () => {
    await renderWith('sort=bogus');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ sort: 'popular' }));
    await waitFor(() => expect(titlesInOrder()).toHaveLength(4));
  });
});

describe('Marketplace Explore - rating filter', () => {
  it('rating=rating4 asks the server for a 4-and-up floor', async () => {
    await renderWith('rating=rating4');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ rating: 'min_4' }));
    await waitFor(() => expect(titlesInOrder()).toEqual(['Top Rated', 'Most Installed']));
  });

  it('rating=rating3 widens to 3 and up', async () => {
    await renderWith('rating=rating3');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ rating: 'min_3' }));
    await waitFor(() => expect(titlesInOrder()).toEqual(['Fresh Free', 'Top Rated', 'Most Installed']));
  });

  it('an unrated publication is excluded by every rating filter (no reviews is not a 0-star score)', async () => {
    await renderWith('rating=rated');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ rating: 'rated' }));
    await waitFor(() => expect(titlesInOrder()).not.toContain('Unrated Newcomer'));
  });
});

describe('Marketplace Explore - date filter', () => {
  it('date=d7 sends a 7-day window and finds the app published this week', async () => {
    // Pre-fix this was the loudest symptom: the window was applied to a
    // popularity-ordered page, so it returned nothing on the very day something
    // was published.
    await renderWith('date=d7');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ days: 7 }));
    await waitFor(() => expect(titlesInOrder()).toEqual(['Fresh Free', 'Unrated Newcomer']));
  });

  it('date=d90 widens the window', async () => {
    await renderWith('date=d90');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ days: 90 }));
    await waitFor(() => expect(titlesInOrder()).toEqual(['Fresh Free', 'Top Rated', 'Unrated Newcomer']));
  });

  it('date=any sends no window at all', async () => {
    await renderWith('');

    await waitFor(() => expect(lastRefinements()?.days).toBeUndefined());
  });
});

describe('Marketplace Explore - price filter', () => {
  it('price=free asks the server for the zero-credit publications', async () => {
    await renderWith('price=free');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ price: 'free' }));
    await waitFor(() => expect(titlesInOrder()).toEqual(
      ['Fresh Free', 'Most Installed', 'Unrated Newcomer'],
    ));
  });

  it('price=paid asks the server for the ones that cost credits', async () => {
    await renderWith('price=paid');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ price: 'paid' }));
    await waitFor(() => expect(titlesInOrder()).toEqual(['Top Rated']));
  });
});

describe('Marketplace Explore - filters combine and can be undone', () => {
  it('rating and price travel together in one query rather than replacing each other', async () => {
    await renderWith('rating=rating4&price=free');

    await waitFor(() => expect(lastRefinements()).toMatchObject({ rating: 'min_4', price: 'free' }));
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

    expect(historyReplace).toHaveBeenCalledTimes(1);
    expect(historyReplace).toHaveBeenCalledWith('/app/marketplace');
  });

  it('reset leaves unrelated params (tab, type, sort) untouched', async () => {
    await renderWith('type=apps&sort=recent&rating=rating4&date=d7');
    await screen.findByTestId('marketplace-reset-filters-empty');

    fireEvent.click(screen.getByTestId('marketplace-reset-filters-empty'));

    expect(historyReplace).toHaveBeenCalledWith('/app/marketplace?type=apps&sort=recent');
  });
});

describe('Marketplace Explore - the grid is paged, so the catalogue does not end at the first screen', () => {
  /** Enough applications to force more than one page at any sane page size. */
  const MANY = Array.from({ length: 30 }, (_, i) => ({
    id: `p-${i}`,
    title: `App ${i}`,
    displayMode: 'APPLICATION',
    publisherId: '9',
    averageRating: 0,
    reviewCount: 0,
    useCount: 0,
    creditsPerUse: 0,
    publishedAt: daysAgo(i + 1),
  }));

  beforeEach(() => {
    orchestratorApiMock.getMarketplacePublications.mockImplementation((page = 0, size = 24) =>
      Promise.resolve({
        publications: MANY.slice(page * size, page * size + size),
        count: MANY.length,
        page,
        size,
        totalPages: Math.ceil(MANY.length / size),
      }));
  });

  it('offers a next page while the server says there are more matches than are on screen', async () => {
    await renderWith('');

    // The old grid stopped at whatever one fetch returned and said nothing about
    // the rest, so a publication past it was simply unreachable.
    await waitFor(() => expect(screen.getByTestId('marketplace-load-more')).toBeInTheDocument());
    expect(screen.getAllByTestId('pub-card')).toHaveLength(24);
  });

  it('appends the next page instead of replacing the grid, and stops offering more at the end', async () => {
    await renderWith('');
    await screen.findByTestId('marketplace-load-more');

    fireEvent.click(screen.getByTestId('marketplace-load-more'));

    await waitFor(() => expect(screen.getAllByTestId('pub-card')).toHaveLength(30));
    // Page 0 is still there: this extends the grid, it does not swap it.
    expect(titlesInOrder()[0]).toBe('App 0');
    expect(titlesInOrder()[29]).toBe('App 29');
    await waitFor(() =>
      expect(screen.queryByTestId('marketplace-load-more')).not.toBeInTheDocument());
  });

  it('ignores a superseded page: a "load more" that lands after a new query must not append', async () => {
    // Two requests in flight, the older one resolving LAST. Without a guard its
    // rows are appended to a grid that is now showing something else entirely,
    // which reads as the search having silently failed.
    let releasePageOne: (() => void) | null = null;
    orchestratorApiMock.getMarketplacePublications.mockImplementation((page = 0, size = 24) => {
      if (page === 1) {
        return new Promise((resolve) => {
          releasePageOne = () => resolve({
            publications: MANY.slice(24, 30), count: MANY.length, page: 1, size, totalPages: 2,
          });
        });
      }
      return Promise.resolve({
        publications: MANY.slice(0, size), count: MANY.length, page: 0, size, totalPages: 2,
      });
    });
    orchestratorApiMock.searchPublications.mockResolvedValue({
      publications: MANY.slice(0, 3), count: 3,
    });

    await renderWith('');
    await screen.findByTestId('marketplace-load-more');
    fireEvent.click(screen.getByTestId('marketplace-load-more'));

    // A query is typed while page 1 is still in flight.
    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'app 0' } });
    await waitFor(() => expect(screen.getAllByTestId('pub-card')).toHaveLength(3));

    await act(async () => { releasePageOne?.(); });

    // Still the three search hits, not thirty-three rows of two different queries.
    expect(screen.getAllByTestId('pub-card')).toHaveLength(3);
  });

  it('asks for the SECOND page, not the first one again', async () => {
    await renderWith('');
    await screen.findByTestId('marketplace-load-more');

    fireEvent.click(screen.getByTestId('marketplace-load-more'));

    await waitFor(() => {
      const calls = orchestratorApiMock.getMarketplacePublications.mock.calls;
      expect(calls[calls.length - 1][0]).toBe(1);
    });
  });
});
