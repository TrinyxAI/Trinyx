-- ============================================================================
-- V429: Add node_type_documentation row for core:generate
--   - core:generate produces ONE asset from a prompt: image, video, audio,
--     voice or music. The model chosen decides the format produced, which
--     parameters are accepted and the price.
--   - The `outputs` block below is the agent-facing half of the 3-way
--     alignment: it MUST stay identical to GenerateNodeSpec's declared output
--     fields (what the generic mapper persists) and to the schema the frontend
--     inspector reads back from /api/node-definitions.
-- ============================================================================

SET search_path TO orchestrator;

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'generate',
    'Generate',
    'core',
    'core',
    'Generates ONE asset from a prompt: image, video, audio, voice or music. The ''model'' param is the only required one and it decides everything else: the format produced, which other params are accepted, and what the run costs. Model ids cannot be guessed - call generation(action=''models'') to list them with what each accepts, its limits and its rate, then use one of those ids. Parameters are checked against the model BEFORE the provider is called, so a rejected call costs nothing and the error names what the model does accept. Every successful run IS charged: a model priced per second or per character costs more for a longer request, and the node reports the size it billed on as billed_quantity in billed_unit. A long job is waited for; the node never returns a job id to poll. When no asset comes back the node FAILS rather than continue with an empty file. When emitting via set_plan, put the config under ''params''.',
    '{
      "model":             {"type": "string", "required": true,  "description": "REQUIRED. A generation model id from generation(action=''models''), e.g. seedance-2.0-fast. Decides the format produced, the accepted params and the price."},
      "prompt":            {"type": "string", "required": false, "description": "What to generate. Required by every model that accepts it. For speech synthesis this is the text spoken, and models priced per character bill on its length."},
      "duration_seconds":  {"type": "number", "required": false, "description": "Length in seconds for video, music and speech. Models priced per second or per minute bill on this value. Bounded per model - see that model''s limits."},
      "n":                 {"type": "number", "required": false, "description": "How many assets to produce. Models priced per image bill on this value."},
      "aspect_ratio":      {"type": "string", "required": false, "description": "Framing, e.g. 16:9. Only the values listed in that model''s limits are accepted."},
      "resolution":        {"type": "string", "required": false, "description": "Output size, e.g. 720p. Only the values listed in that model''s limits are accepted."},
      "voice":             {"type": "string", "required": false, "description": "Voice id for speech synthesis. Required by some voice models - see that model''s accepted params."},
      "language":          {"type": "string", "required": false, "description": "Language code for speech synthesis."},
      "quality":           {"type": "string", "required": false, "description": "Provider quality tier. Only the values listed in that model''s limits are accepted."},
      "style":             {"type": "string", "required": false, "description": "Provider style preset."},
      "seed":              {"type": "number", "required": false, "description": "Seed for reproducible output."},
      "negative_prompt":   {"type": "string", "required": false, "description": "What to avoid in the generated asset."},
      "input_image":       {"type": "string|object", "required": false, "description": "A reference image or first frame: the WHOLE FileRef output of an upstream node as a whole-value template (e.g. {{core:download.output.file}}), never .path and never a URL."},
      "input_audio":       {"type": "string|object", "required": false, "description": "A reference voice or track: the WHOLE FileRef output of an upstream node, same rule as input_image."},
      "input_video":       {"type": "string|object", "required": false, "description": "A reference or continuation video: the WHOLE FileRef output of an upstream node, same rule as input_image."},
      "credential_source": {"type": "string", "required": false, "description": "Which key runs the call: ''platform'' uses the platform''s provider key and the run is billed the platform price, ''user'' uses a key you configured yourself and the platform bills nothing. Omitting it means ''platform'', so a run you did not mark is charged."}
    }'::jsonb,
    '{
      "file":              {"type": "object", "description": "The generated asset, stored so it outlives the provider''s own link. Reference via {{core:<label>.output.file}} and map the WHOLE object into a downstream file param, never .path or a URL."},
      "model":             {"type": "string", "description": "The generation model that ran, as its public id."},
      "kind":              {"type": "string", "description": "The format produced: image, video, audio, voice, music, ... the same value the model was listed under."},
      "provider":          {"type": "string", "description": "Name of the provider whose model ran."},
      "billed_quantity":   {"type": "number", "description": "The size this run was billed on, counted in billed_unit (e.g. 10 for a 10 second video). Derived from the request, never supplied as a param. A model sold per call reports 1."},
      "billed_unit":       {"type": "string", "description": "What billed_quantity counts: call, second, image or character. This is the unit the size was MEASURED in, which is not always the unit the rate is quoted in: a model listed per minute reports seconds here. To work out the cost, convert billed_quantity into the rate''s own unit first (60 seconds is 1 minute), then multiply."},
      "provider_response": {"type": "object", "description": "The provider''s own payload, kept under its own key so a provider field can never shadow file or model. Its shape varies by provider - do not build downstream logic on it."}
    }'::jsonb,
    NULL,
    NULL,
    '["Pick the model FIRST: it decides the format, the accepted params and the price. generation(action=''models'') lists every id with its accepts, limits and rate; a model id cannot be guessed.", "A param the model does not accept, or a value outside its limits, is refused BEFORE the provider is called - the run costs nothing and the error names the accepted values, so correct it and run again.", "Every successful run is charged. A per-second or per-character model costs more for a longer request: output.billed_quantity is the size it was billed on, in output.billed_unit. That unit is the one the size was MEASURED in, so convert it into the rate''s own unit before multiplying - a model listed per minute reports seconds. Setting a param cannot change the size billed - it is derived from the request.", "credential_source=''user'' runs on a key you configured yourself and the platform bills nothing for that node; ''platform'' uses the platform''s key at the platform price.", "The node FAILS when no asset comes back rather than continuing with an empty file - the call has already been charged by then, and a downstream node running on nothing would hide it.", "Chain into core:media to edit what was generated: generate a clip, generate a voice over, then mux_audio the two together."]'::jsonb,
    '[]'::jsonb,
    '["generate", "generation", "image", "video", "audio", "voice", "music", "tts", "speech", "text-to-image", "text-to-video", "text-to-speech", "prompt", "asset"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label,
    category = EXCLUDED.category,
    variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description,
    parameters = EXCLUDED.parameters,
    outputs = EXCLUDED.outputs,
    global_variables = EXCLUDED.global_variables,
    edge_ports = EXCLUDED.edge_ports,
    concepts = EXCLUDED.concepts,
    examples = EXCLUDED.examples,
    keywords = EXCLUDED.keywords,
    enabled = EXCLUDED.enabled,
    updated_at = NOW();
