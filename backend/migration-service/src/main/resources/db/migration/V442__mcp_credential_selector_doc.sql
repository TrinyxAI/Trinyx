-- The mcp node can decide WHICH of the owner's accounts it runs on at RUN time.
--
-- Without this row the parameter exists in the creator and is invisible to every
-- agent, which is the half-shipped state this project has been bitten by before:
-- a parameter the code reads and the documentation never mentions is a feature
-- nobody uses, and a parameter documented but not read is one that is silently
-- dropped. The creator reads both spellings of it, so the
-- documentation must name it.
--
-- Targeted jsonb_set rather than a row rewrite: the parameters blob carries every
-- other mcp parameter and rewriting it whole would revert anything added to it
-- since, from this migration or from a parallel one.
UPDATE node_type_documentation
SET parameters = jsonb_set(
        COALESCE(parameters, '{}'::jsonb),
        '{credential_selector}',
        '{
           "type": "string",
           "required": false,
           "example": "{{trigger.output.account}}",
           "description": "WHICH of the owner''s own accounts this step runs on, when the answer is only known at run time. Give an expression that resolves to the NAME of one of the owner''s credentials for this integration (its numeric id also works). This is what lets ONE workflow serve several accounts instead of being duplicated per account: resolve it from a table row, a trigger field, or {{item}} inside a split, and the same step publishes to a different account per item. Leave it out and the step runs on the account the owner pinned in the builder, or on their default one for the integration - which is the right answer for every step you create unless the person asked for per-account behaviour. If it resolves to nothing, or names no credential of this integration, the step FAILS and nothing is sent: it never falls back to the default account, because that would act on the wrong account and report success. WHOEVER CAN WRITE THE RESOLVED VALUE CHOOSES THE ACCOUNT, so resolve it from something the workflow owner controls (a table row, a variable, a split item) rather than from a field an untrusted caller fills in, such as a public webhook body or a form on a shared application. One sharp edge: a value that is a positive whole number is read as a credential ID, so a credential whose NAME is a number cannot be selected by name. A mocked step never calls the provider, so it does not resolve this either: mock_mode passes whatever the expression says, including nothing."
         }'::jsonb,
        true)
WHERE type = 'mcp';

-- Same reason, on the concepts list the agent reads alongside the parameters.
UPDATE node_type_documentation
SET concepts = COALESCE(concepts, '[]'::jsonb) ||
        '["One workflow, several accounts: set credential_selector to an expression (a table column, a trigger field, or {{item}} under a split) instead of creating one workflow per account.",
          "A credential_selector that resolves to nothing FAILS the step. That is deliberate: the alternative is publishing to the owner''s default account and reporting success."]'::jsonb
WHERE type = 'mcp'
  AND NOT (COALESCE(concepts, '[]'::jsonb) @> '["One workflow, several accounts: set credential_selector to an expression (a table column, a trigger field, or {{item}} under a split) instead of creating one workflow per account."]'::jsonb);

-- The step also reports WHICH account served, so a run can be read back afterwards.
-- Undeclared, an agent cannot know the field exists and every silent-substitution
-- question stays unanswerable after the fact.
UPDATE node_type_documentation
SET outputs = jsonb_set(
        COALESCE(outputs, '{}'::jsonb),
        '{credential_selection}',
        '{
           "type": "object",
           "description": "Present only when this step chose its account at RUN time. Reports the expression that was used and what it resolved to: {selector, resolved_credential_name} or {selector, resolved_credential_id}. Read it to see which account the step ASKED for. It is the request, not a receipt: nothing in the response confirms it back, so treat it as what was requested rather than proof of what ran, e.g. {{mcp:<step label>.output.credential_selection.resolved_credential_name}}."
         }'::jsonb,
        true)
WHERE type = 'mcp';
