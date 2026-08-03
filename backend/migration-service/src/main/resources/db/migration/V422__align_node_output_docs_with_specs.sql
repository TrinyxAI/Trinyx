-- Align node_type_documentation.outputs with the NodeSpec contracts.
--
-- The agent reads node_type_documentation to learn a node's output field names. For the
-- 9 types below it was reading names the engine never persists, so an agent following
-- the documentation produced templates that cannot resolve. There is no runtime failure
-- for that. A node parameter goes through TemplateEngine.evaluateTemplate: mixed text
-- ("Client: {{...}}") hits resolveExpressions, where a null evaluation becomes an EMPTY
-- STRING plus a WARN, and a pure "{{...}}" returns NULL. Either way the run reports
-- COMPLETED and the parameter silently loses its content, which is indistinguishable from
-- a legitimately empty value. (The {{__UNRESOLVED__:<path>}} sentinel some code mentions
-- belongs to the INTERFACE rendering path, not to node parameters.)
--
-- Worked example, SWITCH: the docs advertised switch_value, matched_value, selected_case,
-- skipped_cases, selected_case_label and skipped_case_labels, none of which is written,
-- while selected_branches, skipped_branches and split_item_count were written and
-- documented nowhere. selected_case_label / selected_case DO appear in the spec, but only
-- as .aliases(), which are consulted when resolving an INPUT value and never determine the
-- stored key (GenericOutputSchemaMapper writes field.key() verbatim).
--
-- Each block below is generated as a faithful projection of the NodeSpec's declared
-- outputs, so descriptions come from the engine rather than being re-invented here.
-- Dynamic families keep the <...> convention already used by e.g. <column_name>.
--
-- One NodeSpec IS changed, in the same branch: EXTRACT_FROM_FILE gained the 7 text-mode
-- fields (mode, total_chunks, text_length, chunking_strategy, chunk_size, overlap,
-- chunk_unit) that the node already computed and that the docs already advertised, but
-- that the spec never declared, so GenericOutputSchemaMapper dropped them before
-- persistence. Declaring them is what makes the block below true rather than aspirational.
-- Every other block here is doc-side only.
--
-- APPROVAL and INTERFACE are deliberately absent. A signal-yielding node does not persist
-- its output when it yields; SignalResumeService writes a different set of fields on
-- resume. Projecting their NodeSpecs would document the yield-time shape and delete the
-- fields that actually land in the row, which is this bug in reverse.

