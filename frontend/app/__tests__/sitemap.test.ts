import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DOCS_PAGES } from '../docs/_nav';

const SITE = 'https://trinyx.fr';

/** A marketplace listing shaped like the public read path returns it. */
function listing(overrides: Record<string, unknown> = {}) {
  return {
    id: 'pub-1',
    publicSlug: 'invoice-bot',
    title: 'Invoice Bot',
    description: 'x'.repeat(200),
    publisherName: 'John Doe',
    publisherHandle: 'john-doe',
    publisherAvatarUrl: null,
    categorySlug: 'automation',
    categoryName: 'Automation',
    averageRating: 4.5,
    reviewCount: 12,
    useCount: 42,
    publishedAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-02T10:00:00Z',
    publicationType: 'WORKFLOW',
    ...overrides,
  };
}

/**
 * Stub the marketplace read path. The sitemap must never reach the network in a
 * unit test, and every test that does not care about listings gets an empty
 * catalog so the in-repo sections stay isolated.
 */
function mockMarketplace(publications: unknown[] = [], truncated = false) {
  vi.doMock('@/lib/marketplace/publicPublications', () => ({
    fetchAllPublicPublications: vi.fn().mockResolvedValue({ publications, truncated }),
  }));
}

describe('sitemap - cloud edition', () => {
  beforeEach(() => vi.resetModules());

  it('emits one entry per live docs page, with the Overview at a higher priority', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    const { default: sitemap } = await import('../sitemap');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    // Docs stay on the Trinyx origin under /docs; every IA page is included.
    for (const page of DOCS_PAGES) {
      expect(urls).toContain(`${SITE}${page.href}`);
    }
    // Overview is prioritised above its sub-pages.
    expect(entries.find((e) => e.url === `${SITE}/docs`)?.priority).toBe(0.6);
    expect(entries.find((e) => e.url === `${SITE}/docs/agents`)?.priority).toBe(0.5);
    // The apex landing root is still emitted alongside the docs.
    expect(urls).toContain(SITE);
  });

  it('emits the landing page ONCE at the apex (locale duplicates canonicalize there, not sitemap entries)', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    const { default: sitemap } = await import('../sitemap');
    const { routing } = await import('@/i18n/routing');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls).toContain(SITE);
    // The landing serves identical English content on every locale URL, so
    // listing /fr, /es, ... would advertise duplicates that canonicalize away.
    for (const locale of routing.locales) {
      expect(urls).not.toContain(`${SITE}/${locale}`);
    }
  });

  it('emits the /compare hub and one entry per comparison page', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    const { default: sitemap } = await import('../sitemap');
    const { COMPARISONS } = await import('../compare/_lib/comparisons');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/compare`);
    for (const comparison of COMPARISONS) {
      expect(urls).toContain(`${SITE}/compare/${comparison.slug}`);
    }
    // Comparison pages are a primary SEO surface: above sub-pages, below the landing.
    expect(entries.find((e) => e.url === `${SITE}/compare/n8n-alternative`)?.priority).toBe(0.8);
  });

  it('emits the blog index and one entry per post, each with a full hreflang cluster', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    const { default: sitemap } = await import('../sitemap');
    const { getAllPosts } = await import('@/lib/blog/posts');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    // The index plus every post (enumerated from the registry) is present.
    expect(urls).toContain(`${SITE}/blog`);
    const posts = getAllPosts();
    for (const post of posts) {
      expect(urls).toContain(`${SITE}/blog/${post.slug}`);
    }

    // Each blog entry carries a reciprocal hreflang cluster (x-default + en + 5 locales).
    const indexEntry = entries.find((e) => e.url === `${SITE}/blog`);
    expect(Object.keys(indexEntry?.alternates?.languages ?? {}).sort()).toEqual([
      'de', 'en', 'es', 'fr', 'pt', 'x-default', 'zh',
    ]);
    expect(indexEntry?.alternates?.languages?.fr).toBe(`${SITE}/fr/blog`);
    expect(indexEntry?.alternates?.languages?.['x-default']).toBe(`${SITE}/blog`);

    // An article's alternates point at the localized article URLs.
    const articleEntry = entries.find((e) => e.url === `${SITE}/blog/${posts[0].slug}`);
    expect(articleEntry?.alternates?.languages?.de).toBe(`${SITE}/de/blog/${posts[0].slug}`);
  });
});

describe('sitemap - marketplace listings', () => {
  beforeEach(() => vi.resetModules());

  it('emits the marketplace hub plus one entry per indexable listing', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing(), listing({ id: 'pub-2', publicSlug: 'expense-sorter' })]);
    const { default: sitemap } = await import('../sitemap');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/marketplace`);
    expect(urls).toContain(`${SITE}/marketplace/invoice-bot`);
    expect(urls).toContain(`${SITE}/marketplace/expense-sorter`);
  });

  it('uses the listing updatedAt as lastModified so crawlers see real freshness', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing()]);
    const { default: sitemap } = await import('../sitemap');
    const entry = (await sitemap()).find((e) => e.url === `${SITE}/marketplace/invoice-bot`);

    expect(entry?.lastModified).toEqual(new Date('2026-07-02T10:00:00Z'));
  });

  it('omits a listing whose description is too thin to index', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing({ publicSlug: 'thin-app', description: 'too short' })]);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // The sitemap and the page's robots meta read the SAME predicate. Listing a
    // noindex URL here would advertise a page that then refuses indexing.
    expect(urls).not.toContain(`${SITE}/marketplace/thin-app`);
  });

  it('omits a listing that has no slug yet', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing({ publicSlug: null })]);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls.some((url) => url.startsWith(`${SITE}/marketplace/`))).toBe(false);
  });

  it('still emits the in-repo sections when the marketplace read fails', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([], true);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // A gateway blip must not empty the sitemap of the landing, docs and blog.
    expect(urls).toContain(SITE);
    expect(urls).toContain(`${SITE}/blog`);
    expect(urls).toContain(`${SITE}/marketplace`);
  });
});

describe('sitemap - community edition', () => {
  beforeEach(() => vi.resetModules());

  it('is empty on a self-hosted edition (never indexed)', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
    mockMarketplace([listing()]);
    const { default: sitemap } = await import('../sitemap');

    // Even with a full catalog available, a self-hosted install advertises
    // nothing: robots.ts already disallows everything there.
    expect(await sitemap()).toEqual([]);
  });
});
