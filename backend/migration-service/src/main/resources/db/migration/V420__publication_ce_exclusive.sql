-- CE-exclusive publications.
--
-- A publication whose snapshot uses a feature that ONLY exists on a self-hosted
-- install cannot work on managed cloud:
--   * CLI_AGENT      - an agent bound to a local-CLI bridge provider
--                      (claude-code / codex / gemini-cli / mistral-vibe). The CLI
--                      binary runs on the operator's own bridge host.
--   * VECTOR_SEARCH  - a VECTOR (embedding) column or a similarity search over
--                      one. Blocked on managed cloud by the datasource edition
--                      gate, which would silently strip the column at clone time.
--
-- The marketplace shows these with a "CE exclusive" badge and refuses the install
-- on managed cloud; the CE download path (/api/ce-marketplace/**) stays open, so
-- self-hosted installs can still acquire them. The flag is RECOMPUTED from the
-- snapshot on every publish and every update (CeExclusiveFeatureDetector) - it is
-- never publisher-set, so it cannot drift from the actual content.

ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS ce_exclusive BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ce_exclusive_features JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN publication.workflow_publications.ce_exclusive IS
    'True when the snapshot uses a self-hosted-only feature. Recomputed on publish/update by CeExclusiveFeatureDetector; blocks acquisition on managed cloud.';
COMMENT ON COLUMN publication.workflow_publications.ce_exclusive_features IS
    'JSON array of the detected feature codes (CLI_AGENT, VECTOR_SEARCH) backing ce_exclusive. Drives the badge tooltip.';

-- Backfill for publications frozen before this column existed. Uses the jsonpath
-- recursive wildcard so nested sub-workflow / sub-agent snapshots are covered by
-- the same expression the Java detector walks explicitly.
--
-- CeExclusiveBackfillExecutionTest REPLAYS this file against a real Postgres over
-- the golden fixtures in
-- migration-service/src/test/resources/ce-exclusive-backfill-fixtures.sql. Those
-- expectations are hand-maintained (migration-service cannot call the Java
-- detector), and the same shapes are asserted against the detector itself in
-- CeExclusiveFeatureDetectorTest. Adding a shape to one side means adding it to
-- the other.
--
-- Predicates are anchored to a NAMED container rather than to a bare key: an
-- unanchored `$.**.provider` or `$.**.type` matches free-form payload the
-- detector never reads (interface snapshot data, agent config blobs, user rows)
-- and would flag a row that the next publish silently un-flags.
--
-- The anchoring is NOT exact, and deliberately so. Several predicates still use
-- `$.**` to reach a container (`$.**.plan.agents[*]`, `$.**.tables[*]`,
-- `$.**._snapshot_ds_mappingSpec`) because sub-workflows and sub-agents nest
-- arbitrarily deep, whereas the Java detector reaches those same containers by
-- explicit recursion. So a snapshot whose FREE-FORM payload happens to mimic a
-- platform key path (e.g. user row data containing `tables[].similarity.column`)
-- can be flagged here and not by the detector. That direction is over-block, not
-- a cloud-install leak, and it self-corrects on the publication's next publish.
-- Narrowing it further is a follow-up, not a correctness gap.
WITH detected AS (
    SELECT
        p.id,
        (
            -- Provider keys, each ANCHORED to a container the Java detector walks:
            --   plan agents[]        - the plan root, plus every nested plan
            --                          (sub-workflows, and the workflow plans an
            --                          AGENT publication embeds) via `.**.plan`;
            --   agent{}              - an AGENT publication's own agent, plus each
            --                          sub-agent under subAgents.
            -- Keys read: _snapshot_agent_modelProvider (entity-backed node),
            -- provider (inline agent/classify/guardrail node), modelProvider (the
            -- agent entity), _snapshot_agent_compactionModelProvider (COLD summariser).
            --
            -- Anchoring matters: a bare `$.**.agents[*]` also descends into
            -- free-form payload such as an agent CONFIG blob, flagging a row the
            -- next publish would silently un-flag.
            --
            -- `? (@.type() == "object")` is MANDATORY before keyvalue(): the key
            -- `agents` is ALSO a list of ID STRINGS (an agent's toolsConfig grant
            -- list, snapshotted verbatim), and .keyvalue() on a string is an
            -- item-method ERROR that lax mode does NOT suppress - it would abort
            -- the migration, fail Flyway and roll the deploy back.
            --
            -- `@.value.type() == "string"` sits in the KEY filter, not after
            -- .value: lax mode would otherwise unwrap an ARRAY value and match its
            -- elements, which the Java detector (asString) never does.
            --
            -- The value is matched case-insensitively with surrounding whitespace
            -- tolerated, exactly like BridgeProviders.isBridgeProvider (trim +
            -- lowercase). An `==` would leave "Claude-Code" un-flagged here and
            -- flagged at its next publish.
            -- The keyed-MAP form (agents as {id: node}) is covered too: mapsIn()
            -- accepts it, and `agents[*]` on an object yields the object itself.
            jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.agents[*] ? (@.type() == "object").keyvalue() ? ((@.key == "_snapshot_agent_modelProvider" || @.key == "provider" || @.key == "modelProvider" || @.key == "_snapshot_agent_compactionModelProvider" || @.key == "compactionModelProvider") && @.value.type() == "string").value ? (@ like_regex "^\\s*(claude-code|codex|gemini-cli|mistral-vibe)\\s*$" flag "i")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.agents.* ? (@.type() == "object").keyvalue() ? ((@.key == "_snapshot_agent_modelProvider" || @.key == "provider" || @.key == "modelProvider" || @.key == "_snapshot_agent_compactionModelProvider" || @.key == "compactionModelProvider") && @.value.type() == "string").value ? (@ like_regex "^\\s*(claude-code|codex|gemini-cli|mistral-vibe)\\s*$" flag "i")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.plan.agents[*] ? (@.type() == "object").keyvalue() ? ((@.key == "_snapshot_agent_modelProvider" || @.key == "provider" || @.key == "modelProvider" || @.key == "_snapshot_agent_compactionModelProvider" || @.key == "compactionModelProvider") && @.value.type() == "string").value ? (@ like_regex "^\\s*(claude-code|codex|gemini-cli|mistral-vibe)\\s*$" flag "i")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.plan.agents.* ? (@.type() == "object").keyvalue() ? ((@.key == "_snapshot_agent_modelProvider" || @.key == "provider" || @.key == "modelProvider" || @.key == "_snapshot_agent_compactionModelProvider" || @.key == "compactionModelProvider") && @.value.type() == "string").value ? (@ like_regex "^\\s*(claude-code|codex|gemini-cli|mistral-vibe)\\s*$" flag "i")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.agent ? (@.type() == "object").keyvalue() ? ((@.key == "modelProvider" || @.key == "compactionModelProvider") && @.value.type() == "string").value ? (@ like_regex "^\\s*(claude-code|codex|gemini-cli|mistral-vibe)\\s*$" flag "i")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.subAgents.*.agent ? (@.type() == "object").keyvalue() ? ((@.key == "modelProvider" || @.key == "compactionModelProvider") && @.value.type() == "string").value ? (@ like_regex "^\\s*(claude-code|codex|gemini-cli|mistral-vibe)\\s*$" flag "i")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.plan.agents[*] ? (@.type() == "object").keyvalue() ? ((@.key == "_snapshot_agent_modelProvider" || @.key == "provider" || @.key == "modelProvider" || @.key == "_snapshot_agent_compactionModelProvider" || @.key == "compactionModelProvider") && @.value.type() == "string").value ? (@ like_regex "^\\s*(claude-code|codex|gemini-cli|mistral-vibe)\\s*$" flag "i")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.plan.agents.* ? (@.type() == "object").keyvalue() ? ((@.key == "_snapshot_agent_modelProvider" || @.key == "provider" || @.key == "modelProvider" || @.key == "_snapshot_agent_compactionModelProvider" || @.key == "compactionModelProvider") && @.value.type() == "string").value ? (@ like_regex "^\\s*(claude-code|codex|gemini-cli|mistral-vibe)\\s*$" flag "i")')
        ) AS cli_agent,
        (
            -- VECTOR columns live ONLY inside a captured SCHEMA: a table node's
            -- _snapshot_ds_mappingSpec, a standalone TABLE snapshot's root
            -- mappingSpec, an INTERFACE's embeddedTable.mappingSpec, or an
            -- agent-attached datasource's mappingSpec.
            --
            -- The type is read at the COLUMN level (`.*.type`) and through the
            -- `children` recursion, NEVER via a bare `.**.type`: a column also
            -- carries a `display` object with its own `type`, which is a render
            -- hint, not a column type. Matching it would flag a text column whose
            -- display happens to say "vector".
            --
            -- ColumnType.VECTOR serialises through @JsonValue as LOWERCASE
            -- "vector"; jsonpath comparison is case-sensitive, so the uppercase
            -- spelling is matched too for any hand-written payload.
            jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**._snapshot_ds_mappingSpec.*.type ? (@ == "vector" || @ == "VECTOR")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**._snapshot_ds_mappingSpec.**.children.*.type ? (@ == "vector" || @ == "VECTOR")')
            -- standalone TABLE resource: schema at the ROOT only (a nested
            -- mappingSpec would also match free-form payload, e.g. an agent config blob)
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.mappingSpec.*.type ? (@ == "vector" || @ == "VECTOR")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.mappingSpec.**.children.*.type ? (@ == "vector" || @ == "VECTOR")')
            -- standalone INTERFACE resource: its backing table
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.embeddedTable.mappingSpec.*.type ? (@ == "vector" || @ == "VECTOR")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.embeddedTable.mappingSpec.**.children.*.type ? (@ == "vector" || @ == "VECTOR")')
            -- agent-attached datasources + workflow plans embedded in an agent snapshot
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.datasources.*.mappingSpec.*.type ? (@ == "vector" || @ == "VECTOR")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.datasources.*.mappingSpec.**.children.*.type ? (@ == "vector" || @ == "VECTOR")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**._snapshot_ds_mappingSpec.*.type ? (@ == "vector" || @ == "VECTOR")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**._snapshot_ds_mappingSpec.**.children.*.type ? (@ == "vector" || @ == "VECTOR")')
            -- Similarity search on a CRUD table node. The THREE containers the
            -- plan parser merges are spelled out (node root, crud, params) rather
            -- than reached with a level wildcard: `.**{0 to 1}` would also match
            -- any OTHER level-1 container (e.g. _snapshot_ds_sourceConfig), which
            -- the Java detector does not read. Both the object form and the
            -- stringified-JSON form are matched.
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.tables[*].similarity.column ? (@ != null && @ != "")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.tables[*].crud.similarity.column ? (@ != null && @ != "")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.tables[*].params.similarity.column ? (@ != null && @ != "")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.tables[*].similarity ? (@ like_regex "\"column\"\\s*:\\s*\"[^\"]+\"")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.tables[*].crud.similarity ? (@ like_regex "\"column\"\\s*:\\s*\"[^\"]+\"")')
            OR jsonb_path_exists(COALESCE(p.plan_snapshot, '{}'::jsonb),
                '$.**.tables[*].params.similarity ? (@ like_regex "\"column\"\\s*:\\s*\"[^\"]+\"")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.tables[*].similarity.column ? (@ != null && @ != "")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.tables[*].crud.similarity.column ? (@ != null && @ != "")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.tables[*].params.similarity.column ? (@ != null && @ != "")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.tables[*].similarity ? (@ like_regex "\"column\"\\s*:\\s*\"[^\"]+\"")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.tables[*].crud.similarity ? (@ like_regex "\"column\"\\s*:\\s*\"[^\"]+\"")')
            OR jsonb_path_exists(COALESCE(p.agent_snapshot, '{}'::jsonb),
                '$.**.tables[*].params.similarity ? (@ like_regex "\"column\"\\s*:\\s*\"[^\"]+\"")')
        ) AS vector_search
    FROM publication.workflow_publications p
    -- Cheap prefilter: a snapshot embeds its datasource ROWS, so multi-MB
    -- payloads are normal and running 15 recursive jsonpath scans over every one
    -- of them would stall the deploy. A row whose raw text contains none of these
    -- tokens cannot match any predicate, so skip it before the expensive walk.
    -- Case-INsensitive (~*) to stay a strict superset of the predicates above,
    -- which tolerate any spelling of a provider name.
    WHERE COALESCE(p.plan_snapshot::text, '') || COALESCE(p.agent_snapshot::text, '')
          ~* '(claude-code|codex|gemini-cli|mistral-vibe|"vector"|similarity)'
)
UPDATE publication.workflow_publications p
SET ce_exclusive = TRUE,
    ce_exclusive_features = (
        SELECT COALESCE(jsonb_agg(feature ORDER BY feature), '[]'::jsonb)
        FROM (
            SELECT 'CLI_AGENT' AS feature WHERE d.cli_agent
            UNION ALL
            SELECT 'VECTOR_SEARCH' WHERE d.vector_search
        ) features
    )
FROM detected d
WHERE p.id = d.id
  AND (d.cli_agent OR d.vector_search);
