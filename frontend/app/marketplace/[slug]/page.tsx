import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import JsonLd from '@/components/seo/JsonLd';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';
import { fetchPublicationBySlug } from '@/lib/marketplace/publicPublications';
import { isIndexable, marketplacePath, metaDescription } from '@/lib/marketplace/indexability';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://trinyx.fr';

/**
 * Public listing page, addressed by its URL slug.
 *
 * ISR rather than SSG: the catalog is open-ended and grows whenever anyone
 * publishes, so there is no build-time list of slugs to pre-render. New
 * listings must be reachable without a deploy, which also means
 * `dynamicParams` stays at its default (true) here, unlike /compare and /blog
 * whose content lives in the repo.
 */
// Literal on purpose: Next requires route segment config to be statically
// analyzable, so importing PUBLIC_MARKETPLACE_REVALIDATE_SECONDS here fails the
// build with "Invalid segment configuration export". Keep the two in step.
export const revalidate = 900;

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const publication = await fetchPublicationBySlug(slug);
  if (!publication) return {};

  const url = `${SITE_URL}${marketplacePath(slug)}`;
  const description = metaDescription(publication);
  const title = `${publication.title} - Trinyx Marketplace`;

  // Thin listings render normally but stay out of the index, and out of the
  // sitemap, which reads the same predicate. Enough near-empty pages drag down
  // the ranking of the whole domain.
  const noIndex = IS_CE || !isIndexable(publication);

  return {
    title,
    description,
    alternates: { canonical: url },
    // Both blocks are spelled out in full: Next merges metadata shallowly per
    // top-level field, so a partial override drops the root layout's values.
    // `images` is deliberately OMITTED from both: setting it here would win over
    // the file-based `opengraph-image.tsx` next to this page, and every shared
    // listing would fall back to the one generic site-wide card again.
    openGraph: {
      siteName: 'Trinyx',
      title,
      description,
      url,
      type: 'article',
    },
    twitter: {
      card: 'summary_large_image',
      title,
      description,
    },
    robots: noIndex ? { index: false, follow: true } : undefined,
  };
}

export default async function MarketplaceListingPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  const publication = await fetchPublicationBySlug(slug);

  // The backend answers 404 identically for an unknown slug and for a listing
  // that is not anonymously readable, so a probe cannot tell them apart. This
  // page must preserve that: one notFound() for both.
  if (!publication) notFound();

  const url = `${SITE_URL}${marketplacePath(slug)}`;

  const softwareJsonLd: Record<string, unknown> = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: publication.title,
    description: metaDescription(publication),
    url,
    applicationCategory: 'BusinessApplication',
    operatingSystem: 'Web',
  };
  if (publication.publisherName) {
    softwareJsonLd.author = { '@type': 'Person', name: publication.publisherName };
  }
  // Only claim a rating when one actually exists: an aggregateRating with
  // reviewCount 0 is invalid structured data and earns a Search Console error.
  if (publication.reviewCount > 0) {
    softwareJsonLd.aggregateRating = {
      '@type': 'AggregateRating',
      ratingValue: publication.averageRating,
      reviewCount: publication.reviewCount,
    };
  }

  const breadcrumbJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: SITE_URL },
      { '@type': 'ListItem', position: 2, name: 'Marketplace', item: `${SITE_URL}/marketplace` },
      { '@type': 'ListItem', position: 3, name: publication.title, item: url },
    ],
  };

  const indexable = isIndexable(publication);

  return (
    <LandingShell>
      {!IS_CE && indexable && <JsonLd data={softwareJsonLd} />}
      {!IS_CE && indexable && <JsonLd data={breadcrumbJsonLd} />}

      <div className="mx-auto w-full max-w-3xl px-3 py-6 sm:px-6 md:py-10">
        <nav className="mb-4 text-sm text-[var(--text-muted)]">
          <Link href="/marketplace" className="no-underline hover:underline">
            Marketplace
          </Link>
        </nav>

        <h1 className="text-2xl font-semibold text-[var(--text-primary)] md:text-3xl">
          {publication.title}
        </h1>

        <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-[var(--text-muted)]">
          {publication.publisherName &&
            (publication.publisherHandle ? (
              // Only link when the publisher has a public handle: their profile
              // is otherwise private and the URL would 404.
              <Link href={`/u/${publication.publisherHandle}`} className="no-underline hover:underline">
                by {publication.publisherName}
              </Link>
            ) : (
              <span>by {publication.publisherName}</span>
            ))}
          {publication.categoryName && <span>{publication.categoryName}</span>}
          {publication.reviewCount > 0 && (
            <span>
              {publication.averageRating.toFixed(1)} ({publication.reviewCount} reviews)
            </span>
          )}
        </div>

        {publication.description && (
          <p className="mt-6 whitespace-pre-line text-base leading-relaxed text-[var(--text-secondary)]">
            {publication.description}
          </p>
        )}
      </div>
    </LandingShell>
  );
}
