-- ============================================================================
-- V435: the generate node's documentation promised more than the platform does.
--
-- V429 told the agent, in `concepts[1]`: "A param the model does not accept, or
-- a value outside its limits, is refused BEFORE the provider is called - the
-- run costs nothing and the error names the accepted values." That was true
-- while every limit came from the generation descriptor, which is checked
-- before billing.
--
-- Some limits now come from the CATALOGUE instead: the values a provider
-- documents for the parameter, inherited so a reader choosing a voice is not
-- left typing an opaque identifier. Nothing checks those, deliberately, because
-- a stale or partial list would refuse values the provider accepts. They are
-- published with allowedEnforced=false, and a value outside one of them reaches
-- the provider and fails there - which is exactly the case the old sentence
-- said could not happen.
--
-- An agent that believes the old promise retries variations of a rejected
-- value, because it has been told the refusal is free. This says which limits
-- are free to test against and which are paid for.
--
-- A targeted UPDATE of one array element: every other column keeps whatever
-- V429, V433 or a later edit left there.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET concepts = jsonb_set(
        concepts,
        '{1}',
        to_jsonb(
            'A param the model does not accept is refused BEFORE the provider is called - the run costs '
            || 'nothing and the error names the accepted params, so correct it and run again. A value outside '
            || 'a limit is refused the same way, EXCEPT where that limit carries allowedEnforced=false: those '
            || 'values are what the provider documents, nothing checks them here, and one outside the list '
            || 'reaches the provider and fails there, after the call is paid for. Treat such a list as a '
            || 'suggestion and confirm the value with the person rather than trying others.'::text
        )
    ),
    updated_at = NOW()
WHERE type = 'generate'
  AND jsonb_typeof(concepts) = 'array'
  -- Only the sentence V429 wrote, so a hand-edited row is left alone and a
  -- re-run changes nothing.
  AND concepts->>1 LIKE 'A param the model does not accept, or a value outside its limits%';
