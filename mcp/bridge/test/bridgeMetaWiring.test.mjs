/**
 * Source-level wiring guard for the trusted `__BRIDGE_META__` extractor.
 *
 * `server.mjs` calls `app.listen` at import time and exports nothing, so it cannot be
 * imported by a unit test. That is exactly why it held an UNTESTED private copy of
 * `extractToolResultAndMetadata` for so long: the copy only knew about strings and block
 * arrays, so the whole MCP envelope Codex delivers fell through to `String(x)` and every
 * tool result on that provider lost its metadata.
 *
 * These tests read the sources instead, pinning the two invariants no runtime test covers:
 * one shared implementation, and no call site that serialises before extracting.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const read = (rel) => readFileSync(join(HERE, '..', rel), 'utf8');

test('server.mjs imports the shared extractor and defines no local copy (the import line is new; the no-copy assertion is the guard)', () => {
  const src = read('server.mjs');

  assert.match(
    src,
    /import\s*\{[^}]*\bextractToolResultAndMetadata\b[^}]*\}\s*from\s*'\.\/lib\/toolContent\.mjs'/,
    'server.mjs must import the shared extractor'
  );
  // Catches a declaration (`function f(`, `const f =`), a method shorthand, AND an object
  // property (`extractToolResultAndMetadata: (raw) => ...`) - the last one being the most
  // plausible drift, since that is exactly where the function is wired into the adapter ctx.
  // The legitimate shorthand `extractToolResultAndMetadata,` must stay allowed.
  assert.doesNotMatch(
    src,
    /(?:function\s+|(?:const|let|var)\s+)extractToolResultAndMetadata\s*[=(]/,
    'a private copy here drifts from the producer and silently loses metadata'
  );
  assert.doesNotMatch(
    src,
    /extractToolResultAndMetadata\s*:\s*[^,\n]/,
    'wiring must reference the shared import, not an inline reimplementation'
  );
});

test('server.mjs actually WIRES the extractor into the adapter ctx', () => {
  // Importing it is not enough: every adapter reads it off `ctx`, so deleting the property
  // from the ctx literal kills the feature at runtime for all four CLIs
  // (`ctx.extractToolResultAndMetadata is not a function`) while the import, the no-copy
  // check and the whole runtime suite stay green. This is the assertion that closes that.
  // Match OUTSIDE the import statements: a multi-line import specifier looks exactly like a
  // shorthand property, so a naive regex is satisfied by the import alone and the guard
  // passes while the ctx property is gone.
  const withoutImports = read('server.mjs').replace(/^import[\s\S]*?from\s+'[^']+';$/gm, '');
  assert.match(
    withoutImports,
    /^\s*extractToolResultAndMetadata\s*,\s*$/m,
    'the shared extractor must be present in the ctx passed to the adapters, not merely imported'
  );
});

test('agent-cli-server guards the session-recovery probe against a non-string error', () => {
  // Without the typeof guard, `result.error.includes(...)` throws on an object-shaped error,
  // the outer catch swallows it, and the tool comes back with a bogus
  // "result.error.includes is not a function" - losing the real error AND its metadata,
  // which is the very channel this change repairs.
  const src = readFileSync(join(HERE, '..', '..', 'agent-cli-server.mjs'), 'utf8');
  assert.match(
    src,
    /typeof\s+result\.error\s*===\s*'string'\s*&&\s*result\.error\.includes\(/,
    'the Session-not-found probe must not assume error is a string'
  );
});

test('agent-cli-server routes BOTH tool-failure paths through buildFailureContent', () => {
  // Reverting either call site to a bare `{content:[{type:'text',...}],isError:true}` drops
  // the metadata of every failed tool again, and no runtime test in this suite sees it.
  const src = readFileSync(join(HERE, '..', '..', 'agent-cli-server.mjs'), 'utf8');
  assert.match(src, /import\s*\{[^}]*\bbuildFailureContent\b[^}]*\}\s*from\s*'\.\/bridge\/lib\/toolContent\.mjs'/);
  const calls = src.match(/return buildFailureContent\(/g) || [];
  assert.ok(calls.length >= 2, 'both the session-recovery and the plain failure path must use it');
  // A BACKEND tool result (it has `.error` and may carry metadata) must never be turned into
  // a hand-rolled envelope. The bridge's own local errors (no session, caught exception)
  // legitimately stay inline: they have no metadata to lose.
  assert.doesNotMatch(
    src,
    /content:\s*\[\{\s*type:\s*'text',\s*text:\s*(?:retryR|r)esult\.error/,
    'a backend tool result must go through buildFailureContent, or its metadata is dropped'
  );
});

/**
 * Extract the argument text of every `extractToolResultAndMetadata(...)` call, balancing
 * parentheses so a later unrelated `JSON.stringify` on the same line cannot false-positive
 * (a plain regex did, and also missed `extract(String(raw))` and the pre-serialised-variable
 * form, which are the same bug wearing different clothes).
 */
