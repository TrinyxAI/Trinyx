-- Add the current Chinese flagships that no feed carries, shaped exactly like
-- the curated rows already in the catalog.
--
-- WHY A MIGRATION AND NOT THE SYNC. Neither feed publishes these models under
-- their native provider: LiteLLM's `zai` block ends at glm-5.1 (glm-5.2 exists
-- only under dashscope/azure_ai/cloudflare, glm-5.3 nowhere), its `moonshot`
-- block ends at kimi-k2.6, and MiniMax M2.7 is absent. The provider-endpoint
-- discovery added alongside would find them, but only for a provider whose API
-- key the backend can resolve, and prod has none for zai / qwen / moonshot /
-- minimax (auth.platform_credentials holds only anthropic, deepseek, openai).
-- So the sync cannot produce these rows today, however often it runs.
--
-- SHAPE. Cloned from agent.model_config_overrides where (provider,model_id) =
-- ('zai','glm-5.1'), the reference curated row: source='manual',
-- provider_kind='byok', mode='chat', enabled left NULL (NULL is "no explicit
-- decision" and stays visible; only FALSE hides a model), prices set, and a
-- model_category_settings row per category. A model with no category row is
-- listed in the raw catalog yet skipped by every category-scoped selector, so
-- the sidecar is not optional.
--
-- RANKING. Each new model takes its closest sibling's ranking rather than a
-- fresh slot: 55-140 is fully occupied and nearly every row there carries
-- 'ranking' in user_modified_fields, i.e. an admin placed it by hand. Ties are
-- an established state (rankings 35, 38, 40, 42, 43 already hold two rows
-- each), so sharing a slot groups the new model with its family without
-- renumbering anyone's curation. Drag-and-drop in the admin panel is the way
-- to refine it.
--
-- PRICES: USD per 1M tokens, taken from OpenRouter's entry for the same vendor
-- id (the id is recorded in feed_metadata so any rate can be traced back).
-- Note this is OpenRouter's RESALE rate, which is not always the vendor's
-- direct one: glm-5.3 (1.40/4.40), glm-5v-turbo (1.20/4.00) and MiniMax-M2.7
-- (0.30/1.20) match the vendor list price, while glm-5.2 at 0.966/3.036 is
-- ~31% under Z.AI's direct 1.40/4.40. Calls on the native provider go direct,
-- so that one bills under the vendor rate until an admin edits it.
-- Trigger derive_model_credits() fills credits_input / credits_output.
--
-- IDEMPOTENT: ON CONFLICT DO NOTHING on the catalog, DO UPDATE on the billing
-- mirror, and the category inserts are guarded on (model_config_id, category).

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- Catalog rows
-- ---------------------------------------------------------------------------
INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, provider_kind, mode, tier, ranking,
     price_input, price_output, price_cache_read, price_floor_input, price_floor_output,
     context_window, max_output_tokens,
     supports_tools, supports_vision, supports_reasoning, supports_prompt_caching,
     last_synced_at, feed_metadata)
VALUES
    -- Z.AI. glm-5.3 is the current flagship (released 2026-08-14), glm-5.2 the
    -- previous one; both were unreachable because LiteLLM stops at glm-5.1.
    ('zai', 'glm-5.3', 'GLM-5.3', NULL, 'manual', 'byok', 'chat', 'mid', 65,
     1.4000, 4.4000, 0.2600, 1.4000, 4.4000, 1048576, 131072,
     TRUE, FALSE, TRUE, TRUE, NOW(),
     '{"priceFrom":"openrouter","openRouterId":"z-ai/glm-5.3"}'::jsonb),
    ('zai', 'glm-5.2', 'GLM-5.2', NULL, 'manual', 'byok', 'chat', 'mid', 65,
     0.9660, 3.0360, 0.1932, 0.9660, 3.0360, 1048576, 131072,
     TRUE, FALSE, TRUE, TRUE, NOW(),
     '{"priceFrom":"openrouter","openRouterId":"z-ai/glm-5.2","note":"OpenRouter resale rate; Z.AI direct list is 1.40/4.40"}'::jsonb),
    ('zai', 'glm-5v-turbo', 'GLM-5V Turbo', NULL, 'manual', 'byok', 'chat', 'mid', 69,
     1.2000, 4.0000, 0.2400, 1.2000, 4.0000, 202752, 131072,
     TRUE, TRUE, TRUE, TRUE, NOW(),
     '{"priceFrom":"openrouter","openRouterId":"z-ai/glm-5v-turbo"}'::jsonb),
    ('zai', 'glm-4.6v', 'GLM-4.6V', NULL, 'manual', 'byok', 'chat', 'budget', 69,
     0.3000, 0.9000, 0.0550, 0.3000, 0.9000, 131072, 32768,
     TRUE, TRUE, TRUE, TRUE, NOW(),
     '{"priceFrom":"openrouter","openRouterId":"z-ai/glm-4.6v"}'::jsonb),

    -- Moonshot. K3 shipped 2026-07-16; the catalog stopped at K2.6.
    ('moonshot', 'kimi-k3', 'Kimi K3', NULL, 'manual', 'byok', 'chat', 'top', 525,
     3.0000, 15.0000, 0.3000, 3.0000, 15.0000, 1048576, NULL,
     TRUE, TRUE, TRUE, TRUE, NOW(),
     '{"priceFrom":"openrouter","openRouterId":"moonshotai/kimi-k3"}'::jsonb),
    ('moonshot', 'kimi-k2.7-code', 'Kimi K2.7 Code', NULL, 'manual', 'byok', 'chat', 'mid', 528,
     0.7100, 3.5000, 0.1500, 0.7100, 3.5000, 262144, 262144,
     TRUE, TRUE, TRUE, TRUE, NOW(),
     '{"priceFrom":"openrouter","openRouterId":"moonshotai/kimi-k2.7-code"}'::jsonb),

    -- MiniMax. V437 seeded M2 through M3; M2.7 was missing from that set.
    ('minimax', 'MiniMax-M2.7', 'MiniMax M2.7', NULL, 'manual', 'byok', 'chat', 'budget', 530,
     0.3000, 1.2000, 0.0600, 0.3000, 1.2000, 204800, 131072,
     TRUE, FALSE, TRUE, TRUE, NOW(),
     '{"priceFrom":"openrouter","openRouterId":"minimax/minimax-m2.7"}'::jsonb)
