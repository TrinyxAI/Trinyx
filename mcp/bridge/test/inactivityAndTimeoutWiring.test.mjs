/**
 * Wiring regression tests for two related bridge fixes:
 *
 *  (#3) The bridge must honor the per-agent `executionTimeout` as the spawn wall-clock cap
 *       (bounded by MAX_TIMEOUT_MS) instead of always running every CLI to MAX_TIMEOUT_MS.
 *       Before this the DTO field was never destructured and was silently ignored.
 *
 *  (#6) Every bridge run gets an INACTIVITY watchdog: if the CLI emits no stdout for the
 *       configured window it is killed and the run ends as INACTIVITY_TIMEOUT (distinct from
 *       the total TIMEOUT). The window resets on each stdout line.
 *
 *  (#1) agent-cli-server.mjs must lift Node/undici's 300s default headers/body timeout so a long
 *       synchronous tool call (a sub-agent run, a long workflow) returns its real terminal result
 *       instead of a generic "fetch failed" at exactly 5 min.
 *
 * These are parse-time forwarding contracts (no Express + real subprocess needed). Mirrors
 * enabledModulesWiring.test.mjs.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { DEFAULT_INACTIVITY_MS } from '../lib/inactivityResolver.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const serverSource = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
const cliSource = readFileSync(resolve(__dirname, '..', '..', 'agent-cli-server.mjs'), 'utf8');

/** Extract the object literal passed to `await executeViaCli({ ... })`. */
function executeViaCliCallArgs() {
  const callIdx = serverSource.indexOf('await executeViaCli({');
  assert.notStrictEqual(callIdx, -1, 'executeViaCli call site not found');
  const argsStart = serverSource.indexOf('{', callIdx);
  let depth = 0, end = argsStart;
  for (let i = argsStart; i < serverSource.length; i++) {
    if (serverSource[i] === '{') depth++;
    else if (serverSource[i] === '}') { depth--; if (depth === 0) { end = i; break; } }
  }
  return serverSource.slice(argsStart, end + 1);
}

// ── #3 executionTimeout → spawn wall-clock ────────────────────────────────────

test('the /execute DTO destructure reads executionTimeout and inactivityTimeout off the body', () => {
  const dtoBlock = serverSource.slice(serverSource.indexOf('= dto;') - 4000, serverSource.indexOf('= dto;'));
  assert.ok(/\bexecutionTimeout\b/.test(dtoBlock),
    'handler MUST destructure executionTimeout from dto, else the per-agent total cap is ignored');
  assert.ok(/\binactivityTimeout\b/.test(dtoBlock),
    'handler MUST destructure inactivityTimeout from dto so a per-agent window can override the default');
});

test('handler passes spawnTimeoutMs (bounded by MAX_TIMEOUT_MS) and inactivityMs to executeViaCli', () => {
  const args = executeViaCliCallArgs();
  assert.ok(/spawnTimeoutMs:\s*Math\.min\(MAX_TIMEOUT_MS,/.test(args),
    'spawnTimeoutMs must be min(MAX_TIMEOUT_MS, executionTimeout*1000) so the agent cap never exceeds the global ceiling');
  assert.ok(/\bexecutionTimeout\b/.test(args),
    'the spawnTimeoutMs expression must derive from executionTimeout');
  assert.ok(/inactivityMs:/.test(args),
    'inactivityMs must be passed so the watchdog window crosses into executeViaCli');
});

test('executeViaCli destructures spawnTimeoutMs and inactivityMs', () => {
  const match = serverSource.match(/async function executeViaCli\(\{([^}]+)\}\)/);
  assert.ok(match, 'executeViaCli signature not found');
  assert.ok(match[1].includes('spawnTimeoutMs'), 'executeViaCli MUST destructure spawnTimeoutMs');
  assert.ok(match[1].includes('inactivityMs'), 'executeViaCli MUST destructure inactivityMs');
});

