-- Fixtures for the V420 CE-exclusive backfill.
--
-- Every row is a snapshot shape the publish side or the workflow builder really
-- writes, plus decoys the Java detector deliberately ignores.
--
-- `expect` is a HAND-MAINTAINED golden column: it records what
-- CeExclusiveFeatureDetector returns for the same payload. It is not derived at
-- run time - that class lives in publication-service, which migration-service
-- does not depend on. CeExclusiveBackfillExecutionTest replays the real V420
-- against these rows and asserts the migration agrees with the golden column;
-- CeExclusiveFeatureDetectorTest asserts the detector side of the same shapes.
-- Adding a shape to one belongs in both, or the pair stops being a cross-check.

CREATE TABLE publication.workflow_publications(
  id serial PRIMARY KEY,
  name text,
  expect boolean,
  plan_snapshot jsonb,
  agent_snapshot jsonb
);

INSERT INTO publication.workflow_publications(name, expect, plan_snapshot, agent_snapshot) VALUES
 -- Shapes that ABORT a naive `.keyvalue()` predicate: the key `agents` is also a
 -- list of ID STRINGS (an agent's toolsConfig grant list, snapshotted verbatim).
 -- .keyvalue() on a string is an item-method error that lax mode does NOT
 -- suppress, so it fails Flyway and rolls the deploy back.
 ('X-toolscfg-agentpub', true, NULL, '{"agent":{"modelProvider":"claude-code","toolsConfig":{"agents":["1111"],"agentsGrant":"custom"}}}'),
 ('X-toolscfg-plan',     true, '{"agents":[{"_snapshot_agent_modelProvider":"codex","_snapshot_agent_toolsConfig":{"agents":["2222"]}}]}', NULL),
 ('X-agent-label',       false,'{"nodes":[{"agent":"my-label"}]}', NULL),
 ('X-toolscfg-plain',    false,'{"agents":[{"_snapshot_agent_modelProvider":"openai","_snapshot_agent_toolsConfig":{"agents":["3333"]}}]}', NULL),

 -- CLI agents, every container and key spelling
 ('cli-inline',      true, '{"agents":[{"provider":"claude-code"}]}', NULL),
 -- BridgeProviders.isBridgeProvider trims + lowercases, so the backfill must too
 ('cli-mixedcase',   true, '{"agents":[{"provider":"Claude-Code"}]}', NULL),
 ('cli-whitespace',  true, '{"agents":[{"provider":"  codex  "}]}', NULL),
 -- mapsIn() accepts a keyed map; nothing writes it today, but the two
 -- implementations must not disagree if something ever does
 ('cli-agents-map',  true, '{"agents":{"a1":{"provider":"gemini-cli"}}}', NULL),
 ('cli-entity',      true, '{"agents":[{"agentConfigId":"a1","_snapshot_agent_modelProvider":"codex"}]}', NULL),
 ('cli-compact',     true, '{"agents":[{"_snapshot_agent_modelProvider":"openai","_snapshot_agent_compactionModelProvider":"gemini-cli"}]}', NULL),
 -- The BARE key, on a plan agent node. CeExclusiveFeatureDetector.usesBridgeProvider lists it,
 -- the backfill originally did not: the row was flagged by a re-publish but not by the migration,
 -- i.e. two sources of truth for one label.
 ('cli-compact-bare', true, '{"agents":[{"provider":"anthropic","compactionModelProvider":"claude-code"}]}', NULL),
 ('cli-subwf',       true, '{"_snapshot_subworkflows":{"w1":{"plan":{"agents":[{"provider":"mistral-vibe"}]}}}}', NULL),
 ('cli-agentpub',    true, NULL, '{"agent":{"modelProvider":"claude-code"}}'),
 ('cli-subagent',    true, NULL, '{"agent":{"modelProvider":"openai"},"subAgents":{"s1":{"agent":{"modelProvider":"codex"}}}}'),
 ('cli-agent-wf',    true, NULL, '{"agent":{"modelProvider":"openai"},"workflows":{"w1":{"plan":{"agents":[{"_snapshot_agent_modelProvider":"claude-code"}]}}}}'),

 -- Vector / embeddings, every captured-schema container
 ('vec-table',       true, '{"tables":[{"_snapshot_ds_mappingSpec":{"e":{"type":"vector"}}}]}', NULL),
 ('vec-children',    true, '{"tables":[{"_snapshot_ds_mappingSpec":{"m":{"type":"object","children":{"e":{"type":"vector"}}}}}]}', NULL),
 ('vec-uppercase',   true, '{"tables":[{"_snapshot_ds_mappingSpec":{"e":{"type":"VECTOR"}}}]}', NULL),
 ('vec-standalone',  true, '{"name":"Docs","mappingSpec":{"e":{"type":"vector"}}}', NULL),
 ('vec-iface-emb',   true, '{"name":"UI","embeddedTable":{"mappingSpec":{"e":{"type":"vector"}}}}', NULL),
 ('vec-agent-ds',    true, NULL, '{"agent":{"modelProvider":"openai"},"datasources":{"42":{"mappingSpec":{"e":{"type":"vector"}}}}}'),
 ('vec-subwf',       true, '{"_snapshot_subworkflows":{"w1":{"plan":{"tables":[{"_snapshot_ds_mappingSpec":{"e":{"type":"vector"}}}]}}}}', NULL),

 -- Similarity search: the three containers the plan parser merges, both forms
 ('vec-sim-root',    true, '{"tables":[{"similarity":{"column":"e"}}]}', NULL),
 ('vec-sim-crud',    true, '{"tables":[{"crud":{"similarity":{"column":"e"}}}]}', NULL),
 ('vec-sim-params',  true, '{"tables":[{"params":{"similarity":{"column":"e"}}}]}', NULL),
 ('vec-sim-str',     true, '{"tables":[{"params":{"similarity":"{\"column\":\"e\",\"topK\":5}"}}]}', NULL),

 -- Decoys: payload the Java detector never reads. Flagging any of these would
 -- create a label the next publish silently clears.
 ('D-iface-type',    false,'{"interfaces":[{"_snapshot_data":{"rows":[{"type":"vector"}]}}]}', NULL),
 ('D-cfg-spec',      false,'{"agents":[{"_snapshot_agent_config":{"mappingSpec":{"e":{"type":"vector"}}}}]}', NULL),
 ('D-display-type',  false,'{"tables":[{"_snapshot_ds_mappingSpec":{"c":{"type":"text","display":{"type":"vector"}}}}]}', NULL),
 ('D-items-sim',     false,'{"tables":[{"_snapshot_ds_items":[{"data":{"similarity":{"column":"score"}}}]}]}', NULL),
 -- a level-1 container the plan parser does NOT merge into the crud config
 ('D-srccfg-sim',    false,'{"tables":[{"_snapshot_ds_sourceConfig":{"similarity":{"column":"e"}}}]}', NULL),
 -- an agent CONFIG blob is free-form payload, not a node list
 ('D-cfg-agents',    false,'{"agents":[{"provider":"openai","_snapshot_agent_config":{"agents":[{"provider":"claude-code"}]}}]}', NULL),
 -- asString() returns null for a non-String, so the Java detector ignores it
 ('D-provider-array',false,'{"agents":[{"provider":["claude-code"]}]}', NULL),
 ('D-sim-blank',     false,'{"tables":[{"params":{"similarity":{"column":""}}}]}', NULL),
 ('D-sim-str-blank', false,'{"tables":[{"params":{"similarity":"{\"column\":\"\"}"}}]}', NULL),
 ('D-prompt',        false,'{"agents":[{"provider":"openai","_snapshot_agent_systemPrompt":"use claude-code style"}]}', NULL),
 ('plain',           false,'{"agents":[{"provider":"anthropic"}],"tables":[{"_snapshot_ds_mappingSpec":{"t":{"type":"text"}}}]}', NULL),
 ('nulls',           false, NULL, NULL);
