/**
 * Tests for the shared adapter helpers. These cover the regression that
 * caused tool double-execution + closure-trap-masked usage doubling, and the
 * reduction loop that used to be inlined four times across adapters.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  recordCallUsage,
  dispatchToolCall,
  dispatchToolResult,
  incrementTurn,
  handleFlatCliMessage,
  handleClaudeStyleAssistantMessage,
  buildStdinPayload,
  PROMPT_SEPARATOR,
} from '../lib/adapterHelpers.mjs';

/**
 * Build a fake ctx that mirrors what server.mjs constructs at runtime.
 * Must use getters for primitives so the closure-trap regression cannot
 * silently come back.
 */
function makeCtx() {
  let usage = { promptTokens: 0, completionTokens: 0 };
  let numTurns = 0;
  const perCallUsages = [];
  const iterationTimestamps = [];
  const finishReasons = [];
  const pendingToolCalls = new Map();
  const orderedEntries = [];
  const toolResults = [];
  const thinkingSections = [];

  const publishedToolCalls = [];
  const publishedToolResults = [];
  const publisher = {
    publishToolCall: async (toolName, toolId, argsStr) => {
      publishedToolCalls.push({ toolName, toolId, argsStr });
    },
    publishToolResult: async (toolId, toolName, success, durationMs, content, metadata) => {
      publishedToolResults.push({ toolId, toolName, success, durationMs, content, metadata });
    },
    publishContent: async () => {},
    publishThinking: async () => {},
  };

  return {
    publisher,
    pendingToolCalls,
    orderedEntries,
    toolResults,
    thinkingSections,
    state: {
      get usage() { return usage; },
      get numTurns() { return numTurns; },
      get perCallUsages() { return perCallUsages; },
      get iterationTimestamps() { return iterationTimestamps; },
      get finishReasons() { return finishReasons; },
    },
    updateState(updates) {
      if (updates.usage != null) usage = updates.usage;
      if (updates.numTurns != null) numTurns = updates.numTurns;
    },
    // Test inspection helpers
    _published: { toolCalls: publishedToolCalls, toolResults: publishedToolResults },
  };
}

test('recordCallUsage pushes one entry per call and recomputes the cumulative', () => {
  const ctx = makeCtx();
  const r1 = recordCallUsage(ctx, { promptTokens: 100, completionTokens: 20 });
  assert.equal(r1.callIndex, 1);
  assert.equal(r1.totalInput, 100);
  assert.equal(r1.totalOutput, 20);
  assert.equal(ctx.state.usage.promptTokens, 100);

  const r2 = recordCallUsage(ctx, { promptTokens: 250, completionTokens: 50 });
  assert.equal(r2.callIndex, 2);
  assert.equal(r2.totalInput, 350);
  assert.equal(r2.totalOutput, 70);
  assert.equal(ctx.state.usage.promptTokens, 350);
  assert.equal(ctx.state.usage.completionTokens, 70);

  // Per-call timestamps grow with each push.
  assert.equal(ctx.state.iterationTimestamps.length, 2);
});

test('recordCallUsage preserves cache + reasoning fields', () => {
  const ctx = makeCtx();
  recordCallUsage(ctx, {
    promptTokens: 1,
    completionTokens: 2,
    cacheCreationInputTokens: 3,
    cacheReadInputTokens: 4,
    reasoningTokens: 5,
  });
  const entry = ctx.state.perCallUsages[0];
  assert.equal(entry.cacheCreationInputTokens, 3);
  assert.equal(entry.cacheReadInputTokens, 4);
  assert.equal(entry.reasoningTokens, 5);
});

test('recordCallUsage reads through ctx.state getters (closure-trap regression)', () => {
  // The original bridge bug captured `usage` by value in a state literal.
  // After the fix, ctx.state.usage is a getter and reflects mutations.
  // This test will fail loudly if a contributor reverts the getter pattern.
  const ctx = makeCtx();
  recordCallUsage(ctx, { promptTokens: 5, completionTokens: 1 });
  recordCallUsage(ctx, { promptTokens: 5, completionTokens: 1 });
  // If the closure trap returns, ctx.state.usage will still be {0,0} here.
  assert.equal(ctx.state.usage.promptTokens, 10, 'closure trap regression');
  assert.equal(ctx.state.usage.completionTokens, 2);
});

test('dispatchToolCall registers pending entry, orderedEntries, and publishes', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, { toolId: 'tu_1', toolName: 'workflow', argsStr: '{"x":1}' });
  assert.equal(ctx.pendingToolCalls.size, 1);
  assert.equal(ctx.pendingToolCalls.get('tu_1').toolName, 'workflow');
  assert.equal(ctx.orderedEntries.length, 1);
  assert.equal(ctx.orderedEntries[0].type, 'tool_call');
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.deepEqual(ctx._published.toolCalls[0], { toolName: 'workflow', toolId: 'tu_1', argsStr: '{"x":1}' });
});

