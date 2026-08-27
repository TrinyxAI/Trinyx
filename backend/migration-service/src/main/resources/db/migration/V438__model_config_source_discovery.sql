-- Allow source='discovery' on model_config_overrides.
--
-- A third catalog source now exists alongside the LiteLLM and OpenRouter feeds:
-- NativeModelDiscoveryService asks each configured provider which models it
-- actually serves, through that provider's own OpenAI-compatible
-- GET <base>/models endpoint. It exists because both feeds are third-party
-- mirrors that lag per vendor - on 2026-08-19 LiteLLM's `zai` block still ended
-- at glm-5.1 while Z.AI was serving glm-5.2, glm-5.3 and glm-5v-turbo, and its
-- `moonshot` block ended at kimi-k2.6 while Kimi K3 had shipped a month earlier.
--
-- Rows stamped 'discovery' have a property no other source has: they carry NO
-- price. /models publishes none, and an aggregator's rate is its resale rate,
-- not the vendor's (z-ai/glm-5.1: 0.966/3.036 on OpenRouter vs 1.40/4.40
-- direct), so inferring one would under-bill every direct call. The source
-- value is what lets the admin UI and the unpriced-enable guard tell those rows
-- apart from a feed row that legitimately has prices.
--
-- Widening a CHECK constraint is backward-compatible: every existing value
-- stays valid, so this is a no-op for the rows already in the table.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO agent;

ALTER TABLE model_config_overrides
    DROP CONSTRAINT IF EXISTS model_config_overrides_source_check,
    ADD  CONSTRAINT model_config_overrides_source_check
         CHECK (source IN ('manual','curated','openrouter','litellm','bundle','discovery'));
