import type { MetadataRoute } from 'next';
import { IS_CE } from '@/lib/edition';
import { COMPARISONS } from './compare/_lib/comparisons';
import { DOCS_PAGES } from './docs/_nav';
import { getAllPosts } from '@/lib/blog/posts';
import { blogHreflang } from '@/lib/blog/localized';
import { fetchAllPublicPublications } from '@/lib/marketplace/publicPublications';
import { isIndexable, marketplacePath } from '@/lib/marketplace/indexability';

// Configurable at deploy time; falls back to the production domain.
const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://trinyx.fr';

/**
 * Public-indexable surface of trinyx.fr (native Next.js sitemap).
 *
 * Included:
 *  - Landing `/` - ONE entry, the apex URL. The landing lives under
 *    `app/[locale]` but its content is hardcoded English on every locale
 *    (see the LandingShell contract), so the locale URLs are byte-identical
 *    duplicates: they canonicalize to the apex (app/[locale]/page.tsx) and are
 *    deliberately NOT listed here. Re-add per-locale entries WITH a reciprocal
 *    hreflang cluster only when the landing is actually translated.
 *  - `/compare/*` - the competitor comparison pages ("n8n alternative",
 *    "Zapier alternative", ...), enumerated from their content source so the
 *    sitemap never drifts from the live pages.
 *  - Marketing / legal sub-pages - these live OUTSIDE the `[locale]` tree and
 *    render at a single bare URL with runtime locale detection
 *    (i18n/resolveRequestLocale.ts), so they have no per-locale URL variants and
 *    therefore no hreflang alternates. `/changelog` is a live public nav entry
 *    (currently placeholder content) - kept at a modest priority.
 *  - Documentation - one entry per live docs page, enumerated from the docs IA
 *    (`app/docs/_nav.ts`) so the sitemap and the sidebar never drift apart.
 *  - Blog - the index and one entry per post, enumerated from the post registry
 *    (`lib/blog/posts.ts`). Unlike the other marketing pages the blog IS
 *    translated (en canonical at `/blog`, localized under `/<locale>/blog`), so
 *    each entry carries a reciprocal hreflang cluster (`blogHreflang`).
 *
 * Excluded (also disallowed in robots.ts):
 *  - Auth-gated app (`/app/*`), `/onboarding`, `/ce-setup`, `/workflows/*`,
 *    `/billing/*`, `/local-mcp`, and token URLs (`/f`, `/s`, `/w/embed`).
 *  - `/login` and `/register`: on the cloud deployment these immediately redirect
 *    to the external OIDC provider (see app/[locale]/login/page.tsx) - content-less
 *    shims with no indexable value.
 *
 * CE deployments emit an empty sitemap: robots.ts already disallows everything
 * for self-hosted editions, and the build cannot know the deployer's domain.
 */
/**
 * Rendered per request, NOT prerendered at build time.
 *
 * The rest of the sitemap is enumerated from in-repo content, but listings
 * appear whenever someone publishes, so it cannot be frozen at build. More
 * importantly, the gateway is unreachable from the CI builder: prerendering
 * bakes a sitemap with ZERO listings, and each frontend replica then serves that
 * copy until it revalidates on its own. Verified in production: the sitemap had
 * regenerated (a fresh lastmod) and still advertised no listings, because other
 * replicas were still answering from the build-time copy.
 *
 * The catalog walk keeps its own hourly cache window, so this is one gateway
 * read per hour per replica, not one per sitemap fetch.
 */
export const dynamic = 'force-dynamic';

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  if (IS_CE) {
    return [];
  }

  const now = new Date();

  // The landing page: one canonical URL. Locale variants serve identical
  // English content and canonicalize here (see the header comment).
  const landing: MetadataRoute.Sitemap = [
    { url: SITE_URL, lastModified: now, changeFrequency: 'weekly', priority: 1.0 },
  ];

  // Competitor comparison pages, enumerated from their single content source.
  const compare: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/compare`, lastModified: now, changeFrequency: 'monthly', priority: 0.7 },
    ...COMPARISONS.map((comparison) => ({
      url: `${SITE_URL}/compare/${comparison.slug}`,
      lastModified: now,
      changeFrequency: 'weekly' as const,
      priority: 0.8,
    })),
  ];

  // Non-localized public sub-pages (single URL, runtime locale detection).
  const pages: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/about`, lastModified: now, changeFrequency: 'monthly', priority: 0.6 },
    { url: `${SITE_URL}/contact`, lastModified: now, changeFrequency: 'monthly', priority: 0.6 },
    { url: `${SITE_URL}/changelog`, lastModified: now, changeFrequency: 'weekly', priority: 0.5 },
    { url: `${SITE_URL}/legal/privacy`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${SITE_URL}/legal/terms`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${SITE_URL}/legal/mentions`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
  ];

  // Documentation is canonical on the same public origin under /docs.
  const docs: MetadataRoute.Sitemap = DOCS_PAGES.map((page) => ({
    url: `${SITE_URL}${page.href}`,
    lastModified: now,
    changeFrequency: 'monthly',
    priority: page.href === '/docs' ? 0.6 : 0.5,
  }));

  // Blog: en canonical URLs, each with the full hreflang cluster so Google
  // discovers every translated version. Article lastModified = its publish date.
  const blog: MetadataRoute.Sitemap = [
    {
      url: `${SITE_URL}/blog`,
      lastModified: now,
      changeFrequency: 'weekly',
      priority: 0.7,
      alternates: { languages: blogHreflang(SITE_URL, '') },
    },
    ...getAllPosts().map((post) => ({
      url: `${SITE_URL}/blog/${post.slug}`,
      lastModified: new Date(`${post.date}T00:00:00Z`),
      changeFrequency: 'monthly' as const,
      priority: 0.7,
      alternates: { languages: blogHreflang(SITE_URL, `/${post.slug}`) },
    })),
  ];

  // Marketplace: the index plus every listing that passes the indexability gate.
  // The SAME predicate drives each page's robots meta, so the sitemap can never
  // advertise a URL that then tells the crawler not to index it.
  // Pass the sitemap's own window explicitly: Next takes the SHORTEST revalidate
  // among a route's fetches, so leaving the reader's 15 minute default here
  // would quietly override the hourly window declared above and rebuild the
  // whole catalog walk four times as often as intended.
  const { publications, truncated } = await fetchAllPublicPublications({ revalidateSeconds: 3600 });
  if (truncated) {
    // Never let a partial catalog look like a complete one.
    console.warn(
      `[sitemap] marketplace walk stopped early after ${publications.length} listings; `
      + 'the sitemap is incomplete (page cap reached or a gateway read failed).',
    );
  }
  const marketplace: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/marketplace`, lastModified: now, changeFrequency: 'daily', priority: 0.8 },
    ...publications.filter(isIndexable).map((publication) => ({
      url: `${SITE_URL}${marketplacePath(publication.publicSlug as string)}`,
      lastModified: publication.updatedAt ? new Date(publication.updatedAt) : now,
      changeFrequency: 'weekly' as const,
      priority: 0.6,
    })),
  ];

  return [...landing, ...compare, ...pages, ...docs, ...blog, ...marketplace];
}