UPDATE node_type_documentation
SET outputs = '{
  "response": {
    "type": "string",
    "description": "Agent''s text response"
  },
  "tokens_used": {
    "type": "number",
    "description": "Total tokens consumed"
  },
  "prompt_tokens": {
    "type": "number",
    "description": "Prompt/input tokens consumed"
  },
  "completion_tokens": {
    "type": "number",
    "description": "Completion/output tokens consumed"
  },
  "tool_calls": {
    "type": "number",
    "description": "Number of tool calls made by the agent"
  },
  "tool_calls_detail": {
    "type": "array",
    "description": "Detailed list of tool calls made by the agent (name, arguments, result)"
  },
  "iterations_used": {
    "type": "number",
    "description": "Number of LLM iterations used"
  },
  "duration_ms": {
    "type": "number",
    "description": "Execution duration in milliseconds"
  },
  "model": {
    "type": "string",
    "description": "LLM model used"
  },
  "provider": {
    "type": "string",
    "description": "LLM provider used"
  },
  "split_item_count": {
    "type": "number",
    "description": "Total number of items when executed inside a split context"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'agent';

UPDATE node_type_documentation
SET outputs = '{
  "node_type": {
    "type": "string",
    "description": "Internal node type identifier (always ''CLASSIFY'')"
  },
  "selected_category": {
    "type": "string",
    "description": "The category selected by the classifier"
  },
  "selected_category_index": {
    "type": "number",
    "description": "Zero-based index of the selected category (used for branch routing)"
  },
  "confidence": {
    "type": "number",
    "description": "Confidence score of the classification"
  },
  "reasoning": {
    "type": "string",
    "description": "Explanation for the classification decision"
  },
  "tokens_used": {
    "type": "number",
    "description": "Total tokens consumed"
  },
  "duration_ms": {
    "type": "number",
    "description": "Execution duration in milliseconds"
  },
  "model": {
    "type": "string",
    "description": "LLM model used"
  },
  "provider": {
    "type": "string",
    "description": "LLM provider used"
  },
  "split_item_count": {
    "type": "number",
    "description": "Total number of items when executed inside a split context (used for branch routing)"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'classify';

UPDATE node_type_documentation
SET outputs = '{
  "selected_branch": {
    "type": "string",
    "description": "The branch that was selected (if/else/elseif_N)"
  },
  "selected_branch_index": {
    "type": "number",
    "description": "Zero-based index of the selected branch (used for routing on resume/restart)"
  },
  "skipped_branches": {
    "type": "array",
    "description": "Branches that were not executed"
  },
  "evaluations": {
    "type": "array",
    "description": "Evaluation details for each condition"
  },
  "split_item_count": {
    "type": "number",
    "description": "Total number of items when executed inside a split context (used for branch routing)"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'decision';

UPDATE node_type_documentation
SET outputs = '{
  "items": {
    "type": "array",
    "description": "Extracted rows/items"
  },
  "format": {
    "type": "string",
    "description": "Detected file format"
  },
  "rowCount": {
    "type": "number",
    "description": "Number of rows extracted"
  },
  "columnCount": {
    "type": "number",
    "description": "Number of columns detected"
  },
  "columns": {
    "type": "array",
    "description": "Column names/headers"
  },
  "success": {
    "type": "boolean",
    "description": "Whether extraction was successful"
  },
  "mode": {
    "type": "string",
    "description": "Extraction mode that ran: ''structured'' (CSV/Excel/JSON rows) or ''text''"
  },
  "total_chunks": {
    "type": "number",
    "description": "Text mode: number of chunks the extracted text was split into. Equals the length of items. Absent in structured mode"
  },
  "text_length": {
    "type": "number",
    "description": "Text mode: character length of the extracted text before chunking. Absent in structured mode"
  },
  "chunking_strategy": {
    "type": "string",
    "description": "Text mode with chunking enabled: the strategy used to split the text. Absent when chunking is off"
  },
  "chunk_size": {
    "type": "number",
    "description": "Text mode with chunking enabled: configured chunk size, expressed in chunk_unit. Absent when chunking is off"
  },
  "overlap": {
    "type": "number",
    "description": "Text mode with chunking enabled: overlap between consecutive chunks, expressed in chunk_unit. Absent when chunking is off"
  },
  "chunk_unit": {
    "type": "string",
    "description": "Text mode with chunking enabled: unit chunk_size and overlap are counted in. Absent when chunking is off"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'extract_from_file';

UPDATE node_type_documentation
SET outputs = '{
  "node_type": {
    "type": "string",
    "description": "Internal node type identifier (always ''GUARDRAIL'')"
  },
  "passed": {
    "type": "boolean",
    "description": "Whether the content passed all guardrail checks"
  },
  "violations": {
    "type": "array",
    "description": "List of violations found"
  },
  "details": {
    "type": "object",
    "description": "Detailed guardrail evaluation results"
  },
  "sanitized": {
    "type": "string",
    "description": "Sanitized version of the input if applicable"
  },
  "tokens_used": {
    "type": "number",
    "description": "Total tokens consumed"
  },
  "duration_ms": {
    "type": "number",
    "description": "Execution duration in milliseconds"
  },
  "model": {
    "type": "string",
    "description": "LLM model used"
  },
  "provider": {
    "type": "string",
    "description": "LLM provider used"
  },
  "split_item_count": {
    "type": "number",
    "description": "Total number of items when executed inside a split context"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'guardrail';

UPDATE node_type_documentation
SET outputs = '{
  "merged_branches": {
    "type": "array",
    "description": "List of branch labels that were merged"
  },
  "sources": {
    "type": "object",
    "description": "Each source branch output keyed by node ID"
  },
  "merged_items": {
    "type": "array",
    "description": "All items collected from successful branches"
  },
  "source_count": {
    "type": "number",
    "description": "Total number of source branches"
  },
  "success_count": {
    "type": "number",
    "description": "Number of branches that completed successfully"
  },
  "strategy": {
    "type": "string",
    "description": "Merge strategy used"
  },
  "item_count": {
    "type": "number",
    "description": "Total number of merged items"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'merge';

UPDATE node_type_documentation
SET outputs = '{
  "error_message": {
    "type": "string",
    "description": "The resolved error message that caused the failure"
  },
  "error_code": {
    "type": "string",
    "description": "Optional error code for programmatic handling"
  },
  "stopped_at": {
    "type": "datetime",
    "description": "ISO timestamp when the error stop was triggered"
  },
  "status": {
    "type": "string",
    "description": "Final status: always ''failed'' for stop on error"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'stop_on_error';

UPDATE node_type_documentation
SET outputs = '{
  "groups": {
    "type": "array",
    "description": "List of group results with aggregated values"
  },
  "total_groups": {
    "type": "number",
    "description": "Count of distinct groups"
  },
  "total_items": {
    "type": "number",
    "description": "Total items processed"
  },
  "aggregation_count": {
    "type": "number",
    "description": "Number of aggregation operations applied"
  },
  "<alias>": {
    "type": "any",
    "description": "One field per configured aggregation alias, holding that aggregation result."
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'summarize';

UPDATE node_type_documentation
SET outputs = '{
  "selected_branches": {
    "type": "string",
    "description": "Label of the case that matched, or the case type when the case has no label. Singular despite the plural name, and asymmetric with skipped_branches, which is a real array: SwitchNode never emits selected_branches, so GenericOutputSchemaMapper resolves it through the aliases selected_case_label then selected_case, both strings"
  },
  "selected_case_index": {
    "type": "number",
    "description": "Index of the selected case for state reconstruction"
  },
  "evaluations": {
    "type": "array",
    "description": "Evaluation details for each case"
  },
  "skipped_branches": {
    "type": "array",
    "description": "Cases that were not executed"
  },
  "split_item_count": {
    "type": "number",
    "description": "Total number of items when executed inside a split context (used for branch routing)"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'switch';