test('child spawn uses spawnTimeoutMs (not the hardcoded MAX_TIMEOUT_MS) as its timeout', () => {
  assert.match(serverSource, /timeout:\s*spawnTimeoutMs\s*\|\|\s*MAX_TIMEOUT_MS/,
    'spawn timeout must be spawnTimeoutMs (fallback MAX_TIMEOUT_MS) so a configured executionTimeout actually bounds the CLI');
});

// ── #6 inactivity watchdog ────────────────────────────────────────────────────

test('an idle timer kills the child and ends the run as INACTIVITY_TIMEOUT', () => {
  assert.match(serverSource, /killChildOnce\('inactivity'\)/,
    'the inactivity timer must terminate the child via killChildOnce');
  assert.match(serverSource, /inactivityTimedOut\s*=\s*true/,
    'the inactivity timer must set the inactivityTimedOut sentinel');
  assert.match(serverSource, /stopReason\s*=\s*AgentStopReason\.INACTIVITY_TIMEOUT/,
    'the inactivity timer must set stopReason to INACTIVITY_TIMEOUT');
});

test('the idle timer is reset on every stdout line (a working CLI is never killed)', () => {
  const lineIdx = serverSource.indexOf("rl.on('line'");
  assert.notStrictEqual(lineIdx, -1, "rl.on('line') handler not found");
  const handler = serverSource.slice(lineIdx, lineIdx + 300);
  assert.match(handler, /resetIdleTimer\(\)/,
    'the line handler MUST resetIdleTimer so any CLI output restarts the inactivity clock');
});

test('the idle timer is cleared on child close and error (no leak / no fire after exit)', () => {
  const closeIdx = serverSource.indexOf("child.on('close'");
  const errorIdx = serverSource.indexOf("child.on('error'");
  assert.match(serverSource.slice(closeIdx, closeIdx + 200), /clearIdleTimer\(\)/,
    'child close MUST clearIdleTimer');
  assert.match(serverSource.slice(errorIdx, errorIdx + 200), /clearIdleTimer\(\)/,
    'child error MUST clearIdleTimer');
});

