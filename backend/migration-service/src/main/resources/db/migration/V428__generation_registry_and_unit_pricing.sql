-- ---------------------------------------------------------------------------
-- V428: Generation registry + quantity-based (unit) pricing.
--
-- Two independent additions that together let the platform resell ANY
-- generation format (image, video, audio, voice, music, 3D, slides, ...)
-- through the existing catalog + platform-credential + markup machinery,
-- with a price that scales on the dimension that actually drives provider
-- cost (seconds of video, characters of speech, images returned).
--
-- 1. catalog.api_tools.generation_spec (jsonb)
--    Declarative descriptor imported from the `generation` block of a
--    scripts/api-migrations/<provider>.json endpoint. It is what turns an
--    ordinary catalog endpoint into a model addressable by the unified
--    `generation` tool / core:generate node. Shape (see SCHEMA.md):
--      { kind, submitTool, pollTool?, assetPath, paramMap{}, models[] }
--    NULL  = ordinary catalog endpoint, invisible to the generation surface.
--
--    Deliberately NOT a set of typed columns: the shape is a contract owned
--    by the JSON seed + importer (same posture as api_tools.output_schema),
--    and a jsonb column keeps "add a format" a zero-migration change, which
--    is the whole point of the feature.
--
-- 2. auth.pricing_version_entry: unit pricing + per-model rows
--    Today a published price is ONE flat amount per api_tool. That cannot
--    express "video costs per second" nor "this endpoint serves a standard
--    and a fast model at different prices".
--
--      price = base_credits + unit_credits x quantity   (clamped to min/max)
--
--    where base_credits is the pre-existing markup_credits column (renamed
--    in meaning only, NOT in name, so nothing downstream breaks) and
--    quantity is resolved from the call parameters by price_unit:
--      call      -> 1                    (legacy behaviour)
--      second    -> duration_seconds
--      image     -> assets requested (the n parameter), not assets returned
--      character -> length of the input text
--      minute    -> duration_seconds / 60
--
--    BACKWARD COMPATIBILITY: existing rows get price_unit='call' and
--    unit_credits=0, so price = markup_credits + 0 x 1 = markup_credits.
--    Byte-for-byte the current amount. No backfill of live pricing needed.
-- ---------------------------------------------------------------------------

-- ── 1. Generation descriptor on the catalog tool ───────────────────────────
SET search_path = catalog, public;

ALTER TABLE catalog.api_tools
    ADD COLUMN IF NOT EXISTS generation_spec JSONB;

COMMENT ON COLUMN catalog.api_tools.generation_spec IS
    'Declarative generation descriptor imported from the `generation` block of the api-migrations JSON. NULL = ordinary endpoint. Non-null = this endpoint backs one or more generation models addressable by the unified `generation` tool and the core:generate node. Shape: {kind, submitTool, pollTool, assetPath, paramMap, models[]}.';

-- Partial index: the registry only ever scans the handful of rows that carry
-- a descriptor, never the 600+ ordinary endpoints.
CREATE INDEX IF NOT EXISTS idx_api_tools_generation_spec
    ON catalog.api_tools ((generation_spec -> 'kind'))
    WHERE generation_spec IS NOT NULL;

-- ── 2. Unit pricing on the published price list ────────────────────────────
SET search_path = auth, public;

