-- V432: Retire the `imageGeneration` opt-in grant.
--
-- The legacy image-generation TOOL is deleted. Its per-agent grant key is now read
-- by nobody: AgentModuleResolver dropped `isImageGenerationEnabled`, and the
-- `image_generation` prompt module and tool provider are gone with it.
--
-- WHY RETIRE RATHER THAN MIGRATE THE GRANT TO `generation`
-- --------------------------------------------------------
-- The obvious move is to rewrite every `imageGeneration` grant into a `generation`
-- grant so the capability survives. That is deliberately NOT done here:
--
--   1. It widens spending authority nobody agreed to. The unified `generation`
--      tool reaches per-second video models that cost an order of magnitude more
--      per call than the images this grant was given for. AgentModuleResolver
--      documents this exact rule ("never inherited from imageGeneration") and
--      tests pin it; rewriting the ROWS instead of the resolver would produce the
--      same widening through the back door.
--   2. A migrated grant would buy far more than the images it was given for. The
--      seed ships one image model (flux) alongside ElevenLabs voice/audio/music and
--      Seedance video, and the grant is a single switch: it cannot be narrowed to
--      the one kind the owner actually agreed to spend on.
--
-- Reason 1 is the load-bearing one and stands on its own. Reason 2 said, until this
-- was corrected, that the registry shipped NO image model at all, which was true
-- when this migration was written and stopped being true in the same branch when
-- flux gained a generation block. The conclusion is unchanged; the sentence was
-- not, and a migration whose recorded justification has quietly become false is
-- worse than one with no comment, because the next reader trusts it.
--
-- So the grant is retired: the key is stripped, and an owner who wants generation
-- opts in again explicitly, which is what an opt-in credit-spending toggle means.
-- KNOWN COST, stated plainly: an owner who had `imageGeneration` on loses image
-- generation until they opt in again. That is the intended trade, not an oversight.
--
-- SAFETY / ORDERING
-- -----------------
-- This migration is NOT required for correctness, which is what makes the rollout
-- safe in either pod order: an unknown key in tools_config is simply never read, so
-- a row that still carries `imageGeneration` is inert the moment the new resolver
-- ships, and a row already stripped is inert under the old resolver too (it reads
-- as "not granted", the default for an opt-in). This is data hygiene: it stops the
-- retired key from being resurfaced by a future editor and keeps the persisted
-- shape honest about what the platform actually honours.
--
-- Idempotent (`- 'imageGeneration'` on a row without the key is a no-op) and scoped
-- with a `?` guard so it only touches rows that actually carry it.

-- RECORD WHO HAD IT, BEFORE STRIPPING IT.
--
-- The three statements below delete a key with no down migration and no way to
-- reconstruct it: nothing else in the schema records that an owner had opted in.
-- "Documented" and "recoverable" are different properties, and this is the one
-- decision in the retirement that cannot be undone by a later release.
--
-- What it buys: an owner who asks why their agent stopped generating can be
-- answered, and a future decision to migrate these grants rather than retire
-- them (see the reasoning above, which turns on spending authority and could be
-- revisited with a per-kind grant) still has its input. Three id columns, so
-- the table stays trivial on any install size.
--
-- IF NOT EXISTS because this migration must stay re-runnable: it is data
-- hygiene rather than a correctness requirement, and a repair that replays it
-- must not fail on the backup it made the first time.
CREATE TABLE IF NOT EXISTS agent.retired_image_generation_grants (
    source_table  text        NOT NULL,
    source_id     text        NOT NULL,
    retired_at    timestamptz NOT NULL DEFAULT now()
);

-- Each table names its OWN identity: user_chat_defaults is keyed by
-- (user_id, organization_id) and has no id column at all, so a uniform
-- `id::text` fails the whole migration on any install. The point is to be able
-- to answer "who had this", and for that table the answer is the pair.
INSERT INTO agent.retired_image_generation_grants (source_table, source_id)
SELECT 'agent.agents', id::text FROM agent.agents
 WHERE tools_config ? 'imageGeneration'
UNION ALL
SELECT 'conversation.user_chat_defaults',
       user_id::text || '/' || coalesce(organization_id::text, '-')
  FROM conversation.user_chat_defaults
 WHERE config ? 'imageGeneration'
UNION ALL
SELECT 'conversation.conversations', id::text FROM conversation.conversations
 WHERE chat_config ? 'imageGeneration';

-- 1. Per-agent grants.
UPDATE agent.agents
   SET tools_config = tools_config - 'imageGeneration'
 WHERE tools_config ? 'imageGeneration';

-- 2. Per-(user, workspace) chat defaults (the Preferences toggle).
UPDATE conversation.user_chat_defaults
   SET config = config - 'imageGeneration'
 WHERE config ? 'imageGeneration';

-- 3. Per-conversation chat config (the composer toggle).
UPDATE conversation.conversations
   SET chat_config = chat_config - 'imageGeneration'
 WHERE chat_config ? 'imageGeneration';

-- Verification (expect 0 rows on all three):
--   SELECT count(*) FROM agent.agents WHERE tools_config ? 'imageGeneration';
--   SELECT count(*) FROM conversation.user_chat_defaults WHERE config ? 'imageGeneration';
--   SELECT count(*) FROM conversation.conversations WHERE chat_config ? 'imageGeneration';
