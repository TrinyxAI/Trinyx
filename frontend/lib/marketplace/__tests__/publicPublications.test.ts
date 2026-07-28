import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// `server-only` throws outside a React Server Component; stub it for the unit
// test (same approach as i18n/__tests__/resolveRequestLocale.test.ts).
vi.mock('server-only', () => ({}));

import {
  fetchAllPublicPublications,
  PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
  fetchMarketplacePage,
  fetchPublicationBySlug,
  gatewayBaseUrl,
  mapPublication,
  mapPublications,
} from '../publicPublications';

const ORIGINAL_ENV = { ...process.env };

function restoreEnv() {
  process.env = { ...ORIGINAL_ENV };
}

/** Minimal backend row: everything the mapper treats as required. */
function rawRow(overrides: Record<string, unknown> = {}) {
  return {
    id: '0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8a9b',
    title: 'Invoice Bot',
    description: 'Chases unpaid invoices.',
    publicSlug: 'invoice-bot',
    publisherName: 'John Doe',
    publisherHandle: 'john-doe',
    publisherAvatarUrl: 'avatar-uuid',
    category: { slug: 'automation', name: 'Automation' },
    averageRating: 4.5,
    reviewCount: 12,
    useCount: 42,
    publishedAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-02T10:00:00Z',
    publicationType: 'WORKFLOW',
    ...overrides,
  };
}

describe('gatewayBaseUrl', () => {
  afterEach(restoreEnv);

  it('prefers the runtime-injected GATEWAY_SERVICE_URL', () => {
    // NEXT_PUBLIC_* is inlined at build time; the non-public var is injected
    // into the pod at runtime and must win so a service-DNS change needs no
    // rebuild.
    process.env.GATEWAY_SERVICE_URL = 'http://in-cluster-gateway:8080';
    process.env.NEXT_PUBLIC_SPRING_BASE_URL = 'http://baked-at-build:8080';

    expect(gatewayBaseUrl()).toBe('http://in-cluster-gateway:8080');
  });

  it('falls back to NEXT_PUBLIC_SPRING_BASE_URL when the runtime var is absent', () => {
    delete process.env.GATEWAY_SERVICE_URL;
    process.env.NEXT_PUBLIC_SPRING_BASE_URL = 'http://baked-at-build:8080';

    expect(gatewayBaseUrl()).toBe('http://baked-at-build:8080');
  });

  it('falls back to localhost when neither is set', () => {
    delete process.env.GATEWAY_SERVICE_URL;
    delete process.env.NEXT_PUBLIC_SPRING_BASE_URL;

    expect(gatewayBaseUrl()).toBe('http://localhost:8080');
  });

  it('ignores an empty variable rather than producing a URL with no host', () => {
    process.env.GATEWAY_SERVICE_URL = '';
    process.env.NEXT_PUBLIC_SPRING_BASE_URL = 'http://baked-at-build:8080';

    expect(gatewayBaseUrl()).toBe('http://baked-at-build:8080');
  });
});

