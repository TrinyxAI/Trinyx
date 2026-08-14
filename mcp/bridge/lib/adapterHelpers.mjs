// Monotonic counter for synthetic tool ids - `Date.now()` alone collides
// when two tool calls land in the same millisecond. Module-scope so the
// counter survives across calls and adapters. Exported so adapters with
// their own dispatch sites (e.g. codex command_execution / web_search) use
// the same counter as the shared helpers.
let _synthIdCounter = 0;
export function synthIdFor(providerKey) {
  _synthIdCounter = (_synthIdCounter + 1) >>> 0;
  return `${providerKey}_${Date.now()}_${_synthIdCounter}`;
}

// The single error renderer, shared with the producer side (buildFailureContent) so the same
// error value cannot come out differently at the two ends of the channel.
import { errorToText } from './toolContent.mjs';

/**
 * Shared helpers used by every CLI adapter (claude, codex, gemini, mistral).
 *
 * Before this module each adapter inlined its own copy of:
 *   - per-call usage push + cumulative recompute + updateState
 *   - tool_call dispatch (pendingToolCalls.set + orderedEntries.push + publish)
 *   - tool_result dispatch (pending lookup + toolResults.push + publish + delete)
 *
 * Result: ~400 duplicated lines and one prod regression where the Claude
 * adapter's variant of the usage loop diverged silently from the others
 * (closure trap + double counting). This module makes the canonical shape the
 * only shape - adapters now describe data, not control flow.
 */

/**
 * Push one API call's usage onto ctx.state.perCallUsages and recompute the
 * cumulative `usage` field that the budget guard reads mid-run.
 *
 * The cumulative is a SUM across perCallUsages - that's the correct billing
 * semantics for every provider where each push is a distinct billable call.
 * Claude is the only provider where the same logical "user turn" can produce
 * two billable calls (extended thinking pause/resume); both are intentionally
 * counted because Anthropic charges them separately.
 *
 * @param {object} ctx - adapter execution context (from server.mjs)
 * @param {object} call - { promptTokens, completionTokens, cacheCreationInputTokens?, cacheReadInputTokens?, cachedTokens?, reasoningTokens? }
 */
export function recordCallUsage(ctx, call) {
  const entry = {
    promptTokens: call.promptTokens || 0,
    completionTokens: call.completionTokens || 0,
    cacheCreationInputTokens: call.cacheCreationInputTokens || 0,
    cacheReadInputTokens: call.cacheReadInputTokens || 0,
    // OpenAI-style cached prompt subset (codex cached_input_tokens) - billed
    // at the provider's cached discount by auth-service.
    cachedTokens: call.cachedTokens || 0,
    reasoningTokens: call.reasoningTokens || 0,
  };
  ctx.state.perCallUsages.push(entry);
  ctx.state.iterationTimestamps.push(Date.now());

  let totalInput = 0;
  let totalOutput = 0;
  for (const c of ctx.state.perCallUsages) {
    totalInput += c.promptTokens;
    totalOutput += c.completionTokens;
  }
  ctx.updateState({ usage: { promptTokens: totalInput, completionTokens: totalOutput } });
  return { totalInput, totalOutput, callIndex: ctx.state.perCallUsages.length };
}

/**
 * Dispatch a tool call: register it in pendingToolCalls, append to orderedEntries
 * (for the canonical replay log), and publish over Redis.
 *
 * Adapters call this *after* any provider-specific dedup (e.g. Claude's
 * seenToolUseIds + pause_turn skip). Helpers do not see provider-specific
 * dedup state - that's intentional, see ctx.adapterState in server.mjs.
 *
 * @param {object} ctx
 * @param {object} call - { toolId, toolName, argsStr, extras? }
 *   `extras` is shallow-merged into the pendingToolCalls entry (used by
 *   claude-adapter to remember `attachmentFileName`).
 */
export async function dispatchToolCall(ctx, { toolId, toolName, argsStr, extras = {} }) {
  ctx.pendingToolCalls.set(toolId, {
    toolName,
    arguments: argsStr,
    startTime: Date.now(),
    ...extras,
  });
  ctx.orderedEntries.push({
    type: 'tool_call',
    id: toolId,
    toolName,
    arguments: argsStr,
    timestamp: Date.now(),
  });
  await ctx.publisher.publishToolCall(toolName, toolId, argsStr);
}

