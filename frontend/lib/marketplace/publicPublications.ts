/**
 * Server-side reads of the public marketplace, for the crawlable pages.
 *
 * <p>This is the first server-component data path in the app that talks to our
 * OWN backend (the only prior server fetch, `lib/changelog/githubReleases.ts`,
 * targets GitHub), so it deliberately mirrors that module's shape: a pure
 * mapper that can be unit-tested without I/O, plus a thin fetch wrapper that
 * degrades to an empty result instead of taking a public page down.
 *
 * Three deliberate choices, each of which has a wrong-looking easy alternative:
 *
 * 1. It calls the gateway DIRECTLY, not `/api/proxy/*`. Going through the proxy
 *    from the server would be an HTTP hop to ourselves, plus the CORS and token
 *    rewriting in `proxy.ts` that a server render has no use for.
 * 2. It does NOT use `lib/api/api-client`. That client is browser-bound: its
 *    base URL is the relative `/api/proxy` (which `fetch` cannot resolve without
 *    an origin), it sends `credentials: 'include'`, it throws when no auth token
 *    is installed, and it is a `globalThis` singleton, so giving it a token on
 *    the server would share that token across concurrent requests.
 * 3. It reads `GATEWAY_SERVICE_URL` first. `NEXT_PUBLIC_*` variables are inlined
 *    at BUILD time; the non-public one is injected into the pod at runtime
 *    (helm `commonEnv`), so it stays correct if the cluster's service DNS
 *    changes without a rebuild.
 *
 * These endpoints are anonymous by design at the gateway, so no credentials are
 * ever attached here. Any response that would require a user context must NOT
 * be fetched through this module.
 */
import 'server-only';

/** How long a public marketplace page may serve stale data, in seconds. */
export const PUBLIC_MARKETPLACE_REVALIDATE_SECONDS = 900;

/** A marketplace listing as the public pages need it. */
export interface PublicPublicationSummary {
  id: string;
  /** URL slug backing /marketplace/{slug}. Null on rows predating the backfill. */
  publicSlug: string | null;
  title: string;
  description: string;
  publisherName: string | null;
  /** Author @handle, or null when the publisher has no public profile. */
  publisherHandle: string | null;
  publisherAvatarUrl: string | null;
  categorySlug: string | null;
  categoryName: string | null;
  averageRating: number;
  reviewCount: number;
  useCount: number;
  publishedAt: string | null;
  updatedAt: string | null;
  publicationType: string;
}

/**
 * Base URL of the gateway as seen from the Next.js server process.
 * Never falls back to a public origin: a public URL here would send server
 * renders back out through the internet (and through Cloudflare) instead of
 * straight to the in-cluster service.
 */
export function gatewayBaseUrl(): string {
  return (
    process.env.GATEWAY_SERVICE_URL ||
    process.env.NEXT_PUBLIC_SPRING_BASE_URL ||
    'http://localhost:8080'
  );
}

/**
 * Slug shape produced by the backend generator: lowercase alphanumerics joined
 * by single hyphens, capped at the column width.
 *
 * Validated BEFORE any fetch. `/marketplace/{slug}` is a dynamic route, so every
 * distinct URL a scanner invents would otherwise become one gateway request
 * from the SSR pod, all sharing a single anonymous rate-limit bucket. Rejecting
 * junk locally turns a URL scan into cheap local 404s instead of load on the
 * gateway (and, at volume, 429s that would make legitimate pages render empty).
 */
const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const SLUG_MAX_LENGTH = 120;

