import Link from 'next/link';
import type { PublicPublicationSummary } from '@/lib/marketplace/publicPublications';
import { marketplacePath } from '@/lib/marketplace/indexability';

/**
 * Server-rendered marketplace card.
 *
 * Deliberately NOT `components/marketplace/PublicationCard.tsx`: that one is a
 * 478-line client component that fetches its own landing snapshot in a
 * `useEffect`, so its title and thumbnail never appear in the initial HTML.
 * A crawler would see an empty shell, which defeats the point of this page.
 * This card takes everything as props and renders to static markup.
 *
 * It also renders outside the `[locale]` tree, where there is no
 * `NextIntlClientProvider` (see the LandingShell contract), so it must not call
 * `useTranslations`. Copy is hardcoded English like /about and /compare.
 */
export default function PublicationCardSsr({
  publication,
}: {
  publication: PublicPublicationSummary;
}) {
  const { publicSlug, title, description, publisherName, categoryName, reviewCount, averageRating } =
    publication;

  // A listing without a slug has no public URL yet (it predates the backfill).
  // Render it as plain text rather than linking to a route that would 404.
  const href = publicSlug ? marketplacePath(publicSlug) : null;

  const heading = (
    <h3 className="text-base font-semibold leading-snug text-[var(--text-primary)]">{title}</h3>
  );

  return (
    <article className="flex h-full flex-col gap-2 rounded-xl border border-[var(--border-color)] bg-[var(--bg-primary)] p-4 transition-colors hover:bg-[var(--bg-secondary)]">
      {href ? (
        <Link href={href} className="no-underline">
          {heading}
        </Link>
      ) : (
        heading
      )}

      {description && (
        <p className="line-clamp-3 text-sm text-[var(--text-secondary)]">{description}</p>
      )}

      <div className="mt-auto flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[var(--text-muted)]">
        {publisherName && <span>by {publisherName}</span>}
        {categoryName && <span>{categoryName}</span>}
        {/* Ratings are only meaningful once someone has actually rated it: a
            bare "0.0" on every new listing reads as a bad score, not as
            "no reviews yet". */}
        {reviewCount > 0 && (
          <span>
            {averageRating.toFixed(1)} ({reviewCount})
          </span>
        )}
      </div>
    </article>
  );
}
