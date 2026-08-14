-- ---------------------------------------------------------------------------
-- V430: who decided this price - the platform owner, or a signed catalog bundle.
--
-- The signed API-catalog bundle now carries the GENERATION PRICES the cloud
-- published, so a self-hosted install that never runs the importer receives
-- them (see catalog-service ApiCatalogBundlePayload.GenerationPriceRow). That
-- turns one question into a blocking one: when a bundle lands on an install
-- whose administrator has already tuned a price, which of the two wins?
--
-- Without per-row provenance the only two answers are both wrong. Overwrite
-- everything and the administrator's decision is silently reverted every 15
-- minutes by the sync scheduler. Overwrite nothing and the FIRST local edit,
-- on any single model, freezes every other model's price forever.
--
-- So the row records who wrote it, exactly as agent.model_config_overrides
-- records user_modified_fields for the LLM catalog: a bundle may replace a row
-- it wrote itself, and never one a human here wrote.
--
--   'admin'  - published from this install (admin screens, importer bootstrap).
--              Also the value every pre-V430 row gets, which is the fail-closed
--              reading: a price that is already live was decided by somebody
--              here, and a bundle must not reprice it behind their back.
--   'bundle' - written by an applied API-catalog bundle. The next bundle owns
--              it, so cloud price changes keep flowing to untouched models.
--
-- Row-level rather than version-level on purpose: a pricing version is
-- published as a whole, so version-level provenance would make "the admin
-- changed one model" mean "this install stops receiving prices".
-- ---------------------------------------------------------------------------

SET search_path = auth, public;

ALTER TABLE auth.pricing_version_entry
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'admin';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pve_source_chk') THEN
        ALTER TABLE auth.pricing_version_entry
            ADD CONSTRAINT pve_source_chk CHECK (source IN ('admin', 'bundle'));
    END IF;
END $$;

COMMENT ON COLUMN auth.pricing_version_entry.source IS
    'Who decided this price: admin = published on this install (admin screens or importer bootstrap, and every pre-V430 row); bundle = written by an applied signed API-catalog bundle. A bundle apply replaces a bundle-sourced row and preserves an admin-sourced one, mirroring user_modified_fields on agent.model_config_overrides.';