export function isValidSlugFormat(slug: string): boolean {
  if (typeof slug !== 'string') return false;
  if (slug.length === 0 || slug.length > SLUG_MAX_LENGTH) return false;
  return SLUG_PATTERN.test(slug);
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

function asNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/**
 * Map one raw publication object from the backend into a view model.
 *
 * Defensive on purpose: the public pages render whatever the marketplace
 * happens to contain, including rows written before any given field existed.
 * A row without an id or a title is unusable for a page and is dropped by
 * {@link mapPublications} rather than rendered half-empty.
 */
export function mapPublication(raw: unknown): PublicPublicationSummary | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;

  const id = asString(row.id);
  const title = asString(row.title);
  if (!id || !title) return null;

  const category = (typeof row.category === 'object' && row.category !== null
    ? (row.category as Record<string, unknown>)
    : {}) as Record<string, unknown>;

  return {
    id,
    publicSlug: asString(row.publicSlug),
    title,
    description: asString(row.description) ?? '',
    publisherName: asString(row.publisherName),
    publisherHandle: asString(row.publisherHandle),
    publisherAvatarUrl: asString(row.publisherAvatarUrl),
    categorySlug: asString(category.slug),
    categoryName: asString(category.name),
    averageRating: asNumber(row.averageRating),
    reviewCount: asNumber(row.reviewCount),
    useCount: asNumber(row.useCount),
    publishedAt: asString(row.publishedAt),
    updatedAt: asString(row.updatedAt),
    publicationType: asString(row.publicationType) ?? 'WORKFLOW',
  };
}

/**
 * Map a marketplace list payload. A non-array `publications` field (or a
 * non-object payload) yields an empty list so a backend shape change degrades
 * to an empty page rather than a 500.
 */
export function mapPublications(payload: unknown): PublicPublicationSummary[] {
  if (typeof payload !== 'object' || payload === null) return [];
  const list = (payload as Record<string, unknown>).publications;
  if (!Array.isArray(list)) return [];
  return list
    .map(mapPublication)
    .filter((item): item is PublicPublicationSummary => item !== null);
}

async function getJson(path: string, revalidateSeconds: number): Promise<unknown | null> {
  try {
    const res = await fetch(`${gatewayBaseUrl()}${path}`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: revalidateSeconds },
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    // A public page must not fail because the gateway blipped: callers render
    // an empty state (or notFound()) instead.
    return null;
  }
}

/**
 * One page of the public marketplace listing, newest first.
 * Returns an empty list on any failure.
 */
export async function fetchMarketplacePage(
  page = 0,
  size = 24,
  revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
): Promise<PublicPublicationSummary[]> {
  const payload = await getJson(
    `/api/publications/marketplace?page=${page}&size=${size}`,
    revalidateSeconds,
  );
  return mapPublications(payload);
}

/**
 * Every listing the sitemap may advertise, walked page by page.
 *
 * Bounded on purpose. `maxPages` caps the work a single sitemap render can do,
 * and reaching that cap is reported by the caller rather than silently
 * truncating: a sitemap that quietly drops half the catalog looks healthy while
 * hiding pages from search engines. The walk also stops as soon as a page comes
 * back short, which is the normal end of the catalog.
 */
export async function fetchAllPublicPublications(
  { pageSize = 100, maxPages = 50, revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS } = {},
): Promise<{ publications: PublicPublicationSummary[]; truncated: boolean }> {
  const all: PublicPublicationSummary[] = [];

  for (let page = 0; page < maxPages; page++) {
    const payload = await getJson(
      `/api/publications/marketplace?page=${page}&size=${pageSize}`,
      revalidateSeconds,
    );
    // A failed page ends the walk: continuing would silently produce a sitemap
    // with a hole in the middle of the catalog.
    if (payload === null) return { publications: all, truncated: true };

    const batch = mapPublications(payload);
    all.push(...batch);

    const rawCount = Array.isArray((payload as Record<string, unknown>).publications)
      ? ((payload as Record<string, unknown>).publications as unknown[]).length
      : 0;
    if (rawCount < pageSize) return { publications: all, truncated: false };
  }

  return { publications: all, truncated: true };
}

/**
 * A single publication addressed by its URL slug, or null when the slug is
 * unknown or the publication is not anonymously readable (the backend answers
 * 404 for both, deliberately indistinguishably). Callers should map null to
 * `notFound()`.
 */
export async function fetchPublicationBySlug(
  slug: string,
  revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
): Promise<PublicPublicationSummary | null> {
  if (!isValidSlugFormat(slug)) return null;
  const payload = await getJson(
    `/api/publications/by-slug/${encodeURIComponent(slug)}`,
    revalidateSeconds,
  );
  return mapPublication(payload);
}
