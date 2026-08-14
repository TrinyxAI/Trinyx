-- ============================================================================
-- V433: two corrections to the core:generate agent documentation
--
--   1. `credential_id`. The node has always been able to run on a SPECIFIC one
--      of the owner's provider keys, and the inspector has offered the choice
--      since it shipped, but nothing carried the id into the plan until now.
--      This row is the parameter list an agent discovers the node through, so
--      an agent rewriting a node from it would drop the field and silently move
--      the run onto the account's default key. Documented here as something to
--      PRESERVE rather than to choose: no action lists the owner's credential
--      ids, so an agent has nothing to pick from and inventing a number would
--      name a key that does not exist.
--
--   2. `n` is REMOVED, not reworded. The row advertised "Models priced per
--      image bill on this value", which the platform cannot honour: one call
--      fetches and stores exactly ONE asset, while a per-image price
--      multiplies n, so a caller asking for four was charged for four and
--      handed one. No model in the catalogue accepts the parameter at all,
--      and the request builder refuses any value above 1, so listing it here
--      could only cost an agent a turn: it would be offered a parameter every
--      model then rejects. A model that one day accepts it will appear in the
--      accepts list of generation(action='models'), which is where an agent
--      learns what a model takes.
--
-- A targeted UPDATE, not an upsert: only the two `parameters` entries and the
-- `concepts` list are touched, and every other column keeps whatever V429 (or
-- a later edit) left there.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = jsonb_set(
        parameters - 'n',
        '{credential_id}',
        '{"type": "number", "required": false, "description": "WHICH of the workflow owner''s own provider keys this node runs on, when they hold several for one provider. Only meaningful beside credential_source:''user''. You cannot choose one - the ids belong to the owner''s account and no action lists them, so the owner sets this in the builder. Copy it UNCHANGED when you rewrite a node that already has one, and never invent a number. A node without it runs on the owner''s default key for that provider, which is the right answer for every node you create."}'::jsonb,
        true
    ),
    concepts = concepts || '["A node may carry credential_id, naming which of the owner''s provider keys it runs on. You cannot pick one and no action lists them: keep the value a node already has when you rewrite it, and leave it out of nodes you create so they run on the owner''s default key."]'::jsonb,
    updated_at = NOW()
WHERE type = 'generate';
