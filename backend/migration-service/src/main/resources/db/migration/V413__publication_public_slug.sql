-- V413: URL slug + publisher handle on publications, for the anonymous SEO marketplace.
--
-- public_slug backs the crawlable URL /marketplace/{public_slug}. Today every
-- marketplace URL is a raw UUID, which search engines index poorly and users
-- cannot read. The column is NULLABLE on purpose: existing rows are filled by
-- PublicationSlugBackfillRunner at boot (idempotent, only touches NULLs), and a
-- row without a slug simply stays reachable by UUID instead of blocking the
-- migration on a data rewrite.
--
-- publisher_handle denormalizes auth.user_profiles.handle at publish time,
-- alongside the publisher_name / publisher_avatar_url snapshot that already
-- lives here. Without it, rendering an author link on a public page costs one
-- extra auth-service round-trip per card (which is what the in-app
-- UserActionMenu does today).
ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS public_slug VARCHAR(120),
    ADD COLUMN IF NOT EXISTS publisher_handle VARCHAR(32);

-- Partial unique index: slugs must not collide, but the many NULL rows that
-- exist before the backfill completes must not collide with each other either.
CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_publications_public_slug
    ON publication.workflow_publications (public_slug)
    WHERE public_slug IS NOT NULL;