/**
 * Dispatch a tool result: look up the pending entry, build the canonical
 * toolResults record, publish over Redis, then drop the pending entry.
 *
 * @param {object} ctx
 * @param {object} result - { toolId, isError, content, errorMsg?, metadata?, fallbackToolName? }
 *   `errorMsg` is the CLI's own reason; on failure it is combined with `content` (see the
 *   fallback policy below), never used alone.
 *   `fallbackToolName` is used if the pending entry is missing (race / unknown id).
 *   `metadata` is shallow-merged into the published metadata; helpers will also
 *   set `label`/`toolName` from the pending entry's `attachmentFileName` if
 *   present (Claude `view_attachment` flow).
 */
export async function dispatchToolResult(ctx, { toolId, isError, content, errorMsg, metadata = {}, fallbackToolName = 'unknown' }) {
  const pending = ctx.pendingToolCalls.get(toolId);
  if (!pending) {
    // Loud warning so a malformed/out-of-order CLI stream surfaces in logs
    // instead of silently producing an "unknown" tool entry that masks bridge
    // bugs. Tests rely on this exact prefix.
    console.warn(`[BRIDGE] dispatchToolResult: no pending tool for id=${toolId} (using fallback "${fallbackToolName}")`);
  }
  const toolName = pending?.toolName || fallbackToolName;
  const durationMs = pending ? Date.now() - pending.startTime : null;

  const enrichedMetadata = { ...metadata };
  if (pending?.attachmentFileName) {
    enrichedMetadata.label = pending.attachmentFileName;
    enrichedMetadata.toolName = 'view_attachment';
  }

  // SINGLE fallback policy for every call site that reaches here. Call sites pass the raw
  // CLI's own message and nothing else, so two branches handling the same event can no
  // longer disagree on what the model reads (they did: one published the reason, the other
  // an empty string). On failure prefer the CLI message, then the payload text - which
  // carries the backend's actionable guidance, e.g. "retry with force=true" - then a
  // generic label, because `success:false` with an empty `error` renders a failure with no
  // message at all.
  const errorText = isError
    ? (errorMsg || content || 'Tool call failed (no message returned by the CLI)')
    : null;
  // Keep BOTH signals when the CLI gives both, IN THE SAME FIELD. Only one crosses the wire:
  // publishToolResult takes the content, and redis-publisher then derives `event.error` from
  // it, so anything left only in `error` reaches neither the frontend nor the model history.
  // Concatenating is what stops the choice between the transport reason and the backend's
  // actionable guidance from losing one of them. When the payload is empty the error text
  // stands in for it, so a failure is never published as a blank result.
  const publishedContent = !isError
    ? content
    : (content && errorMsg && content !== errorMsg ? `${errorMsg}\n\n${content}` : (content || errorText));

  ctx.toolResults.push({
    toolCall: { id: toolId, toolName, arguments: pending?.arguments || '{}' },
    success: !isError,
    content: publishedContent,
    error: errorText,
    durationMs,
    metadata: enrichedMetadata,
  });

  await ctx.publisher.publishToolResult(toolId, toolName, !isError, durationMs, publishedContent, enrichedMetadata);
  ctx.pendingToolCalls.delete(toolId);
}

/**
 * The payload a CLI item carries for a finished tool call.
 *
 * Codex-style CLIs put it under `result`, the *_result / *_output events under `output` or
 * `content` or `text`. Returned RAW: `extractToolResultAndMetadata` owns every shape
 * (string, MCP block array, whole CallToolResult envelope) and serialising here is what
 * destroyed the trusted `__BRIDGE_META__` metadata on the codex/gemini/mistral paths.
 */
// One order per event shape, each matching what its branch read before this change.
// `result` is only ever APPENDED where it was absent, never promoted above a field that
// already won. Not strictly behaviour-preserving: an EMPTY leading field no longer wins
// (`{result:'', output:'X'}` used to yield '' on the codex branch and now yields 'X'),
// which is the point of picking the first MEANINGFUL field.
export const CODEX_PAYLOAD_FIELDS = ['result', 'output', 'content', 'text'];
export const FLAT_PAYLOAD_FIELDS = ['output', 'content', 'result', 'text'];
export const RESULT_EVENT_PAYLOAD_FIELDS = ['output', 'content', 'text', 'result'];