test('dispatchToolCall passes extras into pending entry', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, {
    toolId: 'tu_2',
    toolName: 'Read',
    argsStr: '{"file_path":"x"}',
    extras: { attachmentFileName: 'pic.png' },
  });
  assert.equal(ctx.pendingToolCalls.get('tu_2').attachmentFileName, 'pic.png');
});

test('dispatchToolResult publishes, records, and clears pending', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, { toolId: 'tu_3', toolName: 'workflow', argsStr: '{}' });
  await dispatchToolResult(ctx, { toolId: 'tu_3', isError: false, content: 'OK' });
  assert.equal(ctx.toolResults.length, 1);
  assert.equal(ctx.toolResults[0].success, true);
  assert.equal(ctx.toolResults[0].content, 'OK');
  assert.equal(ctx.pendingToolCalls.has('tu_3'), false);
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx._published.toolResults[0].success, true);
});

test('dispatchToolResult uses errorMsg when isError', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, { toolId: 'tu_4', toolName: 'workflow', argsStr: '{}' });
  await dispatchToolResult(ctx, { toolId: 'tu_4', isError: true, content: 'fail body', errorMsg: 'specific reason' });
  assert.equal(ctx.toolResults[0].error, 'specific reason');
  assert.equal(ctx.toolResults[0].success, false);
});

test('dispatchToolResult propagates attachmentFileName into metadata.label', async () => {
  const ctx = makeCtx();
  await dispatchToolCall(ctx, {
    toolId: 'tu_5',
    toolName: 'Read',
    argsStr: '{}',
    extras: { attachmentFileName: 'screenshot.png' },
  });
  await dispatchToolResult(ctx, { toolId: 'tu_5', isError: false, content: 'data' });
  const meta = ctx.toolResults[0].metadata;
  assert.equal(meta.label, 'screenshot.png');
  assert.equal(meta.toolName, 'view_attachment');
});

test('dispatchToolResult tolerates missing pending entry but warns loudly', async () => {
  const ctx = makeCtx();
  const origWarn = console.warn;
  const warns = [];
  console.warn = (m) => warns.push(m);
  try {
    await dispatchToolResult(ctx, { toolId: 'tu_unknown', isError: false, content: 'data', fallbackToolName: 'workflow' });
  } finally {
    console.warn = origWarn;
  }
  assert.equal(ctx.toolResults[0].toolCall.toolName, 'workflow');
  assert.equal(ctx.toolResults[0].durationMs, null);
  assert.ok(warns.some((w) => /no pending tool for id=tu_unknown/.test(w)), 'must warn on missing pending');
});

test('incrementTurn bumps numTurns through updateState', () => {
  const ctx = makeCtx();
  incrementTurn(ctx);
  incrementTurn(ctx);
  incrementTurn(ctx);
  assert.equal(ctx.state.numTurns, 3);
});

// ─── isExtendedThinkingContinuation ───────────────────────────────────────

import { isExtendedThinkingContinuation } from '../adapters/claude-adapter.mjs';

test('isExtendedThinkingContinuation requires pause_turn as prev stop_reason', () => {
  const prev = { promptTokens: 13333 };
  // Same tokens + pause_turn → continuation
  assert.equal(isExtendedThinkingContinuation(prev, 13333, 'pause_turn'), true);
  // Same tokens but stop was tool_use → NOT a continuation (independent turn)
  assert.equal(isExtendedThinkingContinuation(prev, 13333, 'tool_use'), false);
  // Same tokens, end_turn → NOT a continuation
  assert.equal(isExtendedThinkingContinuation(prev, 13333, 'end_turn'), false);
});

test('isExtendedThinkingContinuation returns false when no prev call', () => {
  assert.equal(isExtendedThinkingContinuation(null, 13333, 'pause_turn'), false);
});

test('isExtendedThinkingContinuation returns false when token counts differ', () => {
  const prev = { promptTokens: 13333 };
  assert.equal(isExtendedThinkingContinuation(prev, 14000, 'pause_turn'), false);
});

// ─── handleCodexStyleItemEvent (gemini/mistral path) ──────────────────────

import {
  handleCodexStyleItemEvent,
  resolveItemError,
  resolveItemErrorMessage,
  resolveItemPayload,
  CODEX_PAYLOAD_FIELDS,
  FLAT_PAYLOAD_FIELDS,
} from '../lib/adapterHelpers.mjs';
import { extractToolResultAndMetadata } from '../lib/toolContent.mjs';
import { afterEach } from 'node:test';

// Centralised so a test that throws mid-way cannot leak the nonce into the next one.
afterEach(() => {
  delete process.env.BRIDGE_META_NONCE;
});

