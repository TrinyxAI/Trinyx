-- Bring the Chinese model catalog up to parity with the western providers.
--
-- Three distinct gaps, all measured against the live LiteLLM feed and the live
-- cloud catalog on 2026-08-19:
--
--  1. MiniMax was absent entirely. Its 6 chat models pass every catalog-sync
--     filter unchanged (chat, tools, flat positive prices); the only thing
--     missing was a provider mapping. LiteLlmFeedParser.PROVIDER_MAP now maps
--     "minimax", so the sync keeps these rows fresh from here on - this seed is
--     what makes them exist and be selectable BEFORE the first sync runs.
--
--  2. Alibaba's flagships were silently deleted at every sync. DashScope bills
--     by context bracket, so LiteLLM publishes a `tiered_pricing` ladder for
--     those rows INSTEAD of the flat input_cost_per_token / output_cost_per_token
--     keys - and the parser's zero-price gate read only the flat keys, so
--     qwen3-max and the whole qwen3-coder line looked unpriced and were dropped.
--     LiteLlmFeedParser.effectiveCost now falls back to the base bracket; this
--     seed lands those models immediately instead of waiting on a sync + an
--     admin enabling each one.
--
--  3. Rows the sync inserted after V388 came in with NO model_category_settings,
--     so they were listed in the raw catalog yet skipped by every
--     category-scoped selector (the chat and browser_agent pickers). 19 of the
--     21 Moonshot rows, 9 of the 12 Z.AI rows and 17 of the 20 Qwen rows were in
--     that state on cloud. Part 4 repairs the existing ones; MergeOptions.forSync
--     now sets assignDefaultCategoriesOnInsert=true so the class cannot recur.
--
-- Prices: USD per 1M tokens.
--   * MiniMax rows are the flat feed prices, cross-checked against MiniMax's
--     published standard tier ($0.30 / $1.20 for the M-series).
--   * Qwen rows are the BASE context bracket - the figure Alibaba Model Studio
--     advertises (qwen3-max $1.20/$6.00 base, rising to $3.00/$15.00 on its
--     largest bracket). Same rule the parser now applies, so the next sync
--     confirms these values rather than fighting them.
--   Trigger derive_model_credits() fills credits_input / credits_output.
--
-- Idempotency:
--   * model_config_overrides: ON CONFLICT (provider, model_id) DO NOTHING -
--     never clobbers an admin-edited row (matches V384/V112).
--   * model_pricing: ON CONFLICT (provider, model, effective_from) DO UPDATE -
--     realigns rates on re-run (matches V116/V386).
--   * category backfill: only rows with NO sidecar at all are touched.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- ---------------------------------------------------------------------------
-- Part 1 + 2: catalog rows (agent schema, catalog source of truth)
-- ---------------------------------------------------------------------------
SET search_path TO agent;

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version, mode,
     price_input, price_output, price_cache_read,
     price_floor_input, price_floor_output,
     context_window, max_output_tokens,
     supports_tools, supports_vision, supports_reasoning, supports_prompt_caching,
     tier, last_synced_at)
VALUES
    -- MiniMax (M-series). 1M context except M2. Vision on M3 only.
    ('minimax', 'MiniMax-M3',             'MiniMax M3',             TRUE, 'curated', 1, 'chat',
     0.30, 1.20, 0.06, 0.30, 1.20, 1000000, 128000, TRUE, TRUE,  TRUE, TRUE, 'budget', NOW()),
    ('minimax', 'MiniMax-M2.5',           'MiniMax M2.5',           TRUE, 'curated', 1, 'chat',
     0.30, 1.20, 0.03, 0.30, 1.20, 1000000,   8192, TRUE, FALSE, TRUE, TRUE, 'budget', NOW()),
    ('minimax', 'MiniMax-M2.5-lightning', 'MiniMax M2.5 Lightning', TRUE, 'curated', 1, 'chat',
     0.30, 2.40, 0.03, 0.30, 2.40, 1000000,   8192, TRUE, FALSE, TRUE, TRUE, 'mid',    NOW()),
    ('minimax', 'MiniMax-M2.1',           'MiniMax M2.1',           TRUE, 'curated', 1, 'chat',
     0.30, 1.20, 0.03, 0.30, 1.20, 1000000,   8192, TRUE, FALSE, TRUE, TRUE, 'budget', NOW()),
    ('minimax', 'MiniMax-M2.1-lightning', 'MiniMax M2.1 Lightning', TRUE, 'curated', 1, 'chat',
     0.30, 2.40, 0.03, 0.30, 2.40, 1000000,   8192, TRUE, FALSE, TRUE, TRUE, 'mid',    NOW()),
    ('minimax', 'MiniMax-M2',             'MiniMax M2',             TRUE, 'curated', 1, 'chat',
     0.30, 1.20, 0.03, 0.30, 1.20,  200000,   8192, TRUE, FALSE, TRUE, TRUE, 'budget', NOW()),

    -- Qwen (Alibaba) - the context-bracket-priced flagships the sync used to drop.
    ('qwen', 'qwen3-max',         'Qwen3 Max',         TRUE, 'curated', 1, 'chat',
     1.20, 6.00, NULL, 1.20, 6.00, 258048, 65536, TRUE, FALSE, TRUE, FALSE, 'high',   NOW()),
    ('qwen', 'qwen3-coder-plus',  'Qwen3 Coder Plus',  TRUE, 'curated', 1, 'chat',
     1.00, 5.00, 0.10, 1.00, 5.00, 997952, 65536, TRUE, FALSE, TRUE, TRUE,  'high',   NOW()),
    ('qwen', 'qwen3-coder-flash', 'Qwen3 Coder Flash', TRUE, 'curated', 1, 'chat',
     0.30, 1.50, 0.08, 0.30, 1.50, 997952, 65536, TRUE, FALSE, TRUE, TRUE,  'mid',    NOW()),
    ('qwen', 'qwen3.7-plus',      'Qwen3.7 Plus',      TRUE, 'curated', 1, 'chat',
     0.40, 1.60, 0.08, 0.40, 1.60, 991808, 65536, TRUE, TRUE,  TRUE, TRUE,  'mid',    NOW()),
    ('qwen', 'qwen3.5-plus',      'Qwen3.5 Plus',      TRUE, 'curated', 1, 'chat',
     0.40, 2.40, NULL, 0.40, 2.40, 991808, 65536, TRUE, TRUE,  TRUE, FALSE, 'mid',    NOW()),
    ('qwen', 'qwen3-vl-plus',     'Qwen3 VL Plus',     TRUE, 'curated', 1, 'chat',
     0.20, 1.60, NULL, 0.20, 1.60, 260096, 32768, TRUE, TRUE,  TRUE, FALSE, 'mid',    NOW()),
    ('qwen', 'qwen-flash',        'Qwen Flash',        TRUE, 'curated', 1, 'chat',
     0.05, 0.40, NULL, 0.05, 0.40, 997952, 32768, TRUE, FALSE, TRUE, FALSE, 'budget', NOW())
