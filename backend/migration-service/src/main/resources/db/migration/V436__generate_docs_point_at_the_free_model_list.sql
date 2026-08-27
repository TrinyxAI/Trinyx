-- ============================================================================
-- V436: the generate node's documentation told the agent to call a tool it may
-- not have.
--
-- V429 wrote, in `description` and in `concepts[0]`: "call generation(action=
-- 'models') to list them". That tool is opt-IN per agent, and deliberately so:
-- every create spends the customer's credits at the model's own rate, and a
-- per-second video model spends far more of them than a web search. An agent
-- that was not granted it therefore reads an instruction it cannot follow, and
-- `model` is the one required param of this node whose values cannot be
-- guessed. The result is an agent that cannot build the node and cannot say
-- why.
--
-- LISTING the models spends nothing, so the ids now ride on
-- workflow(action='help', topics=['generate']) - the help every builder already
-- holds. That payload is assembled from THIS row plus a static block, so a row
-- still naming the paid tool puts both instructions in front of the agent at
-- once. Only the row can fix its own half.
--
-- Also corrects `parameters.credential_source`, whose wording predates the
-- runs_on field: an unstated node runs on the PLATFORM key (GenerateNode
-- substitutes it), which fails outright where the platform does not resell the
-- provider. That is exactly what runs_on now reports, so the text points at it.
--
-- Targeted UPDATEs of named JSONB paths: every other column, and every other
-- parameter, keeps whatever V429, V433 or a later edit left there.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET description = 'Generates ONE asset from a prompt: image, video, audio, voice or music. The ''model'' param is the only required one and it decides everything else: the format produced, which other params are accepted, and what the run costs. Model ids cannot be guessed - workflow(action=''help'', topics=[''generate'']) lists every id this installation offers, with what each accepts and which key can run it; that is a free read you always have. Parameters are checked against the model BEFORE the provider is called, so a rejected call costs nothing and the error names what the model does accept. Every successful run IS charged: a model priced per second or per character costs more for a longer request, and the node reports the size it billed on as billed_quantity in billed_unit. A long job is waited for; the node never returns a job id to poll. When no asset comes back the node FAILS rather than continue with an empty file. When emitting via set_plan, put the config under ''params''.',
    updated_at = NOW()
WHERE type = 'generate'
  AND description LIKE '%generation(action=''models'')%';

UPDATE node_type_documentation
SET concepts = jsonb_set(
        concepts,
        '{0}',
        to_jsonb(
            'Pick the model FIRST: it decides the format, the accepted params and the price. '
            || 'workflow(action=''help'', topics=[''generate'']) lists every id with what it accepts and '
            || 'its runs_on; a model id cannot be guessed. Read runs_on before writing the node: '
            || '''own_key_only'' means the platform does not resell that provider here, so the node needs '
            || 'credential_source=''user'' and a key the owner has connected, and an unstated node fails '
            || 'on its first run.'::text
        )
    ),
    updated_at = NOW()
WHERE type = 'generate'
  AND jsonb_typeof(concepts) = 'array'
  AND concepts->>0 LIKE 'Pick the model FIRST%generation(action=''models'')%';

UPDATE node_type_documentation
SET parameters = jsonb_set(
        parameters,
        '{model,description}',
        to_jsonb(
            'REQUIRED. A generation model id from workflow(action=''help'', topics=[''generate'']), '
            || 'e.g. seedance-2.0-fast. Decides the format produced, the accepted params and the price.'::text
        )
    ),
    updated_at = NOW()
WHERE type = 'generate'
  AND parameters -> 'model' ->> 'description' LIKE '%generation(action=''models'')%';

UPDATE node_type_documentation
SET parameters = jsonb_set(
        parameters,
        '{credential_source,description}',
        to_jsonb(
            '''platform'' uses the platform''s key at the platform price; ''user'' uses a key the owner '
            || 'configured themselves, which the platform never charges for. UNSTATED MEANS ''platform'' '
            || 'for this node - it is substituted before the run, so leaving it out chooses a payer '
            || 'rather than falling back. Check runs_on in workflow(action=''help'', topics=[''generate'']) '
            || 'first: where it says ''own_key_only'' the platform does not resell that provider on this '
            || 'installation, and a node left unstated, or set to ''platform'', fails on its first run.'::text
        )
    ),
    updated_at = NOW()
WHERE type = 'generate'
  -- Content-matched like every other statement here: the header promises a
  -- later hand edit survives, and an unguarded rewrite would break that promise
  -- (and V435 set the precedent deliberately).
  AND parameters -> 'credential_source' ->> 'description' LIKE '%Omitting it means%';