function makeItemCtx() {
  const base = makeCtx();
  base.stripMcpPrefix = (n) => n.replace(/^mcp__[^_]+__/, '');
  base.extractToolResultAndMetadata = extractToolResultAndMetadata;
  base.getContent = () => '';
  // augment publisher with content/thinking
  base.publisher.publishContent = async () => {};
  base.publisher.publishThinking = async () => {};
  return base;
}

// The three tests below cover the shape Codex really emits: `item.result` is the whole MCP
// CallToolResult OBJECT, not a string. Every pre-existing test in this file used a plain
// string, which is exactly why the metadata loss and the silent-failure bug went unnoticed.

test('handleCodexStyleItemEvent: an MCP envelope result keeps its trusted metadata', async () => {
  process.env.BRIDGE_META_NONCE = 'cafebabecafebabecafebabecafebabe';
  const ctx = makeItemCtx();
  const envelope = {
    content: [
      { type: 'text', text: '{"ok":true}' },
      { type: 'text', text: '\n__BRIDGE_META__:cafebabecafebabecafebabecafebabe:{"serviceApprovalRequested":true,"services":["gmail"],"iconSlug":"gmail"}' },
    ],
    structured_content: null,
  };

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'call_env', tool: 'credential', arguments: '{}', result: envelope, status: 'completed' },
  }, ctx, { providerKey: 'codex' });

  const published = ctx._published.toolResults[0];
  assert.equal(published.metadata.serviceApprovalRequested, true, 'the approval card would never render without this');
  assert.equal(published.metadata.iconSlug, 'gmail');
  assert.equal(published.content.trimEnd(), '{"ok":true}');
  assert.ok(!published.content.includes('structured_content'), 'the raw envelope must not become the visible result');
});

test('handleCodexStyleItemEvent: isError on the envelope marks the call failed even when status says completed', async () => {
  const ctx = makeItemCtx();
  const envelope = {
    content: [{ type: 'text', text: 'Credentials already exist for: gmail. Retry with force=true.' }],
    isError: true,
  };

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'call_err', tool: 'credential', arguments: '{}', result: envelope, status: 'completed' },
  }, ctx, { providerKey: 'codex' });

  const published = ctx._published.toolResults[0];
  assert.equal(published.success, false, 'an error body must never be published as a success');
  assert.match(published.content, /force=true/, 'the backend guidance must reach the model, not an empty string');
});

test('handleCodexStyleItemEvent: mcp_tool_call_result also keeps envelope metadata and honours isError', async () => {
  // The sibling branch read a narrower field list and ignored the envelope's isError, so the
  // same class of bug survived here after the mcp_tool_call branch was fixed.
  process.env.BRIDGE_META_NONCE = 'cafebabecafebabecafebabecafebabe';
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('call_sib', { toolName: 'table', arguments: '{}', startTime: Date.now() });

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: {
      type: 'mcp_tool_call_result',
      call_id: 'call_sib',
      result: {
        content: [
          { type: 'text', text: 'row deleted' },
          { type: 'text', text: '\n__BRIDGE_META__:cafebabecafebabecafebabecafebabe:{"iconSlug":"table"}' },
        ],
        isError: true,
      },
    },
  }, ctx, { providerKey: 'codex' });

  const published = ctx._published.toolResults[0];
  assert.equal(published.metadata.iconSlug, 'table', 'metadata must survive on this branch too');
  assert.equal(published.success, false, 'the envelope isError must mark the call failed');
  assert.match(ctx.toolResults[0].error, /row deleted/, 'a failed call must never carry an empty error');
});

test('resolveItemError: only a strictly true isError counts, so a truthy value is not a failure', () => {
  // `=== true` and not `!!`: the truthy-but-not-true cases below are the only ones that
  // discriminate between the two, and are exactly what a sloppy CLI emits.
  assert.equal(resolveItemError({ status: 'completed', result: { isError: 1 } }), false);
  assert.equal(resolveItemError({ status: 'completed', result: { isError: 'false' } }), false);
  assert.equal(resolveItemError({ status: 'completed', result: { isError: {} } }), false);
  // `?.` and not `.`: a null payload must not throw.
  assert.equal(resolveItemError({ status: 'completed', result: null }), false);
  assert.equal(resolveItemError(null), false);
  // Non-envelope payloads carry no failure signal.
  assert.equal(resolveItemError({ status: 'completed', result: 'plain text' }), false);
  assert.equal(resolveItemError({ status: 'completed', result: [{ type: 'text', text: 'x' }] }), false);
  assert.equal(resolveItemError({ status: 'completed', result: { content: [], isError: false } }), false);
  // Real failures.
  assert.equal(resolveItemError({ status: 'completed', result: { content: [], isError: true } }), true);
  assert.equal(resolveItemError({ status: 'completed', output: { content: [], isError: true } }), true);
  assert.equal(resolveItemError({ status: 'failed' }), true);
  assert.equal(resolveItemError({ error: { message: 'boom' } }), true);
});