ALTER TABLE auth.pricing_version_entry
    ADD COLUMN IF NOT EXISTS model_id     VARCHAR(128),
    ADD COLUMN IF NOT EXISTS price_unit   VARCHAR(16)    NOT NULL DEFAULT 'call',
    ADD COLUMN IF NOT EXISTS unit_credits DECIMAL(12,6)  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS min_credits  DECIMAL(12,6),
    ADD COLUMN IF NOT EXISTS max_credits  DECIMAL(12,6);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pve_price_unit_chk') THEN
        ALTER TABLE auth.pricing_version_entry
            ADD CONSTRAINT pve_price_unit_chk CHECK (
                price_unit IN ('call', 'second', 'minute', 'image', 'character')
            );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pve_unit_credits_nonneg') THEN
        ALTER TABLE auth.pricing_version_entry
            ADD CONSTRAINT pve_unit_credits_nonneg CHECK (unit_credits >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pve_min_max_credits_nonneg') THEN
        ALTER TABLE auth.pricing_version_entry
            ADD CONSTRAINT pve_min_max_credits_nonneg CHECK (
                (min_credits IS NULL OR min_credits >= 0)
                AND (max_credits IS NULL OR max_credits >= 0)
                AND (min_credits IS NULL OR max_credits IS NULL OR max_credits >= min_credits)
            );
    END IF;
END $$;

-- One endpoint can back SEVERAL models at different prices (e.g. a submit
-- endpoint serving both the standard and the fast variant). The key therefore
-- becomes (version, tool, model), with a NULL model meaning "the tool-level
-- default" - which is exactly what every pre-V428 row is.
--
-- A plain UNIQUE constraint would treat NULLs as distinct and silently allow
-- duplicate tool-level rows, so the uniqueness is expressed as an index over
-- COALESCE(model_id, '').
-- The old constraint is found by INTROSPECTION, not by name. V101 declared it
-- inline inside CREATE TABLE without naming it, so Postgres generated
-- "pricing_version_entry_pricing_version_id_api_tool_id_key". Dropping a
-- guessed name would silently no-op and leave the two-column uniqueness in
-- place, which would then reject the very first per-model price row with a
-- duplicate-key error at runtime. Matching on the column set instead is
-- immune to however the constraint happened to be named.
DO $$
DECLARE
    old_constraint TEXT;
BEGIN
    SELECT con.conname INTO old_constraint
    FROM   pg_constraint con
    JOIN   pg_class      rel ON rel.oid = con.conrelid
    JOIN   pg_namespace  nsp ON nsp.oid = rel.relnamespace
    WHERE  nsp.nspname = 'auth'
      AND  rel.relname = 'pricing_version_entry'
      AND  con.contype = 'u'
      AND  con.conkey = ARRAY(
               SELECT att.attnum
               FROM   pg_attribute att
               WHERE  att.attrelid = rel.oid
                 AND  att.attname IN ('pricing_version_id', 'api_tool_id')
               ORDER  BY att.attnum)
    LIMIT 1;

    IF old_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE auth.pricing_version_entry DROP CONSTRAINT %I', old_constraint);
        RAISE NOTICE 'V428: dropped two-column uniqueness %', old_constraint;
    END IF;
END $$;

-- Same treatment for a plain unique INDEX, in case an environment carries the
-- uniqueness in that form instead.
DO $$
DECLARE
    old_index TEXT;
BEGIN
    SELECT cls.relname INTO old_index
    FROM   pg_index      idx
    JOIN   pg_class      cls ON cls.oid = idx.indexrelid
    JOIN   pg_class      tbl ON tbl.oid = idx.indrelid
    JOIN   pg_namespace  nsp ON nsp.oid = tbl.relnamespace
    WHERE  nsp.nspname = 'auth'
      AND  tbl.relname = 'pricing_version_entry'
      AND  idx.indisunique
      AND  NOT idx.indisprimary
      AND  idx.indnatts = 2
      AND  cls.relname <> 'uk_pve_version_tool_model'
      AND  NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = idx.indexrelid)
    LIMIT 1;

    IF old_index IS NOT NULL THEN
        EXECUTE format('DROP INDEX auth.%I', old_index);
        RAISE NOTICE 'V428: dropped two-column unique index %', old_index;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_pve_version_tool_model
    ON auth.pricing_version_entry (pricing_version_id, api_tool_id, COALESCE(model_id, ''));

-- V101's non-unique lookup index is now a strict prefix of the unique index
-- above, so it only costs write throughput.
DROP INDEX IF EXISTS auth.idx_pve_version_tool;

COMMENT ON COLUMN auth.pricing_version_entry.model_id IS
    'Generation model this price applies to (e.g. seedance-2.0-fast). NULL = the tool-level price, the only shape that existed before V428. Resolution order at billing time: exact model match, then the NULL row, then the version default.';
COMMENT ON COLUMN auth.pricing_version_entry.price_unit IS
    'What unit_credits is charged per: call | second | minute | image | character. Drives which call parameter supplies the quantity.';
COMMENT ON COLUMN auth.pricing_version_entry.unit_credits IS
    'Credits per unit. Final price = markup_credits (base) + unit_credits x quantity, clamped to [min_credits, max_credits] when set.';