export function resolveItemPayload(item, fields = CODEX_PAYLOAD_FIELDS) {
  if (!item) return '';
  // First MEANINGFUL field wins. Neither `||` nor `??` alone is right: `??` keeps an empty
  // `result` and hides a real `output` beside it, while `||` discards a legitimate `0`.
  // The field ORDER stays per-branch: codex-style events lead with `result`, the flat
  // *_result events lead with `output`. Imposing one order on both silently changed which
  // field wins when a CLI sends several, which is a behaviour change no test asked for.
  for (const name of fields) {
    const candidate = item[name];
    if (candidate !== null && candidate !== undefined && candidate !== '') return candidate;
  }
  return '';
}

/**
 * Whether a CLI reported an explicit error object/message on the item.
 *
 * Deliberately narrower than `!= null`: `error: false`, `error: ''` and `error: 0` are common
 * "no error" idioms, and treating them as failures would flip successful calls to failed on
 * the *_result / *_output branches, which never applied this check before.
 */
function hasCliError(item) {
  const err = item.error;
  if (err === null || err === undefined || err === false || err === '' || err === 0) return false;
  if (typeof err === 'number' && Number.isNaN(err)) return false;
  // An Error instance has no enumerable own keys, so the object test below would call it
  // "empty" while resolveItemErrorMessage happily returns its message. Decide it here.
  if (err instanceof Error) return true;
  // An EMPTY object or array is the same "no error" idiom, emitted by Go/protobuf-style
  // serialisers. Treating it as a failure flips a successful call to failed AND, through the
  // fallback policy, turns its own success payload into the error message the model reads.
  if (Array.isArray(err)) return err.length > 0;
  if (typeof err === 'object') return Object.keys(err).length > 0;
  return true;
}

/**
 * Whether a CLI item reports a failed tool call.
 *
 * The envelope check is the load-bearing one: Codex can report a failure with
 * `status: 'completed'` and no `item.error`, carrying it only as `isError` on the
 * CallToolResult. Without it the bridge publishes SUCCESS for a failed tool and the agent
 * reads an error body as a normal result - a correctness bug, not a display one.
 * Strict `=== true` so a string/array/null payload can never be mistaken for an envelope.
 */
const FAILED_STATUSES = new Set(['failed', 'error', 'cancelled', 'canceled', 'aborted', 'timeout']);

export function resolveItemError(item, fields = CODEX_PAYLOAD_FIELDS) {
  if (!item) return false;
  // The envelope flag is read from the payload that was actually SELECTED, not from any
  // field that happens to carry one: `{output:'everything is fine', result:{isError:true}}`
  // otherwise publishes that success text with success=false.
  const payload = resolveItemPayload(item, fields);
  return FAILED_STATUSES.has(item.status)
    || item.is_error === true
    || hasCliError(item)
    || payload?.isError === true;
}

/**
 * The CLI's own error message, when it provides one.
 *
 * Falls back to a serialisation for a structured error such as `{code: 500}`: returning null
 * there sent the model a generic label and dropped the only diagnostic the CLI gave.
 */
export function resolveItemErrorMessage(item) {
  if (!item) return null;
  return errorToText(item.error) || null;
}

/**
 * Increment the per-iteration turn counter. Used by every adapter (claude
 * via the assistant case, codex on turn.completed, gemini/mistral on the
 * shared assistant + turn_complete paths). Centralised so a future bug in
 * one adapter can't silently double-count or skip a turn.
 *
 * Note: distinct from `ctx.updateState({ numTurns: msg.num_turns })` which
 * adapters use at end-of-run to OVERRIDE with the canonical CLI total.
 */
export function incrementTurn(ctx) {
  ctx.updateState({ numTurns: (ctx.state?.numTurns || 0) + 1 });
}

/**
 * Handle a "flat" CLI message - a single typed envelope per event rather
 * than the nested item.* / assistant.content[] formats. Used by Gemini and
 * Mistral CLIs (any CLI that emits one of these top-level types):
 *
 *   content / text                          - direct text output
 *   content_block_delta / stream_event       - streaming text/thinking deltas
 *   tool_use / function_call / tool_call    - single tool invocation
 *   tool_result / function_response / tool_call_result - single tool result
 *   thinking / reasoning                     - single reasoning block
 *
 * Returns `true` if the message was handled, `false` otherwise so the caller
 * can fall through to its provider-specific branches (turn_complete, system,
 * result, error, ...).
 *
 * @param {object} msg
 * @param {object} ctx
 * @param {object} opts
 * @param {string} opts.providerKey - for synthetic ids
 * @returns {Promise<boolean>}
 */
