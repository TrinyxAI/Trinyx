-- Loop node docs: state the stop rule the builder actually enforces.
--
-- Found while probing a prod loop fix (2026-07-31): the docs marked BOTH `condition` and
-- `max_iterations` as required:false, so an agent could reasonably supply neither - and then hit
-- a validation error at workflow(action='validate') with no hint from the parameter docs that at
-- least one was needed. (The stricter of the two validators additionally demanded `condition`
-- outright, which contradicted these docs and blocked the count-only loop entirely; that check is
-- fixed in WorkflowErrorChecker in the same change.)
--
-- Both params stay individually optional - that is accurate, either one alone is enough - but the
-- description now says so explicitly, from the agent's point of view: which action to call, and
-- what each choice means for when the loop stops.
UPDATE node_type_documentation
SET parameters = '{
  "condition": {
    "type": "string",
    "example": "{{mcp:api.output.has_more}} == true",
    "required": false,
    "description": "SpEL expression re-evaluated before every pass. The body runs while it is true. Omit it for a fixed number of passes - then max_iterations alone decides when the loop stops. REQUIRED UNLESS max_iterations is set: a loop with neither is rejected by workflow(action=''validate'')."
  },
  "max_iterations": {
    "type": "integer",
    "default": 10,
    "example": 5,
    "required": false,
    "description": "Hard ceiling on how many times the body runs, counting the first pass. Range: 1-10000. Reaching it stops the loop and sets output.reason to max_iterations_reached. REQUIRED UNLESS condition is set; keep it even with a condition, as the safety net for a condition that never turns false."
  }
}'::jsonb,
    description = 'While loop that repeatedly executes a body branch. Two ports: body (runs each pass) and exit (continues once the loop stops). It stops on the FIRST of: condition false, or max_iterations reached - read output.reason to know which. Supply at least one of condition / max_iterations.',
    updated_at = NOW()
WHERE type = 'loop';
