-- V447: say what "waits for the sub-workflow" actually means, now that it is true.
--
-- The node fires a trigger on the target and then reads that epoch's step outputs. Firing
-- returns as soon as the engine has nothing left to run INLINE, which also happens when a node
-- inside the target YIELDS: a wait longer than 3 seconds, a user approval, an interface awaiting
-- __continue, or an agent handed to the async queue (agent nodes take that path by default, so
-- this is the ordinary case). The rest of the target then runs later, on the resume path.
--
-- Until now the node read the step rows at that instant and reported success over them, so a
-- caller could receive a PREFIX of the target's work as a finished result: measured in production
-- at 4 steps out of 11, in a run that ended green. The node now waits for the epoch to actually
-- close before reading, inside the timeoutSeconds budget, and fails if that budget runs out.
--
-- Two consequences an agent must be able to read, neither of which was written down anywhere:
--   1. A target that is merely slow (an agent, a long API call) now WORKS instead of returning
--      half its data. It just takes as long as it takes, up to timeoutSeconds.
--   2. A target parked on a PERSON (approval, interface) can never be resolved by waiting, so it
--      spends the budget and fails. That needs a different workflow shape, not a bigger timeout.
--
-- And the timeout was never a leash: it bounds how long THIS node waits, never the target, which
-- keeps running and can still perform external actions afterwards.
--
-- `parameters` also changes, in one place only: the advertised timeout range. It said 1-3600 and
-- was enforced nowhere; the ceiling is now real, and it is 1500, because four joins in the engine
-- give a parallel branch 30 minutes to finish, measured from when the branch was dispatched, so the
-- ceiling leaves headroom for whatever ran before this node in that branch. The output contract {result, success, subRunId, subWorkflowId} is
-- untouched by this fix.
SET search_path TO orchestrator, public;

UPDATE node_type_documentation
SET description = 'Executes another workflow by firing one of its triggers, waits for that epoch '
        || 'to actually finish, and returns its step outputs. PRECONDITION: the target workflow '
        || 'must already have a live run (WAITING_TRIGGER, RUNNING or PAUSED); this node never '
        || 'creates one, so run the target once before calling it. Passes input data via '
        || 'inputMapping. Waits up to timeoutSeconds for the target to complete, and FAILS if it '
        || 'has not: it never returns a partially finished target as a result. Includes '
        || 'anti-recursion protection via a configurable maxDepth limit and a cycle guard on the '
        || 'workflow-id call chain. Useful for composing complex workflows from reusable '
        || 'building blocks.',
    concepts = '["PRECONDITION: the target workflow must already have a live run (WAITING_TRIGGER, RUNNING or PAUSED). This node NEVER creates one - execute the target workflow once first, otherwise the node fails with \"No active run found for workflow X. Start the workflow first.\"",
                 "Fires a trigger on the target workflow''s existing run, appending a new epoch to it. When no triggerId is given the first trigger that is not of type workflow or error is chosen",
                 "If the target workflow is PINNED, only its runs AT THE PINNED VERSION are eligible - a live run at any other version is invisible and you will get \"No active run found\". Re-run the target at its pinned version, or unpin it",
                 "The target workflow must belong to the caller: same organization, or the same owner. A target in another workspace is reported as not found",
                 "Input data is passed to the sub-workflow as trigger data",
                 "Waits for the target epoch to FINISH, then returns its outputs. A target that pauses part-way (an agent working, a wait longer than 3 seconds, a user approval, an interface awaiting __continue) is waited for rather than reported as done, so this node never returns a half-finished target as a success",
                 "A target that is simply SLOW is fine: it holds this node for as long as it needs, up to timeoutSeconds (default 300, maximum 1500). If it needs longer than the maximum, split it so each call finishes inside one budget",
                 "Stepping a workflow one node at a time cannot wait: in that mode a target that has not already finished makes this node fail immediately instead of blocking. Run the workflow normally to let it wait",
                 "A target waiting on a PERSON (a user approval, or an interface awaiting __continue) can never be released by waiting, so it will spend the whole budget and fail. No timeout value fixes that: put the approval or the interface in THIS workflow instead of inside the target",
                 "TIMEOUT DOES NOT STOP THE TARGET. When the budget runs out this node fails, but the target run keeps executing and can still perform external actions (posting, sending, charging). Its outputs do not exist yet, so nothing downstream of this node runs. Stop that run directly if you need it stopped, and never place an irreversible action after a sub-workflow call on the assumption that a timeout cancelled it",
                 "Anti-recursion: tracks call depth and fails if maxDepth is exceeded; a workflow already in the call chain is refused as a cycle",
                 "Sub-workflow failure propagates as this node''s failure",
                 "Access sub-workflow results: {{core:label.output.result.mcp:step_key.output.field}}"]'::jsonb,
    parameters = jsonb_set(
        parameters,
        '{timeoutSeconds}',
        '{"type": "integer", "default": 300, "required": false, "description": "How long to wait for the sub-workflow to FINISH, in seconds. When it runs out this node fails and the sub-workflow keeps running: the value never bounds the sub-workflow itself. Range: 1-1500, values above are reduced to 1500. It also covers time spent queueing behind other calls to the SAME target from this run, so several calls to one target should each allow for the others."}'::jsonb,
        true),
    updated_at = NOW()
WHERE type = 'sub_workflow';