export async function handleFlatCliMessage(msg, ctx, opts) {
  const { publisher, pendingToolCalls, thinkingSections, orderedEntries } = ctx;
  const providerKey = opts.providerKey;
  const synthId = () => synthIdFor(providerKey);

  // Direct text content
  if (msg.type === 'content' || msg.type === 'text') {
    const text = msg.text || msg.content || '';
    if (text) {
      await publisher.publishContent(text);
      ctx.updateState({ fullContent: ctx.getContent() + text });
    }
    return true;
  }

  // Streaming deltas
  if (msg.type === 'content_block_delta' || msg.type === 'stream_event') {
    const delta = msg.delta || msg.event?.delta;
    if (delta?.type === 'text_delta' && delta.text) {
      await publisher.publishContent(delta.text);
      ctx.updateState({ fullContent: ctx.getContent() + delta.text });
    }
    if (delta?.type === 'thinking_delta' && delta.thinking) {
      await publisher.publishThinking(delta.thinking);
      thinkingSections.push({ title: '', content: delta.thinking });
    }
    return true;
  }

  // Tool invocation
  if (msg.type === 'tool_use' || msg.type === 'function_call' || msg.type === 'tool_call') {
    const toolId = msg.id || msg.call_id || synthId();
    let toolName = msg.name || msg.tool || msg.function?.name || 'unknown';
    toolName = ctx.stripMcpPrefix(toolName);
    const argsStr = typeof msg.arguments === 'string'
      ? msg.arguments
      : JSON.stringify(msg.arguments || msg.args || msg.input || msg.function?.arguments || {});
    await dispatchToolCall(ctx, { toolId, toolName, argsStr });
    return true;
  }

  // Tool result
  if (msg.type === 'tool_result' || msg.type === 'function_response' || msg.type === 'tool_call_result') {
    const toolId = msg.call_id || msg.tool_use_id || msg.id;
    // Same resolvers as the codex-style branches: this path used to hand-roll both the field
    // priority and the error test, so an envelope reporting `isError` was published as a
    // SUCCESS here while the very same envelope was correctly failed elsewhere.
    const { content: cleanContent, metadata } = ctx.extractToolResultAndMetadata(
      resolveItemPayload(msg, FLAT_PAYLOAD_FIELDS)
    );
    await dispatchToolResult(ctx, {
      toolId,
      isError: resolveItemError(msg, FLAT_PAYLOAD_FIELDS),
      content: cleanContent,
      errorMsg: resolveItemErrorMessage(msg),
      metadata,
      fallbackToolName: msg.name || 'unknown',
    });
    return true;
  }

  // Thinking
  if (msg.type === 'thinking' || msg.type === 'reasoning') {
    const thinking = msg.text || msg.content || msg.thinking || '';
    if (thinking) {
      await publisher.publishThinking(thinking);
      thinkingSections.push({ title: '', content: thinking });
      orderedEntries.push({ type: 'thinking', title: '', content: thinking, timestamp: Date.now() });
    }
    return true;
  }

  return false;
}

/**
 * Build the stdin payload for CLIs that read the prompt from stdin (codex,
 * gemini, mistral). Three adapters had identical "prepend system prompt with
 * '\n\n---\n\n' separator" logic - centralised here so the separator can be
 * changed in one place if a CLI ever objects to it.
 */
export const PROMPT_SEPARATOR = '\n\n---\n\n';
export function buildStdinPayload(systemPrompt, prompt) {
  return systemPrompt ? `${systemPrompt}${PROMPT_SEPARATOR}${prompt}` : prompt;
}

/**
 * Handle a Claude-shaped `assistant` event for adapters that accept the
 * Anthropic block format (Gemini and Mistral CLIs proxy it). Iterates
 * `msg.message.content[]` and publishes text / tool_use / thinking blocks.
 *
 * Does NOT include Claude's own snapshot/pause_turn dedup machinery - those
 * are protocol quirks specific to the official `claude` CLI's
 * `--output-format stream-json --verbose`. Other CLIs that just emit a
 * single assistant block per turn don't need them.
 *
 * @param {object} msg - parsed NDJSON event with `msg.message.content[]`
 * @param {object} ctx - adapter context
 * @param {object} opts
 * @param {string} opts.providerKey - for synthetic ids ('gemini', 'mistral', ...)
 * @param {(usage: object, ctx: object) => void} [opts.recordUsage]
 */