test('an error-only completed event waits for its result event, and the payload wins', async () => {
  // Pins why the dispatch gate must NOT be widened to "any error signal": doing so makes
  // this first event dispatch, and the follow-up result event dispatch again after
  // pendingToolCalls.delete - losing the tool name, republishing success over a failure, and
  // discarding the real payload below. Counting is the only way to see it.
  const ctx = makeItemCtx();

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'd1', tool: 'workflow', arguments: '{}', status: 'completed', error: 'transport hiccup' },
  }, ctx, { providerKey: 'codex' });

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call_result', call_id: 'd1', output: { content: [{ type: 'text', text: 'actually it worked' }] } },
  }, ctx, { providerKey: 'codex' });

  assert.equal(ctx.toolResults.length, 1, 'one tool call must produce exactly one result');
  assert.equal(ctx.toolResults[0].toolCall.toolName, 'workflow', 'the tool name must not be lost');
  assert.match(ctx.toolResults[0].content, /actually it worked/, 'the real payload must be the one kept');
  assert.equal(ctx.toolResults[0].success, true);
});

test('resolveItemError: an empty error object or array is not a failure', () => {
  // `{}` / `[]` are "no error" idioms from Go/protobuf-style serialisers. Treating them as
  // failures flips a successful call AND turns its success payload into the error message.
  assert.equal(resolveItemError({ status: 'completed', error: {}, output: 'all good' }), false);
  assert.equal(resolveItemError({ status: 'completed', error: [], output: 'all good' }), false);
  assert.equal(resolveItemError({ status: 'completed', error: NaN, output: 'all good' }), false);
  assert.equal(resolveItemError({ status: 'completed', error: { code: 500 } }), true);
  assert.equal(resolveItemError({ status: 'completed', error: ['boom'] }), true);
});

test('resolveItemError: a cancelled, aborted or timed-out call is a failure', () => {
  // These were published as successes with an empty content.
  for (const status of ['cancelled', 'canceled', 'aborted', 'timeout']) {
    assert.equal(resolveItemError({ status }), true, `status=${status} must fail the call`);
  }
  assert.equal(resolveItemError({ status: 'completed' }), false);
});

test('resolveItemErrorMessage: a structured error keeps its diagnostic', () => {
  assert.equal(resolveItemErrorMessage({ error: { message: 'boom' } }), 'boom');
  assert.equal(resolveItemErrorMessage({ error: 'plain string' }), 'plain string');
  // Returning null here sent the model a generic label and dropped the only diagnostic.
  assert.equal(resolveItemErrorMessage({ error: { code: 500 } }), '{"code":500}');
  assert.equal(resolveItemErrorMessage({ error: {} }), null);
  assert.equal(resolveItemErrorMessage({ error: false }), null);
  assert.equal(resolveItemErrorMessage({}), null);
  assert.equal(resolveItemErrorMessage(null), null);
});

test('resolveItemPayload keeps each branch its own field order', () => {
  // The flat *_result events lead with `output`, codex-style events lead with `result`.
  // Imposing one order on both changed which field wins when a CLI sends several.
  const item = { result: 'STALE RESULT FIELD', content: 'the real content' };
  assert.equal(resolveItemPayload(item, CODEX_PAYLOAD_FIELDS), 'STALE RESULT FIELD');
  assert.equal(resolveItemPayload(item, FLAT_PAYLOAD_FIELDS), 'the real content');
});

test('resolveItemError: falsy error idioms are not failures', () => {
  // `error: false` / `''` / `0` mean "no error". A loose `item.error != null` flipped these
  // to failed on the *_result branches, which never applied that check before the refactor.
  assert.equal(resolveItemError({ status: 'completed', error: false, output: 'all good' }), false);
  assert.equal(resolveItemError({ status: 'completed', error: '', output: 'all good' }), false);
  assert.equal(resolveItemError({ status: 'completed', error: 0, output: 'all good' }), false);
  assert.equal(resolveItemError({ status: 'completed', error: 'boom' }), true);
});

test('resolveItemPayload takes the first MEANINGFUL field, not merely the first present one', () => {
  assert.equal(resolveItemPayload({ result: 'r', output: 'o', content: 'c', text: 't' }), 'r');
  // The discriminating cases: `??` alone would return the empty result and hide the real
  // output beside it; `||` alone would discard a legitimate 0.
  assert.equal(resolveItemPayload({ result: '', output: 'THE REAL OUTPUT' }), 'THE REAL OUTPUT');
  assert.equal(resolveItemPayload({ result: null, output: 'THE REAL OUTPUT' }), 'THE REAL OUTPUT');
  assert.equal(resolveItemPayload({ result: 0 }), 0);
  assert.equal(resolveItemPayload({ result: false }), false);
  assert.equal(resolveItemPayload({ output: 'o', content: 'c' }), 'o');
  assert.equal(resolveItemPayload({ content: 'c', text: 't' }), 'c');
  assert.equal(resolveItemPayload({ text: 't' }), 't');
  assert.equal(resolveItemPayload({}), '');
  assert.equal(resolveItemPayload(null), '');
});