describe('mapPublication', () => {
  it('maps a complete row', () => {
    const item = mapPublication(rawRow());

    expect(item).toEqual({
      id: '0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8a9b',
      publicSlug: 'invoice-bot',
      title: 'Invoice Bot',
      description: 'Chases unpaid invoices.',
      publisherName: 'John Doe',
      publisherHandle: 'john-doe',
      publisherAvatarUrl: 'avatar-uuid',
      categorySlug: 'automation',
      categoryName: 'Automation',
      averageRating: 4.5,
      reviewCount: 12,
      useCount: 42,
      publishedAt: '2026-07-01T10:00:00Z',
      updatedAt: '2026-07-02T10:00:00Z',
      publicationType: 'WORKFLOW',
    });
  });

  it('drops a row with no id: it cannot be addressed by a page', () => {
    expect(mapPublication(rawRow({ id: undefined }))).toBeNull();
  });

  it('drops a row with no title: an indexed page needs one', () => {
    expect(mapPublication(rawRow({ title: '' }))).toBeNull();
  });

  it('keeps a row whose slug is still null (predates the backfill)', () => {
    // Such a row is reachable by UUID and must not vanish from the listing.
    const item = mapPublication(rawRow({ publicSlug: null }));

    expect(item?.publicSlug).toBeNull();
    expect(item?.id).toBeTruthy();
  });

  it('treats a missing publisher handle as absent, never as the string "null"', () => {
    // The handle goes into a /u/{handle} URL, so a stringified null would
    // produce a link to /u/null.
    const item = mapPublication(rawRow({ publisherHandle: null }));

    expect(item?.publisherHandle).toBeNull();
  });

  it('survives a row with no category object', () => {
    const item = mapPublication(rawRow({ category: null }));

    expect(item?.categorySlug).toBeNull();
    expect(item?.categoryName).toBeNull();
  });

  it('coerces non-numeric metrics to 0 rather than emitting NaN into the markup', () => {
    const item = mapPublication(rawRow({ averageRating: null, reviewCount: 'x', useCount: undefined }));

    expect(item?.averageRating).toBe(0);
    expect(item?.reviewCount).toBe(0);
    expect(item?.useCount).toBe(0);
  });

  it('defaults an absent publicationType to WORKFLOW', () => {
    expect(mapPublication(rawRow({ publicationType: undefined }))?.publicationType).toBe('WORKFLOW');
  });

  it.each([null, undefined, 'a string', 42, []])('returns null for a non-object payload (%s)', (input) => {
    expect(mapPublication(input)).toBeNull();
  });
});

describe('mapPublications', () => {
  it('maps the publications array', () => {
    const items = mapPublications({ publications: [rawRow(), rawRow({ id: 'other-id' })] });

    expect(items).toHaveLength(2);
  });

  it('skips unusable rows but keeps the rest', () => {
    const items = mapPublications({ publications: [rawRow(), { title: 'no id' }, rawRow({ id: 'x' })] });

    expect(items).toHaveLength(2);
  });

  it.each([
    ['a non-array publications field', { publications: 'nope' }],
    ['a missing publications field', { count: 0 }],
    ['a non-object payload', 'nope'],
    ['null', null],
  ])('returns an empty list for %s', (_label, payload) => {
    // A backend shape change must degrade to an empty page, not a 500 on a
    // public URL.
    expect(mapPublications(payload)).toEqual([]);
  });
});

describe('fetchMarketplacePage', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  it('calls the gateway directly, with ISR caching and no credentials', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ publications: [rawRow()] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const items = await fetchMarketplacePage();

    expect(items).toHaveLength(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://gw:8080/api/publications/marketplace?page=0&size=24');
    // Never through the Next proxy: that would be an HTTP hop to ourselves.
    expect(url).not.toContain('/api/proxy');
    expect(init.next).toEqual({ revalidate: PUBLIC_MARKETPLACE_REVALIDATE_SECONDS });
    // These endpoints are anonymous by design; sending credentials from a
    // shared server process would be both useless and a cross-request hazard.
    expect(init.headers).toEqual({ Accept: 'application/json' });
    expect(init.credentials).toBeUndefined();
  });

  it('forwards pagination and the caller revalidate window', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ publications: [] }) });
    vi.stubGlobal('fetch', fetchMock);

    await fetchMarketplacePage(3, 10, 60);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain('page=3&size=10');
    expect(init.next).toEqual({ revalidate: 60 });
  });

  it('returns an empty list on a non-200 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503, json: async () => ({}) }));

    await expect(fetchMarketplacePage()).resolves.toEqual([]);
  });

  it('returns an empty list when the gateway is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    // The public marketplace page must still render its empty state.
    await expect(fetchMarketplacePage()).resolves.toEqual([]);
  });

  it('returns an empty list when the body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => {
        throw new SyntaxError('Unexpected token <');
      },
    }));

    await expect(fetchMarketplacePage()).resolves.toEqual([]);
  });
});

