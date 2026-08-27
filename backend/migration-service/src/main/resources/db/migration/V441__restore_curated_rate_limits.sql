-- Hand control of rate limits back to the curated ai.agent.rate-limits table.
--
-- THE COLLISION. Two layers were meant to complement each other and instead
-- fought. CachedModelRateLimitProvider resolves a model's limit as
-- coalesce(DB column, YAML seed, provider bucket): a DB value wins per-field.
-- CatalogMergeService, meanwhile, stamped the generic catalog fallback
-- (60000/500/20000/200) onto every feed row that arrived without limits - and
-- LiteLLM supplies rpm/tpm for only ~1.8% of models, so nearly every row got
-- stamped. The stamp then beat the curated value.
--
-- Measured against production on 2026-08-24: 44 of the 50 curated entries were
-- inert. The gap was not cosmetic.
--     openai/gpt-5.4-mini        curated 10,000,000 TPM  enforced 60,000
--     openai/gpt-5               curated  4,000,000 TPM  enforced 60,000
--     deepseek/deepseek-chat     curated  2,000,000 TPM  enforced 60,000
--     anthropic/claude-sonnet-4-6 curated   800,000 TPM  enforced 60,000
-- RPM moved both ways: claude-opus-4-7 enforced 500 rpm against a curated 50.
--
-- WHAT THIS CLEARS. Only rows that carry the fallback fingerprint EXACTLY
-- (60000/500/20000/200) AND have a curated entry. That pairing is provably the
-- blanket stamp rather than a decision: an admin who chose these four numbers
-- by hand would be indistinguishable, but the value is the documented default
-- and the row's own provider family carries it uniformly.
--
-- WHAT THIS DELIBERATELY DOES NOT TOUCH:
--   * 11 curated rows holding OTHER values (the google / gemini-cli family at
--     800000/2000, deepseek-coder with tpm_per_tenant = -1 meaning "disabled").
--     Those differ from both the fallback and the curated value, so they read as
--     deliberate. Clearing google/gemini-2.5-pro would drop its RPM from 2000 to
--     the curated 20 - a 100x cut nobody asked for.
--   * The 7 models with NO curated entry, filled by V440. Clearing them would
--     drop them onto RateLimitConfig.defaults (10M TPM / 10K RPM), i.e. no
--     ceiling. Together the two migrations keep the invariant intact: every row
--     resolves to either a DB value or a curated one, never to nothing.
--
-- DURABILITY. Clearing alone would last until the next sync re-stamped the
-- fallback. CatalogMergeService.applyRateLimitDefaults now skips any model that
-- has a curated entry, so the columns stay NULL and the curated table stays in
-- force. That code change is what makes this migration permanent rather than a
-- one-day reprieve.
--
-- Idempotent: the fingerprint guard means a re-run, or a row an admin has edited
-- since, is a no-op.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO agent;

UPDATE model_config_overrides
   SET rate_limit_tpm            = NULL,
       rate_limit_rpm            = NULL,
       rate_limit_tpm_per_tenant = NULL,
       rate_limit_rpm_per_tenant = NULL,
       updated_at                = NOW()
 WHERE rate_limit_tpm            = 60000
   AND rate_limit_rpm            = 500
   AND rate_limit_tpm_per_tenant = 20000
   AND rate_limit_rpm_per_tenant = 200
   AND (provider, model_id) IN (
    ('anthropic','claude-opus-4-7'),
    ('anthropic','claude-sonnet-4-6'),
    ('anthropic','claude-opus-4-6'),
    ('anthropic','claude-fable-5'),
    ('deepseek','deepseek-chat'),
    ('openai','gpt-5'),
    ('openai','gpt-5.2'),
    ('openai','gpt-5.4-mini'),
    ('cohere','command-r-08-2024'),
    ('mistral','mistral-small-latest'),
    ('mistral','mistral-large-latest'),
    ('openai','o4-mini'),
    ('cohere','command-r-plus-08-2024'),
    ('zai','glm-5.1'),
    ('zai','glm-5'),
    ('anthropic','claude-haiku-4-5'),
    ('xai','grok-3-mini-beta'),
    ('openrouter','openai/gpt-5.4'),
    ('qwen','glm-5.1'),
    ('openai','gpt-5-mini'),
    ('mistral-vibe','devstral-2'),
    ('mistral-vibe','devstral-small-2'),
    ('codex','gpt-5.4-mini'),
    ('codex','gpt-5.3-codex'),
    ('codex','gpt-5.2'),
    ('codex','gpt-5.4'),
    ('claude-code','claude-opus-4-6'),
    ('claude-code','claude-opus-4-7'),
    ('claude-code','claude-haiku-4-5'),
    ('claude-code','claude-fable-5'),
    ('claude-code','claude-sonnet-4-6'),
    ('openai','gpt-5.4'),
    ('xai','grok-3-beta')
       );
