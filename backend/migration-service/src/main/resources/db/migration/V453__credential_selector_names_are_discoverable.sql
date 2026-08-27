-- The credential_selector description told the agent what the field means and never
-- told it where valid values come from. An agent cannot invent an account name, so
-- without this it can only set the field to an expression it hopes resolves to
-- something, and the fail-closed behaviour then turns every guess into a failed run.
-- get_connected_services and credential(action='list') already return exactly the
-- (name, integration, status) triples this field matches on, so the fix is to name
-- them here. Both are named because they sit on different surfaces: an agent building
-- a workflow has the first, an agent in a conversation has the second, and naming one
-- leaves the other reading a cross-reference it cannot follow.
--
-- The unselectable cases are spelled out because each one fails the step rather
-- than degrading, and three of them are invisible in the listing the agent just read:
-- a status of 'expiring' reads as "still works" there and is refused here
-- (the run-time matcher narrows to 'active' deliberately; the repository's own queries
-- do not, so reading them is what makes this look like an accident), a duplicate
-- name looks like an ordinary name, and a workspace mismatch looks like nothing at all.
--
-- V442 wrote this same key. Rewriting only {credential_selector,description} leaves
-- the type/required/example siblings and every other mcp parameter untouched.
UPDATE node_type_documentation
SET parameters = jsonb_set(parameters, '{credential_selector,description}', to_jsonb('WHICH of the owner''s own accounts this step runs on, when the answer is only known at run time. Give an expression that resolves to the NAME of one of the owner''s credentials for this integration (its numeric id also works). To learn which names exist, call get_connected_services, or credential(action=''list'') where that is the one you have: each returns one entry per account with a name, an integration and a status, and this field matches an entry whose integration is this step''s. Two entries sharing an integration is exactly the case this field is for. These make an entry unselectable, and each FAILS the step rather than falling back: a status other than ''active'' (an expiring token is refused too, not only needs_reauth and error; only the owner can Reconnect a revoked one, and a misconfigured one needs an admin); a name shared with another active entry of the same integration, since picking either would be picking at random; a name that is a positive whole number, which is read as a credential id instead; and a name that is listed under a different workspace than the one the workflow runs in. Matching ignores capitalisation and surrounding spaces and nothing else, so copy the name rather than reformatting it. This is what lets ONE workflow serve several accounts instead of being duplicated per account: resolve it from a table row, a trigger field, or {{item}} inside a split, and the same step publishes to a different account per item. Leave it out and the step runs on the account the owner pinned on it, or on their default one for the integration, which is the right answer for every step you create unless the person asked for per-account behaviour. If it resolves to nothing, or names no credential of this integration, the step FAILS and nothing is sent: it never falls back to the default account, because that would act on the wrong account and report success. WHOEVER CAN WRITE THE RESOLVED VALUE CHOOSES THE ACCOUNT, so resolve it from something the workflow owner controls (a table row, a variable, a split item) rather than from a field an untrusted caller fills in, such as a public webhook body or a form on a shared application. A mocked step never calls the provider, so it does not resolve this either: mock_mode passes whatever the expression says, including nothing.'::text), true)
WHERE type = 'mcp'
  AND parameters ? 'credential_selector';

-- V442's example was '{{trigger.output.account}}', which is neither supported trigger
-- form ({{trigger.<column>}} for a table/datasource trigger, {{trigger:<label>.output.<field>}}
-- otherwise). It is the one value in this doc an agent copies verbatim, and it resolves
-- to nothing, which fail-closed means a failed step. The split form replaces it because
-- it is also the shape that makes the field worth having.
UPDATE node_type_documentation
SET parameters = jsonb_set(parameters, '{credential_selector,example}', to_jsonb('{{item.ig_account}}'::text), true)
WHERE type = 'mcp'
  AND parameters ? 'credential_selector';

-- Same gap on the concepts list: the account-per-run idea is stated there, and an
-- agent reading only that would still not know how to find a name.
UPDATE node_type_documentation
SET concepts = COALESCE(concepts, '[]'::jsonb) || jsonb_build_array('get_connected_services, or credential(action=''list''), lists the owner''s accounts with their names, so it is how you find the value a credential_selector has to resolve to. Only an entry whose status is ''active'' can be selected that way.'::text)
WHERE type = 'mcp'
  AND NOT (COALESCE(concepts, '[]'::jsonb) @> jsonb_build_array('get_connected_services, or credential(action=''list''), lists the owner''s accounts with their names, so it is how you find the value a credential_selector has to resolve to. Only an entry whose status is ''active'' can be selected that way.'::text));