function extractorCallArguments(src) {
  const calls = [];
  const needle = 'extractToolResultAndMetadata(';
  let from = 0;
  for (;;) {
    const at = src.indexOf(needle, from);
    if (at === -1) break;
    let depth = 0;
    let i = at + needle.length - 1;
    for (; i < src.length; i++) {
      if (src[i] === '(') depth++;
      else if (src[i] === ')') {
        depth--;
        if (depth === 0) break;
      }
    }
    calls.push(src.slice(at + needle.length, i));
    from = i + 1;
  }
  return calls;
}

test('no adapter call site serialises the payload before extracting', () => {
  // The exact regression: `extractToolResultAndMetadata(typeof x === 'string' ? x : JSON.stringify(x))`
  // escapes the marker's leading newline, so the sentinel is never found.
  for (const rel of [
    'lib/adapterHelpers.mjs',
    'server.mjs',
    'adapters/claude-adapter.mjs',
    'adapters/codex-adapter.mjs',
    'adapters/gemini-adapter.mjs',
    'adapters/mistral-adapter.mjs',
  ]) {
    const src = read(rel);
    if (rel === 'lib/adapterHelpers.mjs') {
      // Non-vacuity: this file MUST contain call sites, otherwise the loop below asserts
      // nothing and the whole guard silently degrades into a no-op.
      assert.ok(extractorCallArguments(src).length >= 3, 'adapterHelpers must still call the extractor');
    }
    for (const args of extractorCallArguments(src)) {
      assert.doesNotMatch(args, /JSON\.stringify|String\s*\(/, `${rel} must pass the raw payload; the extractor owns every shape`);
    }
    // The pre-serialised-variable form: `const s = JSON.stringify(raw); extract(s)`.
    for (const args of extractorCallArguments(src)) {
      const name = args.trim();
      if (!/^[A-Za-z_$][\w$]*$/.test(name)) continue;
      const assigned = new RegExp(`(?:const|let|var)\\s+${name}\\s*=[^;]*(?:JSON\\.stringify|String\\s*\\()`);
      assert.doesNotMatch(src, assigned, `${rel} serialises into "${name}" before extracting`);
    }
  }
});

test('the guard catches the inline serialise-before-extract shapes it is written for', () => {
  // A wiring guard that cannot fail is worse than none: pin its discriminating power.
  const bad = [
    "extractToolResultAndMetadata(typeof x === 'string' ? x : JSON.stringify(x))",
    'extractToolResultAndMetadata(String(raw))',
    'extractToolResultAndMetadata(\n  JSON.stringify(raw)\n)',
  ];
  for (const src of bad) {
    const args = extractorCallArguments(src);
    assert.equal(args.length, 1, `failed to parse: ${src}`);
    assert.match(args[0], /JSON\.stringify|String\s*\(/, `guard must reject: ${src}`);
  }
  // And must NOT fire on a legitimate call followed by an unrelated serialisation.
  const good = 'extractToolResultAndMetadata(raw); const a = JSON.stringify(other);';
  assert.doesNotMatch(extractorCallArguments(good)[0], /JSON\.stringify|String\s*\(/);
});
