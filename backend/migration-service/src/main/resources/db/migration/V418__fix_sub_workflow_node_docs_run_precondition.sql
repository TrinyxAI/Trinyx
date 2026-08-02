-- V418: correct the agent-facing documentation of the sub_workflow node.
--
-- The V11 seed still describes the pre-2026-03-31 implementation: an "ephemeral
-- (one-shot) run pattern" that runs "with the same tenantId as the parent
-- workflow". Commit 74a473277 replaced that with the trigger-based pattern
-- (SubWorkflowNode.findActiveRun -> ReusableTriggerService), so both statements
-- have been false for months. An agent that follows the docs builds a node that
-- fails at runtime with "No active run found for workflow X. Start the workflow
-- first." and has no way to know why.
--
-- The examples are rewritten too: they were still built around the old "just add the
-- node" flow with no mention of the run precondition, and the first one now shows the
-- execute-then-add order an agent actually has to follow.
-- `parameters` and `outputs` are still accurate and are deliberately left untouched.
SET search_path TO orchestrator, public;

UPDATE node_type_documentation
SET description = 'Executes another workflow by firing one of its triggers, and waits for that '
        || 'epoch to finish. PRECONDITION: the target workflow must already have a live run '
        || '(WAITING_TRIGGER, RUNNING or PAUSED); this node never creates one, so run the '
        || 'target once before calling it. Passes input data via inputMapping, waits up to '
        || 'timeoutSeconds, and returns the target epoch''s step outputs. Includes '
        || 'anti-recursion protection via a configurable maxDepth limit and a cycle guard on '
        || 'the workflow-id call chain. Useful for composing complex workflows from reusable '
        || 'building blocks.',
    concepts = '["PRECONDITION: the target workflow must already have a live run (WAITING_TRIGGER, RUNNING or PAUSED). This node NEVER creates one - execute the target workflow once first, otherwise the node fails with \"No active run found for workflow X. Start the workflow first.\"",
                 "Fires a trigger on the target workflow''s existing run, appending a new epoch to it. When no triggerId is given the first trigger that is not of type workflow or error is chosen",
                 "If the target workflow is PINNED, only its runs AT THE PINNED VERSION are eligible - a live run at any other version is invisible and you will get \"No active run found\". Re-run the target at its pinned version, or unpin it",
                 "The target workflow must belong to the caller: same organization, or the same owner. A target in another workspace is reported as not found",
                 "Input data is passed to the sub-workflow as trigger data",
                 "The node waits synchronously for that epoch to complete (up to timeoutSeconds)",
                 "Anti-recursion: tracks call depth and fails if maxDepth is exceeded; a workflow already in the call chain is refused as a cycle",
                 "Sub-workflow failure propagates as this node''s failure",
                 "Access sub-workflow results: {{core:label.output.result.mcp:step_key.output.field}}"]'::jsonb,
    keywords = '["sub-workflow","sub_workflow","call","invoke","function","compose","reuse","nested","child","delegation","active run","trigger"]'::jsonb,
    examples = '["workflow(action=''execute'', id=''abc-123-def'') FIRST so the target has a live run (add version=<its pinned version> if it is pinned), then workflow(action=''add_node'', type=''sub_workflow'', label=''Call Enrichment'', params={workflowId: ''abc-123-def'', inputMapping: ''{{mcp:fetch_user.output.result}}'', timeoutSeconds: 120}, connect_after=''Fetch User'')",
                 "workflow(action=''add_node'', type=''sub_workflow'', label=''Run Validation'', params={workflowId: ''{{trigger:start.output.validation_workflow_id}}'', timeoutSeconds: 60, maxDepth: 3}, connect_after=''Start'')",
                 "workflow(action=''add_node'', type=''sub_workflow'', label=''Process Batch'', params={workflowId: ''batch-processor-id'', inputMapping: ''{{core:split.output.item}}'', timeoutSeconds: 300}, connect_after=''Split Items'')"]'::jsonb,
    updated_at = NOW()
WHERE type = 'sub_workflow';

-- No cross-type sweep of the dead `workflow_builder(` tool name here: V391 section 10
-- already replaces it in description / concepts / examples for every row, and V391 runs
-- before this migration in every environment. Rewriting this row's examples in full above
-- is what removes the last occurrence; a second sweep would match nothing.