describe('fetchAllPublicPublications', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  it('walks pages until a short page ends the catalog', async () => {
    const full = Array.from({ length: 100 }, (_, i) => rawRow({ id: `pub-${i}` }));
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ publications: full }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ publications: [rawRow({ id: 'last' })] }) });
    vi.stubGlobal('fetch', fetchMock);

    const { publications, truncated } = await fetchAllPublicPublications();

    expect(publications).toHaveLength(101);
    expect(truncated).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('stops after a single short page without a second request', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ publications: [rawRow()] }) });
    vi.stubGlobal('fetch', fetchMock);

    const { publications, truncated } = await fetchAllPublicPublications();

    expect(publications).toHaveLength(1);
    expect(truncated).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('reports truncation when the page cap is reached', async () => {
    // Every page comes back full, so the catalog never signals its end.
    const full = Array.from({ length: 10 }, (_, i) => rawRow({ id: `pub-${i}` }));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ publications: full }) }));

    const { publications, truncated } = await fetchAllPublicPublications({ pageSize: 10, maxPages: 3 });

    // Truncation MUST be visible: a partial sitemap that looks complete hides
    // pages from search engines with no signal at all.
    expect(publications).toHaveLength(30);
    expect(truncated).toBe(true);
  });

  it('reports truncation and keeps what it has when a page read fails', async () => {
    const full = Array.from({ length: 100 }, (_, i) => rawRow({ id: `pub-${i}` }));
    vi.stubGlobal('fetch', vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ publications: full }) })
      .mockResolvedValueOnce({ ok: false, status: 502, json: async () => ({}) }));

    const { publications, truncated } = await fetchAllPublicPublications();

    // Continuing past a failed page would leave a hole in the middle of the
    // catalog while still reporting success.
    expect(publications).toHaveLength(100);
    expect(truncated).toBe(true);
  });

  it('returns an empty, truncated result when the very first page fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    await expect(fetchAllPublicPublications()).resolves.toEqual({ publications: [], truncated: true });
  });
});

describe('fetchPublicationBySlug', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  it('requests the by-slug endpoint and maps the detail payload', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => rawRow() });
    vi.stubGlobal('fetch', fetchMock);

    const item = await fetchPublicationBySlug('invoice-bot');

    expect(item?.title).toBe('Invoice Bot');
    expect(fetchMock.mock.calls[0][0]).toBe('http://gw:8080/api/publications/by-slug/invoice-bot');
  });

  it.each([
    ['a path traversal attempt', '../../internal/secrets'],
    ['an uppercase slug', 'Invoice-Bot'],
    ['a slug with a slash', 'invoice/bot'],
    ['a slug with a query string', 'invoice-bot?x=1'],
    ['a double hyphen', 'invoice--bot'],
    ['a leading hyphen', '-invoice-bot'],
    ['a trailing hyphen', 'invoice-bot-'],
    ['an over-long slug', `${'a'.repeat(121)}`],
    ['an empty slug', ''],
    ['a whitespace slug', '   '],
  ])('rejects %s locally, without a gateway request', async (_label, slug) => {
    // /marketplace/{slug} is a dynamic route: every URL a scanner invents would
    // otherwise become one gateway call from the SSR pod, all sharing a single
    // anonymous rate-limit bucket.
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPublicationBySlug(slug)).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('accepts a well-formed slug at the length ceiling', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => rawRow() });
    vi.stubGlobal('fetch', fetchMock);

    await fetchPublicationBySlug('a'.repeat(120));

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('returns null for a 404 (unknown slug and non-public publication alike)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }));

    await expect(fetchPublicationBySlug('nope')).resolves.toBeNull();
  });

  it.each(['', '   '])('returns null for a blank slug without calling the gateway (%s)', async (slug) => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPublicationBySlug(slug)).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