ON CONFLICT (provider, model_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Part 3: repair Z.AI glm-5-turbo, listed with NO price at all
--
-- The row is source='manual' and carries NULL price_input / price_output while
-- being active in the chat and browser_agent categories: it is selectable and
-- bills nothing. Z.AI's list price is $1.20 / $4.00, which is also what the CE
-- seed (model-catalog/models.json) has always shipped for it - the cloud row
-- simply never got the values. Only filled when still NULL, so an admin edit
-- made in the meantime wins.
-- ---------------------------------------------------------------------------
UPDATE model_config_overrides
   SET price_input  = COALESCE(price_input,  1.20),
       price_output = COALESCE(price_output, 4.00),
       tier         = COALESCE(tier, 'mid'),
       updated_at   = NOW()
 WHERE provider = 'zai'
   AND model_id = 'glm-5-turbo'
   AND (price_input IS NULL OR price_output IS NULL);

-- ---------------------------------------------------------------------------
-- Part 4: category backfill for orphans (same rule as V388, re-run)
--
-- V388 repaired the orphans that existed when it ran; every row the LiteLLM
-- sync has inserted since then arrived orphaned again, because forSync did not
-- assign default categories. That is fixed in code now, but the rows already in
-- the table still need this one-shot repair. Provider-agnostic on purpose: the
-- Chinese rows are the bulk of it, but any other provider caught by the same
-- gap is repaired in the same pass.
--
-- Mode -> default categories mirrors ModelCategory.acceptsMode: NULL/'chat' ->
-- chat + browser_agent ; 'image' -> image_generation ; anything else untouched.
-- ---------------------------------------------------------------------------
INSERT INTO model_category_settings (model_config_id, category, enabled)
SELECT mco.id, c.category, TRUE
FROM model_config_overrides mco
CROSS JOIN (VALUES ('chat'), ('browser_agent')) AS c(category)
WHERE (mco.mode IS NULL OR mco.mode = 'chat')
  AND mco.deprecated_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM model_category_settings mcs WHERE mcs.model_config_id = mco.id
  )
ON CONFLICT (model_config_id, category) DO NOTHING;

INSERT INTO model_category_settings (model_config_id, category, enabled)
SELECT mco.id, 'image_generation', TRUE
FROM model_config_overrides mco
WHERE mco.mode = 'image'
  AND mco.deprecated_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM model_category_settings mcs WHERE mcs.model_config_id = mco.id
  )
ON CONFLICT (model_config_id, category) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Billing (auth schema, the table ModelPricingService reads)
-- ---------------------------------------------------------------------------
INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active)
VALUES
    ('minimax', 'MiniMax-M3',             0.30, 1.20, 0, CURRENT_DATE, true),
    ('minimax', 'MiniMax-M2.5',           0.30, 1.20, 0, CURRENT_DATE, true),
    ('minimax', 'MiniMax-M2.5-lightning', 0.30, 2.40, 0, CURRENT_DATE, true),
    ('minimax', 'MiniMax-M2.1',           0.30, 1.20, 0, CURRENT_DATE, true),
    ('minimax', 'MiniMax-M2.1-lightning', 0.30, 2.40, 0, CURRENT_DATE, true),
    ('minimax', 'MiniMax-M2',             0.30, 1.20, 0, CURRENT_DATE, true),
    ('qwen',    'qwen3-max',              1.20, 6.00, 0, CURRENT_DATE, true),
    ('qwen',    'qwen3-coder-plus',       1.00, 5.00, 0, CURRENT_DATE, true),
    ('qwen',    'qwen3-coder-flash',      0.30, 1.50, 0, CURRENT_DATE, true),
    ('qwen',    'qwen3.7-plus',           0.40, 1.60, 0, CURRENT_DATE, true),
    ('qwen',    'qwen3.5-plus',           0.40, 2.40, 0, CURRENT_DATE, true),
    ('qwen',    'qwen3-vl-plus',          0.20, 1.60, 0, CURRENT_DATE, true),
    ('qwen',    'qwen-flash',             0.05, 0.40, 0, CURRENT_DATE, true),
    ('zai',     'glm-5-turbo',            1.20, 4.00, 0, CURRENT_DATE, true)
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate  = EXCLUDED.input_rate,
              output_rate = EXCLUDED.output_rate,
              is_active   = true;