test('each CALL SITE keeps the field order its branch had before the refactor', async () => {
  // Pinning the constants in isolation proves nothing: the defect was a call site passing
  // the WRONG constant, which every isolated test still passed. These go through the real
  // event handlers with an item carrying both fields.
  const cases = [
    { type: 'mcp_tool_call_result', flat: false, expected: 'FROM_OUTPUT' },
    { type: 'tool_result', flat: true, expected: 'FROM_OUTPUT' },
  ];

  for (const { type, flat, expected } of cases) {
    const ctx = makeItemCtx();
    ctx.pendingToolCalls.set('order_id', { toolName: 'workflow', arguments: '{}', startTime: Date.now() });
    const payload = { call_id: 'order_id', result: 'FROM_RESULT', output: 'FROM_OUTPUT' };

    if (flat) {
      await handleFlatCliMessage({ type, ...payload }, ctx, { providerKey: 'gemini' });
    } else {
      await handleCodexStyleItemEvent({ type: 'item.completed', item: { type, ...payload } }, ctx, { providerKey: 'codex' });
    }

    assert.equal(ctx._published.toolResults[0].content, expected, `${type} must keep its own field order`);
  }

  // The codex mcp_tool_call branch is the one that legitimately leads with `result`.
  const ctx = makeItemCtx();
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'order2', tool: 'workflow', arguments: '{}', status: 'completed', result: 'FROM_RESULT', output: 'FROM_OUTPUT' },
  }, ctx, { providerKey: 'codex' });
  assert.equal(ctx._published.toolResults[0].content, 'FROM_RESULT');
});

test('KNOWN LIMITATION (pre-existing, out of scope): a cancelled call with no result leaks its pending entry', async () => {
  // Documented, not fixed. The dispatch gate only fires on `status:'failed'` or a present
  // result, and nothing else deletes from pendingToolCalls. Both attempts to close this
  // (widening the gate, making dispatch idempotent per toolId) LOST legitimate results, so
  // the metadata fix leaves the lifecycle exactly as it found it. This test exists so the
  // behaviour is visible and a future lifecycle change has a starting point.
  const ctx = makeItemCtx();

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'cancel1', tool: 'workflow', arguments: '{}', status: 'cancelled' },
  }, ctx, { providerKey: 'codex' });

  assert.equal(ctx.toolResults.length, 0, 'no result is produced today');
  assert.equal(ctx.pendingToolCalls.size, 1, 'and the pending entry survives - the known leak');
});

test('a failure keeps BOTH the CLI message and the payload text', async () => {
  // The error message must not evict the payload: the backend's actionable guidance lives
  // in the payload, and `content` is what feeds the model history.
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('both', { toolName: 'credential', arguments: '{}', startTime: Date.now() });

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: {
      type: 'mcp_tool_call_result',
      call_id: 'both',
      error: { message: 'transport closed' },
      output: { content: [{ type: 'text', text: 'backend guidance: retry with force=true' }], isError: true },
    },
  }, ctx, { providerKey: 'codex' });

  assert.equal(ctx.toolResults[0].error, 'transport closed', 'the CLI reason belongs in error');
  assert.match(ctx.toolResults[0].content, /force=true/, 'the payload must still reach the model');
});

test('a failure with no payload publishes a readable content, never a blank result', async () => {
  // `error` alone is not enough: `content` is what feeds the model history, so a failure
  // with nothing to say must still say something there.
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('blank', { toolName: 'workflow', arguments: '{}', startTime: Date.now() });

  await handleFlatCliMessage({
    type: 'tool_result',
    call_id: 'blank',
    output: { content: [], isError: true },
  }, ctx, { providerKey: 'gemini' });

  assert.equal(ctx._published.toolResults[0].success, false);
  assert.ok(ctx._published.toolResults[0].content, 'content must not be empty on a failure');
  assert.equal(ctx._published.toolResults[0].content, ctx.toolResults[0].error);
});

test('handleFlatCliMessage forwards the CLI error message, not just the failure flag', async () => {
  // The flat leg's `errorMsg:` argument was unpinned: deleting it survived the whole suite,
  // because every other flat test has no `error` field or a falsy one.
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('flat_msg', { toolName: 'workflow', arguments: '{}', startTime: Date.now() });

  await handleFlatCliMessage({
    type: 'tool_result',
    call_id: 'flat_msg',
    error: 'upstream refused the connection',
    output: '',
  }, ctx, { providerKey: 'gemini' });

  assert.equal(ctx.toolResults[0].success, false);
  assert.equal(ctx.toolResults[0].error, 'upstream refused the connection');
});

