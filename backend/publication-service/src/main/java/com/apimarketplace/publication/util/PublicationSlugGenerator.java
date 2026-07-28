package com.apimarketplace.publication.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the URL slug used by the public marketplace ({@code /marketplace/{slug}}).
 *
 * <p><b>Why this is not {@code catalog.util.SlugUtils}.</b> That helper lives in
 * catalog-service (unreachable from here) and, more importantly, does not strip
 * diacritics: it lowercases and then deletes everything outside {@code [a-z0-9-]},
 * so {@code "Générateur d'emails"} collapses to {@code "gnrateur-demails"} and a
 * CJK title collapses to the empty string. That is acceptable for catalog names,
 * which are ASCII by construction, but not for publication titles, which are
 * free text typed by users in any language and end up in an indexed URL.
 * This generator de-accents first (NFD) and falls back to an id-derived slug when
 * a title carries no transliterable characters at all.
 *
 * <p>All methods are pure; uniqueness is resolved by the caller, which owns the
 * repository (see {@code PublicationSlugAssigner}).
 */
public final class PublicationSlugGenerator {

    /**
     * Slug budget. Stays well under the column's VARCHAR(120) so the numeric
     * de-duplication suffix ({@code -2}, {@code -37}, ...) always fits.
     */
    static final int MAX_SLUG_LENGTH = 100;

    /** Slug used when a title yields nothing transliterable (CJK, emoji-only). */
    static final String FALLBACK_PREFIX = "app";

    private PublicationSlugGenerator() {
    }

    /**
     * Slugify a publication title.
     *
     * <p>Lowercased, de-accented, non-alphanumerics collapsed to single hyphens,
     * trimmed of leading/trailing hyphens, and truncated on a word boundary so a
     * long title never ends mid-word.
     *
     * @return the slug, or an empty string when {@code title} contains nothing
     *         transliterable. Callers must treat empty as "use
     *         {@link #fallbackSlug(UUID)}" rather than persisting it.
     */
    public static String slugify(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }

        String deAccented = Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        String slug = deAccented.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");

        return truncateOnWordBoundary(slug);
    }

    /**
     * Deterministic slug for a title that produced nothing usable. Derived from
     * the publication id so it is stable across re-runs of the backfill: the URL
     * of an already-indexed page must never change.
     */
    public static String fallbackSlug(UUID publicationId) {
        String shortId = publicationId.toString().replace("-", "").substring(0, 8);
        return FALLBACK_PREFIX + "-" + shortId;
    }

    /**
     * Slugify {@code title}, falling back to {@link #fallbackSlug(UUID)} when the
     * title yields nothing. This is the entry point callers should use.
     */
    public static String slugifyOrFallback(String title, UUID publicationId) {
        String slug = slugify(title);
        return slug.isEmpty() ? fallbackSlug(publicationId) : slug;
    }

    /**
     * Append a de-duplication suffix: {@code my-app} then {@code my-app-2},
     * {@code my-app-3}, ... The base is re-truncated so the result still fits the
     * column even when the base was already at the length ceiling.
     */
    public static String withSuffix(String baseSlug, int attempt) {
        String suffix = "-" + attempt;
        int room = MAX_SLUG_LENGTH - suffix.length();
        String base = baseSlug.length() > room ? baseSlug.substring(0, room) : baseSlug;
        return base.replaceAll("-+$", "") + suffix;
    }

    /**
     * Truncate to {@link #MAX_SLUG_LENGTH}, preferring the last hyphen so the slug
     * does not end on a half word. A first "word" longer than the budget is cut
     * hard, since there is no boundary to fall back to.
     */
    private static String truncateOnWordBoundary(String slug) {
        if (slug.length() <= MAX_SLUG_LENGTH) {
            return slug;
        }
        String cut = slug.substring(0, MAX_SLUG_LENGTH);
        int lastHyphen = cut.lastIndexOf('-');
        if (lastHyphen > 0) {
            cut = cut.substring(0, lastHyphen);
        }
        return cut.replaceAll("-+$", "");
    }
}
