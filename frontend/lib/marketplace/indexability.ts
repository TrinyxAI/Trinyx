import type { PublicPublicationSummary } from './publicPublications';

/**
 * Decides which public marketplace pages search engines may index.
 *
 * Not every published listing deserves to be in the index. A page whose whole
 * content is a title and a one-line description is "thin content": Google
 * demotes it, and enough of them drag down the ranking of the entire domain,
 * including the pages that already perform (the landing, /compare, the blog).
 * Listings that fail this gate are still fully reachable and rendered, they
 * just carry `noindex` and stay out of the sitemap.
 *
 * This is intentionally a pure function on the view model: the same rule must
 * drive the page's robots meta AND the sitemap, and any divergence between the
 * two produces the worst outcome, a URL advertised in the sitemap that then
 * tells the crawler not to index it.
 */

/**
 * Minimum description length for an indexable listing.
 *
 * Sized so a real sentence about what the app does passes and a placeholder
 * ("test", "my workflow", "asdf") does not. Deliberately a floor rather than a
 * quality judgement: the goal is to filter out empties, not to referee prose.
 */
export const MIN_INDEXABLE_DESCRIPTION_LENGTH = 120;

export function isIndexable(publication: PublicPublicationSummary): boolean {
  // Without a slug there is no canonical URL to index: the row predates the
  // backfill and is only reachable by UUID.
  if (!publication.publicSlug) return false;
  if (publication.title.trim().length === 0) return false;
  return publication.description.trim().length >= MIN_INDEXABLE_DESCRIPTION_LENGTH;
}

/** Canonical path of a public listing. */
export function marketplacePath(slug: string): string {
  return `/marketplace/${slug}`;
}

/**
 * Build the one-line description used for `<meta name="description">` and the
 * OpenGraph card.
 *
 * Truncated on a word boundary with a real ellipsis character rather than three
 * dots, and never mid-word: search engines show roughly this much, and a
 * description cut through a word reads as broken to a human scanning results.
 * Falls back to a generic sentence so a listing never ships an empty meta
 * description (which search consoles flag).
 */
export function metaDescription(publication: PublicPublicationSummary, maxLength = 155): string {
  const description = publication.description.trim();
  if (description.length === 0) {
    return `${publication.title} on the Trinyx marketplace.`;
  }
  if (description.length <= maxLength) return description;

  const cut = description.slice(0, maxLength - 1);
  const lastSpace = cut.lastIndexOf(' ');
  return `${(lastSpace > 0 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`;
}
