-- CE-side generation SEED bookkeeping.
--
-- Single-row (PK=1) marker of the last generation-seed VERSION applied at boot
-- by GenerationSeedBootstrap (catalog-seeds/generation-seed.json, generated
-- from scripts/api-migrations/*.json by generate_generation_seed.py). The seed
-- re-applies the descriptors ONLY when the shipped version is greater than
-- applied_version, so a release refreshes an already-seeded install exactly
-- once and an unchanged version is a no-op.
--
-- Why this install needs it at all: a self-hosted install that has never synced
-- a signed catalog bundle boots from catalog-data.sql.gz, which carries
-- catalog.api_tools but NOT generation_spec, and the starting prices are
-- published by catalog-service-import, which is not part of the CE image. Both
-- halves therefore ship in the image instead.
--
-- Distinct from api_catalog_bundle_sync_status (V331), which tracks the signed
-- cloud BUNDLE fetch/apply. This row tracks the code-shipped seed, which needs
-- no cloud link. Distinct too from catalog_seed_state (V31), which is the
-- per-API YAML seed ledger and is truncated whenever the SQL dump is reloaded.

SET search_path TO catalog;

CREATE TABLE IF NOT EXISTS generation_seed_state (
    id               SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    -- Highest generation-seed `version` whose descriptors already reached
    -- catalog.api_tools.generation_spec. NULL before the first apply.
    applied_version  BIGINT,
    applied_at       TIMESTAMPTZ
);

-- Seed the single row so UPDATE semantics hold without an upsert.
INSERT INTO generation_seed_state (id) VALUES (1) ON CONFLICT DO NOTHING;

COMMENT ON TABLE generation_seed_state
    IS 'CE-side marker of the last generation-seed version applied at boot. Single row (id=1).';
