-- Protect hand-written display names from being overwritten by the catalog sync.
--
-- Context: widening LiteLlmFeedParser.PROVIDER_MAP (moonshot / dashscope->qwen /
-- zai) finally lets the "Refresh from providers" sync reach the Kimi, Qwen and
-- GLM rows. The LiteLLM feed carries no display name, so the parser stamps the
-- RAW model id as displayName, and CatalogMergeService.applyFields overwrites
-- display_name on update unless the field is listed in user_modified_fields.
--
-- V384 / V386 seeded five rows with curated, human display names and left
-- user_modified_fields empty:
--     qwen     qwen-max    'Qwen Max'      moonshot  kimi-k2.6  'Kimi K2.6'
--     qwen     qwen-plus   'Qwen Plus'     moonshot  kimi-k2.5  'Kimi K2.5'
--     qwen     qwen-turbo  'Qwen Turbo'
-- Without this migration the first apply-sync silently renames them to
-- 'qwen-max', 'kimi-k2.6', ... in the model picker.
--
-- The predicate is deliberately generic (any curated row whose display_name
-- differs from its model_id) rather than a hardcoded list of five: every
-- V112-seeded row uses the raw id as its display name, so this is a no-op for
-- them, and any future curated row with a real name is protected by the same
-- rule instead of needing another migration.
--
-- The 4 CLI bridges are EXCLUDED. Their rows also carry curated pretty names
-- ('Claude Opus 4.7', 'GPT-5.4 mini', ...), but those are owned by
-- BridgeModelDeriver.prettifyDisplayName, which re-derives them on every sync.
-- Marking them user-modified would freeze the deriver out of its own field.
-- Bridges are safe from the clobber this migration fixes anyway: they never
-- come from a feed (ModelCatalogSyncService.EXCLUDED_PROVIDERS).
--
-- TRADEOFF, accepted deliberately: CatalogMergeService builds its protected-field
-- set from user_modified_fields alone, identically for the sync, bundle and seed
-- paths. So these five names also stop being renameable by a signed bundle;
-- renaming them now takes an admin edit, which is precisely what
-- user_modified_fields is meant to express. Pinned by
-- CatalogMergeServiceDisplayNameProtectionTest.
--
-- Field names in user_modified_fields are camelCase (they are matched against
-- CatalogMergeService.APPLIED_FIELD_NAMES), consistent with V158's 'priceInput'.
-- array_append is guarded by the NOT ... = ANY(...) check so re-running cannot
-- duplicate the entry.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO agent;

UPDATE model_config_overrides
   SET user_modified_fields = array_append(user_modified_fields, 'displayName')
 WHERE source = 'curated'
   AND provider NOT IN ('claude-code', 'codex', 'gemini-cli', 'mistral-vibe')
   AND display_name IS NOT NULL
   AND display_name IS DISTINCT FROM model_id
   AND NOT ('displayName' = ANY(user_modified_fields));