test('ctx.state exposes inactivityTimedOut for the stopReason mapper', () => {
  assert.match(serverSource, /get inactivityTimedOut\(\)\s*\{\s*return inactivityTimedOut;/,
    'ctx.state must expose inactivityTimedOut so resolveStopReason can prefer INACTIVITY_TIMEOUT');
});

// ── #1 lift undici's 300s default on tool calls ───────────────────────────────

test('agent-cli-server.mjs disables undici headers/body timeout for the (synchronous, long) tool calls', () => {
  assert.match(cliSource, /import \{ Agent, setGlobalDispatcher \} from 'undici'/,
    'agent-cli-server.mjs must import undici Agent + setGlobalDispatcher');
  assert.match(cliSource, /setGlobalDispatcher\(new Agent\(\{ headersTimeout: 0, bodyTimeout: 0 \}\)\)/,
    'a long sub-agent tool call must not be aborted by undici\'s 300s default - headers/body timeout disabled');
});

// ── the watchdog mechanics live in lib/inactivityWatchdog.mjs (behaviorally tested) ──

test('server.mjs builds its idle timer from the shared lib watchdog (behaviorally tested module)', () => {
  assert.match(serverSource, /import \{ createInactivityWatchdog \} from '\.\/lib\/inactivityWatchdog\.mjs'/,
    'server.mjs must import the extracted watchdog - the mechanics are behaviorally pinned there');
  assert.match(serverSource, /createInactivityWatchdog\(inactivityMs,/,
    'the idle watchdog must be constructed with the resolved inactivityMs window');
});

// ── MAX_TIMEOUT_MS default must cover the 7200s timeout contract ─────────────

test('the bridge hard cap default covers the 7200s executionTimeout/inactivityTimeout contract', () => {
  const match = serverSource.match(/BRIDGE_MAX_TIMEOUT_MS \|\| String\((\d+)\s*\*\s*60\s*\*\s*1000\)/);
  assert.ok(match, 'MAX_TIMEOUT_MS default expression not found');
  const minutes = parseInt(match[1], 10);
  assert.ok(minutes * 60 > 7200,
    `MAX_TIMEOUT_MS default (${minutes} min) must exceed the 7200s contract max - under the old 65-min cap a valid 2h budget could never elapse on the bridge`);
});

// ── the server-side approval park must expire BEFORE the inactivity watchdog ──

test('the approval-gate park budget stays under the bridge inactivity watchdog', () => {
  // A parked tool call produces no CLI stdout, and stdout is the only thing that resets
  // this watchdog. So the two windows race on every gated bridge call, and if the park is
  // not strictly shorter the watchdog always wins: the run is killed as INACTIVITY_TIMEOUT
  // instead of the park expiring cleanly - and an approval clicked just before that spends
  // real credits on a tool whose result nobody is left to read. The two values live in
  // different languages and different services, so only a check like this keeps them honest.
  const gateSource = readFileSync(
    resolve(__dirname, '..', '..', '..', 'backend', 'agent-service', 'src', 'main', 'java',
      'com', 'apimarketplace', 'agent', 'service', 'execution', 'ToolApprovalGate.java'), 'utf8');
  const match = gateSource.match(/agent\.tool\.approval-gate\.timeout-ms:(\d+)/);
  assert.ok(match, 'approval-gate.timeout-ms default not found in ToolApprovalGate.java');
  const parkMs = parseInt(match[1], 10);

  assert.ok(parkMs < DEFAULT_INACTIVITY_MS,
    `the park budget (${parkMs}ms) must be shorter than the inactivity watchdog (${DEFAULT_INACTIVITY_MS}ms)`);
  // Not merely shorter: the watchdog is armed when the CLI last spoke, which is strictly
  // before the tool call reaches the gate, so an equal-ish value still loses the race.
  assert.ok(DEFAULT_INACTIVITY_MS - parkMs >= 30_000,
    `leave at least 30s of margin - the watchdog starts counting before the park does (park=${parkMs}ms)`);

  // The gate ALSO caps a park at half of whatever window it is told about, which is what
  // leaves room for the tool to actually run after the approval - a park sized to the whole
  // window would be killed mid-execution, right after the user said yes.
  assert.match(gateSource, /inactivityWindowMs\(\)\s*\/\s*2/,
    'the park must be capped at half the run inactivity window, leaving the other half for the tool to run');
});

// ── the watchdog window must actually REACH the server-side gate ──────────────

test('the run inactivity window is threaded bridge -> CLI server -> session credentials', () => {
  // Three links, three files, two languages. Break any one and every bridge park silently
  // reverts to the gate's own default, losing the race with the watchdog - with nothing
  // failing anywhere, because each side keeps working on its own.
  assert.match(serverSource, /AGENT_INACTIVITY_SECONDS:\s*String\(/,
    'the bridge must hand the resolved window to the CLI child');

  const cliServerSource = readFileSync(resolve(__dirname, '..', '..', 'agent-cli-server.mjs'), 'utf8');
  assert.match(cliServerSource, /process\.env\.AGENT_INACTIVITY_SECONDS/,
    'agent-cli-server must read the window off its env');
  assert.match(cliServerSource, /body\.inactivityTimeoutSeconds\s*=/,
    'agent-cli-server must forward it in the session body');

  const cliAgentServiceSource = readFileSync(
    resolve(__dirname, '..', '..', '..', 'backend', 'agent-service', 'src', 'main', 'java',
      'com', 'apimarketplace', 'agent', 'service', 'cli', 'CliAgentService.java'), 'utf8');
  assert.match(cliAgentServiceSource, /credentials\.put\("__inactivityTimeoutSeconds__"/,
    'CliAgentService must put it in the session credentials the gate reads');
});