export async function handleClaudeStyleAssistantMessage(msg, ctx, opts) {
  const { publisher, thinkingSections, orderedEntries } = ctx;
  const contentBlocks = msg.message?.content || [];
  const providerKey = opts.providerKey;
  const recordUsage = opts.recordUsage || (() => {});
  const synthId = () => synthIdFor(providerKey);

  if (msg.message?.model) ctx.updateState({ cliModel: msg.message.model });
  if (msg.message?.usage) recordUsage(msg.message.usage, ctx);

  for (const block of contentBlocks) {
    if (block.type === 'text' && block.text) {
      await publisher.publishContent(block.text);
      ctx.updateState({ fullContent: ctx.getContent() + block.text });
    }

    if (block.type === 'tool_use' || block.type === 'function_call') {
      const toolId = block.id || synthId();
      let toolName = block.name || block.tool || 'unknown';
      toolName = ctx.stripMcpPrefix(toolName);
      const argsStr = JSON.stringify(block.input || block.arguments || {});
      await dispatchToolCall(ctx, { toolId, toolName, argsStr });
    }

    if ((block.type === 'thinking' || block.type === 'reasoning') && (block.thinking || block.text)) {
      const thinking = block.thinking || block.text;
      await publisher.publishThinking(thinking);
      thinkingSections.push({ title: '', content: thinking });
      orderedEntries.push({ type: 'thinking', title: '', content: thinking, timestamp: Date.now() });
    }
  }

  incrementTurn(ctx);
}

/**
 * Handle the OpenAI-style `item.started` / `item.completed` event family.
 *
 * Used by Codex, Gemini, and Mistral CLIs (and any other CLI that emits the
 * same envelope). The three providers all had a near-byte-for-byte copy of
 * this 100-line switch - extracted here so a fix to e.g. tool-result
 * handling lands in one place. Provider-specific differences are exposed
 * via the `opts` parameter; codex layers its own `command_execution` /
 * `web_search` handlers on top of this helper rather than inside it.
 *
 * @param {object} msg - parsed NDJSON event with `type` in {'item.started','item.completed'}
 * @param {object} ctx - adapter context (publisher, pendingToolCalls, …)
 * @param {object} opts
 * @param {string} opts.providerKey - log prefix and synthetic id namespace ('codex' | 'gemini' | 'mistral')
 * @param {(usage: object, ctx: object) => void} [opts.recordUsage] - provider-specific usage recorder
 *   (called for `agent_message`/`message` items that carry usage). Defaults to no-op.
 */
