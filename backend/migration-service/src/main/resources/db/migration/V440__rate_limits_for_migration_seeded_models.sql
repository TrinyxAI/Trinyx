-- Restore the "every catalog row has a rate-limit ceiling" invariant for the
-- rows that migrations inserted directly.
--
-- THE INVARIANT, from application.yml (ai.agent.*.rate-limit-*):
--   "Catalog row fallbacks applied by CatalogMergeService when a feed does not
--    supply rate limits. Every row MUST have non-null rate limits so the limiter
--    enforces a ceiling - LiteLLM only populates rpm/tpm for ~1.8% of models, so
--    these defaults catch the rest."
--
-- HOW IT WAS BROKEN: that fallback lives in CatalogMergeService, so it only
-- applies to rows that arrive through a feed sync. A migration INSERTing
-- straight into model_config_overrides bypasses the merge entirely and escapes
-- it. V437 and V439 did exactly that, so the 7 models they seeded landed with
-- all four rate-limit columns NULL.
--
-- WHY THAT MATTERS: NULL is not "inherit a sane ceiling". The resolution order
-- in CachedModelRateLimitProvider is DB column -> ai.agent.rate-limits.<model>
-- (YAML) -> ProviderRateLimiter's provider bucket. None of these 7 has a YAML
-- entry, and RateLimitConfig.defaults is 10,000,000 TPM / 10,000 RPM, described
-- in code as "very permissive defaults - override in application.yml for
-- production". No `rate-limit.providers` map is configured, so that permissive
-- default is what they actually got: effectively no ceiling at all.
--
-- WHAT THIS SETS: the documented catalog-row fallback, 60000 / 500 / 20000 /
-- 200. That is the value CatalogMergeService would have written had these rows
-- gone through it, and it is what the other 500+ rows of these same providers
-- enforce today (zai glm-5.1 and glm-5, all 21 Moonshot rows, all 6 MiniMax
-- rows from V437). So this makes the new models consistent with their family
-- rather than inventing a per-model figure.
--
-- DELIBERATELY NOT TOUCHED - the other 6 NULL rows in the table:
--   mistral-medium-3, perplexity/sonar-pro, perplexity/sonar-reasoning-pro,
--   zai/glm-5-turbo, openrouter/anthropic/claude-sonnet-4-20250514,
--   openrouter/google/gemini-3-pro-preview
-- Those NULLs are CORRECT. Each has a curated ai.agent.rate-limits entry
-- (500k-2M TPM), and a DB value would override it downward by up to 33x, since
-- the DB column wins per-field over the YAML seed. They are the only rows in
-- the catalog where the curated table is still in force.
--
-- Idempotent: only rows where all four columns are still NULL are touched, so a
-- re-run and any admin edit made in between are both no-ops.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO agent;

UPDATE model_config_overrides
   SET rate_limit_tpm            = 60000,
       rate_limit_rpm            = 500,
       rate_limit_tpm_per_tenant = 20000,
       rate_limit_rpm_per_tenant = 200,
       updated_at                = NOW()
 WHERE (provider, model_id) IN (
           ('zai',      'glm-5.3'),
           ('zai',      'glm-5.2'),
           ('zai',      'glm-5v-turbo'),
           ('zai',      'glm-4.6v'),
           ('moonshot', 'kimi-k3'),
           ('moonshot', 'kimi-k2.7-code'),
           ('minimax',  'MiniMax-M2.7')
       )
   AND rate_limit_tpm            IS NULL
   AND rate_limit_rpm            IS NULL
   AND rate_limit_tpm_per_tenant IS NULL
   AND rate_limit_rpm_per_tenant IS NULL;