ON CONFLICT (provider, model_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Category sidecars. Without a row here a model is invisible to the chat and
-- browser_agent selectors even though the catalog lists it.
--
-- rank mirrors the sibling's: the Z.AI trio sits at chat 26-28 / browser_agent
-- 39-41, while the Moonshot and MiniMax rows carry no explicit rank (they sort
-- by the global ranking, like their siblings).
-- ---------------------------------------------------------------------------
INSERT INTO model_category_settings (model_config_id, category, enabled, rank)
SELECT m.id, v.category, TRUE, v.rank
FROM model_config_overrides m
JOIN (VALUES
        ('zai',      'glm-5.3',        'chat',          26),
        ('zai',      'glm-5.3',        'browser_agent', 39),
        ('zai',      'glm-5.2',        'chat',          26),
        ('zai',      'glm-5.2',        'browser_agent', 39),
        ('zai',      'glm-5v-turbo',   'chat',          28),
        ('zai',      'glm-5v-turbo',   'browser_agent', 41),
        ('zai',      'glm-4.6v',       'chat',          28),
        ('zai',      'glm-4.6v',       'browser_agent', 41),
        ('moonshot', 'kimi-k3',        'chat',          NULL),
        ('moonshot', 'kimi-k3',        'browser_agent', NULL),
        ('moonshot', 'kimi-k2.7-code', 'chat',          NULL),
        ('moonshot', 'kimi-k2.7-code', 'browser_agent', NULL),
        ('minimax',  'MiniMax-M2.7',   'chat',          NULL),
        ('minimax',  'MiniMax-M2.7',   'browser_agent', NULL)
     ) AS v(provider, model_id, category, rank)
  ON v.provider = m.provider AND v.model_id = m.model_id
ON CONFLICT (model_config_id, category) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Two gaps left by V437, fixed here rather than left to rot.
--
-- 1. Its MiniMax rows landed with ranking NULL, so they sort behind all 700
--    catalog rows. Give them the MiniMax band, next to M2.7 above.
-- 2. zai/glm-5-turbo still has mode and context_window NULL: V437 only repaired
--    its prices. A NULL mode is treated as chat everywhere, so this changes no
--    behaviour, it just stops the row looking half-filled next to its siblings.
-- Both only touch rows that are still unset, so an admin edit made since wins.
-- ---------------------------------------------------------------------------
UPDATE model_config_overrides SET ranking = 531, updated_at = NOW()
 WHERE provider = 'minimax' AND model_id = 'MiniMax-M3'             AND ranking IS NULL;
UPDATE model_config_overrides SET ranking = 532, updated_at = NOW()
 WHERE provider = 'minimax' AND model_id = 'MiniMax-M2.5'           AND ranking IS NULL;
UPDATE model_config_overrides SET ranking = 533, updated_at = NOW()
 WHERE provider = 'minimax' AND model_id = 'MiniMax-M2.5-lightning' AND ranking IS NULL;
UPDATE model_config_overrides SET ranking = 534, updated_at = NOW()
 WHERE provider = 'minimax' AND model_id = 'MiniMax-M2.1'           AND ranking IS NULL;
UPDATE model_config_overrides SET ranking = 535, updated_at = NOW()
 WHERE provider = 'minimax' AND model_id = 'MiniMax-M2.1-lightning' AND ranking IS NULL;
UPDATE model_config_overrides SET ranking = 536, updated_at = NOW()
 WHERE provider = 'minimax' AND model_id = 'MiniMax-M2'             AND ranking IS NULL;

UPDATE model_config_overrides
   SET mode = COALESCE(mode, 'chat'),
       context_window = COALESCE(context_window, 200000),
       max_output_tokens = COALESCE(max_output_tokens, 131072),
       supports_tools = COALESCE(supports_tools, TRUE),
       updated_at = NOW()
 WHERE provider = 'zai' AND model_id = 'glm-5-turbo'
   AND (mode IS NULL OR context_window IS NULL);

-- ---------------------------------------------------------------------------
-- Billing mirror (auth.model_pricing is the table the billing path reads)
-- ---------------------------------------------------------------------------
INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active)
VALUES
    ('zai',      'glm-5.3',        1.4000,  4.4000, 0, CURRENT_DATE, true),
    ('zai',      'glm-5.2',        0.9660,  3.0360, 0, CURRENT_DATE, true),
    ('zai',      'glm-5v-turbo',   1.2000,  4.0000, 0, CURRENT_DATE, true),
    ('zai',      'glm-4.6v',       0.3000,  0.9000, 0, CURRENT_DATE, true),
    ('moonshot', 'kimi-k3',        3.0000, 15.0000, 0, CURRENT_DATE, true),
    ('moonshot', 'kimi-k2.7-code', 0.7100,  3.5000, 0, CURRENT_DATE, true),
    ('minimax',  'MiniMax-M2.7',   0.3000,  1.2000, 0, CURRENT_DATE, true)
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate  = EXCLUDED.input_rate,
              output_rate = EXCLUDED.output_rate,
              is_active   = true;