test("GUARD: status 'error' keeps failing the call on the flat and *_result branches", async () => {
  // Pre-existing behaviour of those two branches, absorbed into FAILED_STATUSES by the
  // refactor. Nothing covered it, so removing it would have republished them as successes.
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('st_err', { toolName: 'workflow', arguments: '{}', startTime: Date.now() });

  await handleFlatCliMessage({ type: 'tool_result', call_id: 'st_err', status: 'error', output: 'boom' }, ctx, { providerKey: 'mistral' });

  assert.equal(ctx._published.toolResults[0].success, false);
});

test('the envelope failure flag is read from the payload that was SELECTED, not any field', async () => {
  // `{output:'everything is fine', result:{isError:true}}` on the flat branch: `output` wins
  // as the payload, so the isError sitting on the unused `result` must not fail the call and
  // publish that success text as an error.
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('sel', { toolName: 'workflow', arguments: '{}', startTime: Date.now() });

  await handleFlatCliMessage({
    type: 'tool_result',
    call_id: 'sel',
    output: 'everything is fine',
    result: { content: [], isError: true },
  }, ctx, { providerKey: 'gemini' });

  assert.equal(ctx._published.toolResults[0].success, true);
  assert.equal(ctx._published.toolResults[0].content, 'everything is fine');
});

test('resolveItemError covers is_error and an envelope carried on content', async () => {
  assert.equal(resolveItemError({ status: 'completed', is_error: true }), true);
  assert.equal(resolveItemError({ status: 'completed', content: { content: [], isError: true } }), true);
  assert.equal(resolveItemError({ status: 'completed', is_error: false }), false);
  assert.equal(resolveItemError({ error: new Error('boom') }), true, 'an Error instance has no own keys');
  assert.equal(resolveItemErrorMessage({ error: new Error('boom') }), 'boom');
});

test('a falsy-but-real payload survives all the way to the published content', async () => {
  // The resolver preserving 0 is pointless if the extractor then flattens it: the guard
  // there must be `== null`, not `!rawContent`. Asserted end to end for that reason.
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('zero', { toolName: 'code', arguments: '{}', startTime: Date.now() });

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call_result', call_id: 'zero', result: 0 },
  }, ctx, { providerKey: 'codex' });

  assert.equal(ctx._published.toolResults[0].content, '0', 'a 0 result must not become an empty answer');
});

test('the two codex sibling branches produce an identical result for the same envelope', async () => {
  // They diverged on content-on-error: one published the reason, the other an empty string.
  process.env.BRIDGE_META_NONCE = 'cafebabecafebabecafebabecafebabe';
  const envelope = {
    content: [
      { type: 'text', text: 'backend says: retry with force=true' },
      { type: 'text', text: '\n__BRIDGE_META__:cafebabecafebabecafebabecafebabe:{"iconSlug":"credential"}' },
    ],
    isError: true,
  };
  const shapes = [];

  for (const type of ['mcp_tool_call', 'mcp_tool_call_result']) {
    const ctx = makeItemCtx();
    ctx.pendingToolCalls.set('same_id', { toolName: 'credential', arguments: '{}', startTime: Date.now() });
    await handleCodexStyleItemEvent({
      type: 'item.completed',
      item: { type, id: 'same_id', call_id: 'same_id', tool: 'credential', arguments: '{}', status: 'completed', result: envelope },
    }, ctx, { providerKey: 'codex' });
    const r = ctx.toolResults[0];
    shapes.push({ success: r.success, content: r.content, error: r.error, metadata: r.metadata });
  }

  assert.deepEqual(shapes[0], shapes[1], 'sibling branches must not disagree on the same event');
  assert.equal(shapes[0].success, false);
  assert.match(shapes[0].content, /force=true/, 'the model must read the reason on BOTH branches');
});

test('handleCodexStyleItemEvent: a failure with no message anywhere still publishes a readable error', async () => {
  const ctx = makeItemCtx();

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: {
      type: 'mcp_tool_call',
      id: 'call_empty',
      tool: 'workflow',
      arguments: '{}',
      status: 'completed',
      result: { content: [], isError: true },
    },
  }, ctx, { providerKey: 'codex' });

  assert.equal(ctx.toolResults[0].success, false);
  assert.ok(ctx.toolResults[0].error, 'error must never be empty on a failed call');
  assert.match(ctx.toolResults[0].error, /no message returned/i);
});

test('handleFlatCliMessage: a tool_result carrying an MCP envelope keeps its metadata', async () => {
  // The flat path (gemini / mistral) had the same serialise-before-parse defect and no test
  // covering an object payload: every existing case passed a plain string.
  process.env.BRIDGE_META_NONCE = 'cafebabecafebabecafebabecafebabe';
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('flat_1', { toolName: 'catalog', arguments: '{}', startTime: Date.now() });

  await handleFlatCliMessage({
    type: 'tool_result',
    call_id: 'flat_1',
    output: {
      content: [
        { type: 'text', text: '{"ok":1}' },
        { type: 'text', text: '\n__BRIDGE_META__:cafebabecafebabecafebabecafebabe:{"iconSlug":"catalog"}' },
      ],
    },
  }, ctx, { providerKey: 'gemini' });

  const published = ctx._published.toolResults[0];
  assert.equal(published.metadata.iconSlug, 'catalog');
  assert.equal(published.content.trimEnd(), '{"ok":1}');
  assert.ok(!published.content.includes('__BRIDGE_META__'));
});

