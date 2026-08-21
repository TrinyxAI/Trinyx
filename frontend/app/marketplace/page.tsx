import type { Metadata } from 'next';
import JsonLd from '@/components/seo/JsonLd';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';
import { fetchMarketplacePage } from '@/lib/marketplace/publicPublications';
import { marketplacePath } from '@/lib/marketplace/indexability';
import PublicationCardSsr from './_components/PublicationCardSsr';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://trinyx.fr';

/**
 * Public marketplace index: the crawlable entry point into the listing tree.
 *
 * Server-rendered with ISR rather than static: the catalog changes whenever
 * anyone publishes, and a fully static page would serve whatever existed at
 * build time until the next deploy.
 */
/**
 * Rendered per request, NOT prerendered at build time.
 *
 * Observed in production: with `revalidate` this page is baked at build, where
 * the gateway is unreachable from the CI builder. The reader fails soft to an
 * empty list, so the EMPTY page is what gets frozen into the prerender, and
 * every frontend replica serves that until it individually revalidates. Sampling
 * the live site returned 0 or 24 cards depending on which replica answered.
 * A crawler landing on the wrong replica sees an empty marketplace.
 *
 * The upstream fetch keeps its own cache window (`next: { revalidate }` in the
 * reader), so this costs one gateway call per window per replica, not one per
 * page view.
 */
export const dynamic = 'force-dynamic';

const TITLE = 'Marketplace - ready-made AI automations';
const DESCRIPTION =
  'Browse AI agents, workflows and apps published by the Trinyx community. '
  + 'Install one in a click, or start from it and make it yours.';

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: '/marketplace' },
  // Full openGraph block: Next merges metadata shallowly per top-level field,
  // so a partial override here would DROP the root layout's og:image.
  openGraph: {
    siteName: 'Trinyx',
    title: `${TITLE} - Trinyx`,
    description: DESCRIPTION,
    url: `${SITE_URL}/marketplace`,
    type: 'website',
    images: [
      {
        url: '/og-image.jpg',
        width: 1200,
        height: 630,
        alt: 'Trinyx: one message in, a working automation out.',
      },
    ],
  },
  twitter: {
    card: 'summary_large_image',
    title: `${TITLE} - Trinyx`,
    description: DESCRIPTION,
    images: ['/og-image.jpg'],
  },
  // Self-hosted deployments must never index marketing pages (same rule as the
  // landing page, /compare and /changelog).
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

export default async function MarketplaceIndexPage() {
  const publications = await fetchMarketplacePage();

  // ItemList tells search engines this is a listing page and gives it the
  // member URLs, which helps them discover detail pages beyond the sitemap.
  const itemListJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'CollectionPage',
    name: TITLE,
    description: DESCRIPTION,
    url: `${SITE_URL}/marketplace`,
    mainEntity: {
      '@type': 'ItemList',
      itemListElement: publications
        .filter((publication) => publication.publicSlug)
        .map((publication, index) => ({
          '@type': 'ListItem',
          position: index + 1,
          name: publication.title,
          url: `${SITE_URL}${marketplacePath(publication.publicSlug as string)}`,
        })),
    },
  };

  return (
    <LandingShell>
      {!IS_CE && <JsonLd data={itemListJsonLd} />}
      <div className="mx-auto w-full max-w-6xl px-3 py-6 sm:px-6 md:py-10">
        <header className="mb-6">
          <h1 className="text-2xl font-semibold text-[var(--text-primary)] md:text-3xl">
            Marketplace
          </h1>
          <p className="mt-2 max-w-2xl text-sm text-[var(--text-secondary)]">{DESCRIPTION}</p>
        </header>

        {publications.length === 0 ? (
          // Reached when the gateway is unreachable as well as when the catalog
          // is genuinely empty: the read path degrades to an empty list rather
          // than failing the page.
          <p className="text-sm text-[var(--text-secondary)]">
            No published listings right now. Check back soon.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {publications.map((publication) => (
              <PublicationCardSsr key={publication.id} publication={publication} />
            ))}
          </div>
        )}
      </div>
    </LandingShell>
  );
}
