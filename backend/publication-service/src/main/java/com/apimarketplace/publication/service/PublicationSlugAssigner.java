package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.util.PublicationSlugGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Single source of truth for assigning a publication's public URL slug
 * ({@code /marketplace/{slug}}).
 *
 * <p>Deliberately shaped like {@link PublisherProfileSnapshotter}: a static
 * helper that takes its collaborator as a parameter, so the three publish entry
 * points (workflow, agent, resource) each call one line and no constructor
 * changes ripple through their test suites. {@code PublicationSlugAssignerTest}
 * asserts all three call sites exist, the same way the snapshotter's test does.
 *
 * <p><b>Assign once.</b> A publication that already has a slug keeps it, even
 * when its title changes on re-publish. Search engines rank a URL, not a row: a
 * slug that followed the title would move the page on every edit, dropping its
 * ranking and breaking already-shared links.
 */
final class PublicationSlugAssigner {

    private static final Logger log = LoggerFactory.getLogger(PublicationSlugAssigner.class);

    /**
     * Bound on de-duplication attempts. Beyond this a title is so heavily
     * duplicated that a readable slug is not worth more queries, and we fall back
     * to the id-derived form, which is unique by construction.
     */
    private static final int MAX_ATTEMPTS = 50;

    private PublicationSlugAssigner() {
    }

    /**
     * Give {@code publication} a slug if it has none. No-op when one is already
     * set, so re-publish paths can call it unconditionally.
     */
    static void assignIfMissing(WorkflowPublicationEntity publication,
                                WorkflowPublicationRepository repository) {
        if (publication.getPublicSlug() != null && !publication.getPublicSlug().isBlank()) {
            return;
        }
        // On a first publish the id is still null: WorkflowPublicationEntity
        // assigns it in @PrePersist, which has not run yet. The id only seeds the
        // fallback branch (a title with nothing transliterable), so a fresh UUID
        // is acceptable there. It is never written back to the entity; the slug is
        // persisted once and never recomputed.
        UUID seed = publication.getId() != null ? publication.getId() : UUID.randomUUID();
        publication.setPublicSlug(resolveUniqueSlug(publication.getTitle(), seed, repository));
    }

    /**
     * Compute a slug for {@code title} that no other row holds.
     *
     * <p>Racing publishes can both observe "free" and collide on insert; the
     * partial unique index from V413 is the real guarantee and turns that race
     * into a constraint violation rather than two identical public URLs.
     *
     * @param publicationId seeds the id-derived fallback; must be non-null
     */
    static String resolveUniqueSlug(String title,
                                    UUID publicationId,
                                    WorkflowPublicationRepository repository) {
        String base = PublicationSlugGenerator.slugifyOrFallback(title, publicationId);

        if (!repository.existsByPublicSlug(base)) {
            return base;
        }

        for (int attempt = 2; attempt <= MAX_ATTEMPTS; attempt++) {
            String candidate = PublicationSlugGenerator.withSuffix(base, attempt);
            if (!repository.existsByPublicSlug(candidate)) {
                return candidate;
            }
        }

        String fallback = PublicationSlugGenerator.fallbackSlug(publicationId);
        log.warn("Slug '{}' exhausted {} de-duplication attempts; falling back to id-derived '{}'",
                base, MAX_ATTEMPTS, fallback);
        return fallback;
    }
}