test('handleFlatCliMessage: an envelope reporting isError fails the call on this path too', async () => {
  // The flat path kept its own hand-rolled error test after the codex branches were fixed,
  // so the very same envelope was published as a SUCCESS here and as a failure there.
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('flat_err', { toolName: 'credential', arguments: '{}', startTime: Date.now() });

  await handleFlatCliMessage({
    type: 'tool_result',
    call_id: 'flat_err',
    output: {
      content: [{ type: 'text', text: 'Credentials already exist. Retry with force=true.' }],
      isError: true,
    },
  }, ctx, { providerKey: 'mistral' });

  const published = ctx._published.toolResults[0];
  assert.equal(published.success, false, 'a failed tool must never be published as a success');
  assert.match(published.content, /force=true/, 'the backend guidance must reach the model');
  assert.match(ctx.toolResults[0].error, /force=true/);
});

test('GUARD (green pre-fix): a falsy error field does not flip a successful call to failed', async () => {
  const ctx = makeItemCtx();
  ctx.pendingToolCalls.set('flat_ok', { toolName: 'catalog', arguments: '{}', startTime: Date.now() });

  await handleFlatCliMessage({
    type: 'tool_result',
    call_id: 'flat_ok',
    error: false,
    output: 'all good',
  }, ctx, { providerKey: 'gemini' });

  assert.equal(ctx._published.toolResults[0].success, true);
  assert.equal(ctx.toolResults[0].error, null);
});

test('an explicit item.error owns the error field while the envelope text stays the content', async () => {
  const ctx = makeItemCtx();

  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: {
      type: 'mcp_tool_call',
      id: 'call_err2',
      tool: 'workflow',
      arguments: '{}',
      status: 'failed',
      error: { message: 'transport closed' },
      result: { content: [{ type: 'text', text: 'envelope text' }], isError: true },
    },
  }, ctx, { providerKey: 'codex' });

  const published = ctx._published.toolResults[0];
  assert.equal(published.success, false);
  // Both survive, and both in the PUBLISHED content, because only one field crosses the
  // wire. Keeping the reason only in `error` hid it from the frontend and the model.
  assert.equal(ctx.toolResults[0].error, 'transport closed');
  assert.match(published.content, /transport closed/, 'the CLI reason must reach the stream');
  assert.match(published.content, /envelope text/, 'and so must the backend guidance');
});

test('handleCodexStyleItemEvent: tool_call with result fires call + result exactly once', async () => {
  const ctx = makeItemCtx();
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'call_1', tool: 'workflow', arguments: '{"x":1}', result: 'OK', status: 'completed' },
  }, ctx, { providerKey: 'gemini' });
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx._published.toolResults[0].success, true);
  assert.equal(ctx.pendingToolCalls.size, 0);
});

test('handleCodexStyleItemEvent: tool_call without result registers pending only', async () => {
  const ctx = makeItemCtx();
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call', id: 'call_2', tool: 'workflow', arguments: '{}' },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx._published.toolResults.length, 0);
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('handleCodexStyleItemEvent: agent_message records usage via opts.recordUsage', async () => {
  const ctx = makeItemCtx();
  let recorded = null;
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'agent_message', text: 'hi', usage: { input_tokens: 100, output_tokens: 5 } },
  }, ctx, {
    providerKey: 'gemini',
    recordUsage: (u) => { recorded = u; },
  });
  assert.equal(recorded.input_tokens, 100);
  assert.equal(recorded.output_tokens, 5);
});

// ─── handleFlatCliMessage (gemini/mistral path) ───────────────────────────