export async function handleCodexStyleItemEvent(msg, ctx, opts) {
  const { publisher, pendingToolCalls, thinkingSections, orderedEntries } = ctx;
  const item = msg.item;
  if (!item) return;
  const providerKey = opts.providerKey;
  const recordUsage = opts.recordUsage || (() => {});
  const synthId = () => synthIdFor(providerKey);

  // `item.started`: register pending + publish, but DO NOT append to
  // `orderedEntries` - the canonical replay log entry is appended once on
  // `item.completed` to avoid double-counting (started+completed for the
  // same tool would otherwise show up twice). Hence the manual `set` +
  // `publish` here instead of `dispatchToolCall` (which always appends).
  if (msg.type === 'item.started') {
    if ((item.type === 'mcp_tool_call' || item.type === 'function_call') && (item.tool || item.name)) {
      const toolId = item.id || item.call_id || synthId();
      let toolName = item.tool || item.name || item.function?.name || 'unknown';
      toolName = ctx.stripMcpPrefix(toolName);
      const argsStr = typeof item.arguments === 'string'
        ? item.arguments
        : JSON.stringify(item.arguments || item.function?.arguments || {});
      pendingToolCalls.set(toolId, { toolName, arguments: argsStr, startTime: Date.now() });
      await publisher.publishToolCall(toolName, toolId, argsStr);
    }
    return;
  }

  // item.completed
  switch (item.type) {
    case 'agent_message':
    case 'message': {
      const text = item.content?.[0]?.text || item.text || '';
      if (text) {
        await publisher.publishContent(text);
        ctx.updateState({ fullContent: ctx.getContent() + text });
      }
      if (item.model) ctx.updateState({ cliModel: item.model });
      if (item.usage) recordUsage(item.usage, ctx);
      break;
    }

    case 'mcp_tool_call':
    case 'function_call': {
      const toolId = item.id || item.call_id || synthId();
      let toolName = item.tool || item.name || item.function?.name || 'unknown';
      toolName = ctx.stripMcpPrefix(toolName);
      const argsStr = typeof item.arguments === 'string'
        ? item.arguments
        : JSON.stringify(item.arguments || item.function?.arguments || {});
      const isError = resolveItemError(item);
      const errorMsg = resolveItemErrorMessage(item);

      // Gate left EXACTLY as it was. Two pre-existing lifecycle quirks live here and are
      // deliberately NOT addressed by this change, which is about the metadata channel:
      //   - a terminal status with no result (any of FAILED_STATUSES except 'failed':
      //     error, cancelled, canceled, aborted, timeout) leaves a pendingToolCalls entry
      //     that nothing resolves (nothing else deletes from that map);
      //   - `status:'failed'` followed by a late *_result event for the same toolId
      //     dispatches twice.
      // Both predate this fix. Widening the gate, or making dispatch idempotent per toolId,
      // was tried and each attempt LOST legitimate results (a first partial event then the
      // real payload) or leaked a different pending entry. Fixing them properly needs a
      // per-call lifecycle, which is its own change with its own tests.
      if (item.status === 'failed' || item.result != null) {
        // Raw value, never serialised - see the note on the other call site.
        const { content: cleanContent, metadata } = ctx.extractToolResultAndMetadata(resolveItemPayload(item));
        const alreadyStarted = pendingToolCalls.has(toolId);
        if (!alreadyStarted) {
          // Record the call so the replay log + UI see it before the result.
          pendingToolCalls.set(toolId, { toolName, arguments: argsStr, startTime: Date.now() });
          orderedEntries.push({ type: 'tool_call', id: toolId, toolName, arguments: argsStr, timestamp: Date.now() });
          await publisher.publishToolCall(toolName, toolId, argsStr);
        }
        await dispatchToolResult(ctx, {
          toolId,
          isError,
          // Raw values only: dispatchToolResult owns the failure fallback for BOTH `content`
          // and `error`, so this branch and its sibling below cannot drift apart again.
          content: cleanContent,
          errorMsg,
          metadata,
          fallbackToolName: toolName,
        });
      } else {
        // Started without a result yet - register pending and broadcast.
        pendingToolCalls.set(toolId, { toolName, arguments: argsStr, startTime: Date.now() });
        orderedEntries.push({ type: 'tool_call', id: toolId, toolName, arguments: argsStr, timestamp: Date.now() });
        await publisher.publishToolCall(toolName, toolId, argsStr);
      }
      break;
    }

    case 'mcp_tool_call_result':
    case 'function_call_output': {
      const toolId = item.call_id || item.tool_use_id || item.id;
      // Same resolvers as the mcp_tool_call branch above: this sibling used to read a
      // narrower field list and ignore the envelope's isError, so the same class of bug
      // survived here after the first one was fixed.
      const { content: cleanContent, metadata } = ctx.extractToolResultAndMetadata(
        resolveItemPayload(item, RESULT_EVENT_PAYLOAD_FIELDS)
      );
      await dispatchToolResult(ctx, {
        toolId,
        isError: resolveItemError(item, RESULT_EVENT_PAYLOAD_FIELDS),
        content: cleanContent,
        errorMsg: resolveItemErrorMessage(item),
        metadata,
        fallbackToolName: item.name || 'unknown',
      });
      break;
    }

    case 'reasoning': {
      const thinking = item.content?.[0]?.text || item.text || '';
      if (thinking) {
        await publisher.publishThinking(thinking);
        thinkingSections.push({ title: '', content: thinking });
        orderedEntries.push({ type: 'thinking', title: '', content: thinking, timestamp: Date.now() });
      }
      break;
    }

    default:
      console.log(`[BRIDGE:${providerKey}:item] unknown item.type=${item.type}`);
      break;
  }
}