test('handleFlatCliMessage: tool_use registers pending and publishes', async () => {
  const ctx = makeItemCtx();
  const handled = await handleFlatCliMessage({ type: 'tool_use', id: 't1', name: 'workflow', input: { x: 1 } }, ctx, { providerKey: 'gemini' });
  assert.equal(handled, true);
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('handleFlatCliMessage: tool_result publishes and clears pending', async () => {
  const ctx = makeItemCtx();
  await handleFlatCliMessage({ type: 'tool_use', id: 't2', name: 'workflow', input: {} }, ctx, { providerKey: 'mistral' });
  await handleFlatCliMessage({ type: 'tool_result', tool_use_id: 't2', output: 'ok' }, ctx, { providerKey: 'mistral' });
  assert.equal(ctx._published.toolResults.length, 1);
  assert.equal(ctx.pendingToolCalls.size, 0);
});

test('handleFlatCliMessage: returns false for unknown type', async () => {
  const ctx = makeItemCtx();
  const handled = await handleFlatCliMessage({ type: 'turn_complete' }, ctx, { providerKey: 'gemini' });
  assert.equal(handled, false);
});

test('handleFlatCliMessage: text/content event publishes content', async () => {
  const ctx = makeItemCtx();
  let published = '';
  ctx.publisher.publishContent = async (t) => { published = t; };
  ctx.getContent = () => '';
  const handled = await handleFlatCliMessage({ type: 'text', text: 'hello' }, ctx, { providerKey: 'gemini' });
  assert.equal(handled, true);
  assert.equal(published, 'hello');
});

// ─── buildStdinPayload ────────────────────────────────────────────────────

test('buildStdinPayload: prepends system prompt with separator when present', () => {
  assert.equal(buildStdinPayload('SYS', 'USER'), `SYS${PROMPT_SEPARATOR}USER`);
});

test('buildStdinPayload: returns prompt as-is when systemPrompt is empty', () => {
  assert.equal(buildStdinPayload('', 'just user'), 'just user');
  assert.equal(buildStdinPayload(null, 'just user'), 'just user');
  assert.equal(buildStdinPayload(undefined, 'just user'), 'just user');
});

// ─── handleClaudeStyleAssistantMessage ────────────────────────────────────

test('handleClaudeStyleAssistantMessage: tool_use block fires dispatchToolCall', async () => {
  const ctx = makeItemCtx();
  await handleClaudeStyleAssistantMessage({
    message: {
      content: [{ type: 'tool_use', id: 'toolu_a', name: 'workflow', input: { x: 1 } }],
    },
  }, ctx, { providerKey: 'gemini' });
  assert.equal(ctx._published.toolCalls.length, 1);
  assert.equal(ctx.pendingToolCalls.size, 1);
});

test('handleClaudeStyleAssistantMessage: text block publishes content + accumulates', async () => {
  const ctx = makeItemCtx();
  let captured = '';
  ctx.publisher.publishContent = async (t) => { captured = t; };
  let acc = '';
  ctx.getContent = () => acc;
  ctx.updateState = (u) => { if (u.fullContent != null) acc = u.fullContent; if (u.numTurns != null) {} };
  await handleClaudeStyleAssistantMessage({
    message: { content: [{ type: 'text', text: 'hi there' }] },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(captured, 'hi there');
});

test('handleClaudeStyleAssistantMessage: thinking block publishes + records section', async () => {
  const ctx = makeItemCtx();
  let captured = '';
  ctx.publisher.publishThinking = async (t) => { captured = t; };
  await handleClaudeStyleAssistantMessage({
    message: { content: [{ type: 'thinking', thinking: 'reasoning…' }] },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(captured, 'reasoning…');
  assert.equal(ctx.thinkingSections.length, 1);
});

test('handleClaudeStyleAssistantMessage: routes usage through opts.recordUsage', async () => {
  const ctx = makeItemCtx();
  let recorded = null;
  await handleClaudeStyleAssistantMessage({
    message: { content: [], usage: { input_tokens: 50, output_tokens: 10 } },
  }, ctx, {
    providerKey: 'gemini',
    recordUsage: (u) => { recorded = u; },
  });
  assert.equal(recorded.input_tokens, 50);
});

// ─── synthId monotonicity ─────────────────────────────────────────────────

test('synthId helpers do not collide within same millisecond', async () => {
  // Force two tool dispatches in the same Date.now() tick by issuing them
  // back-to-back without any await between the calls. The Set behind the
  // counter must mint distinct ids regardless.
  const ctx = makeItemCtx();
  await handleFlatCliMessage({ type: 'tool_use', name: 'a', input: {} }, ctx, { providerKey: 'gemini' });
  await handleFlatCliMessage({ type: 'tool_use', name: 'b', input: {} }, ctx, { providerKey: 'gemini' });
  const ids = Array.from(ctx.pendingToolCalls.keys());
  assert.equal(ids.length, 2);
  assert.notEqual(ids[0], ids[1], 'consecutive synthetic ids must differ even within the same ms');
});

test('handleCodexStyleItemEvent: tool_call_result with prior pending uses pending toolName', async () => {
  const ctx = makeItemCtx();
  // First a started event
  await handleCodexStyleItemEvent({
    type: 'item.started',
    item: { type: 'mcp_tool_call', id: 'call_3', tool: 'workflow', arguments: '{}' },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(ctx.pendingToolCalls.size, 1);
  // Then a result event
  await handleCodexStyleItemEvent({
    type: 'item.completed',
    item: { type: 'mcp_tool_call_result', call_id: 'call_3', output: 'result body' },
  }, ctx, { providerKey: 'mistral' });
  assert.equal(ctx.toolResults.length, 1);
  assert.equal(ctx.toolResults[0].toolCall.toolName, 'workflow');
  assert.equal(ctx.pendingToolCalls.size, 0);
});
