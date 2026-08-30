import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const testDir = dirname(fileURLToPath(import.meta.url));
const mcpDir = resolve(testDir, '..', '..');
const dockerfile = readFileSync(resolve(mcpDir, 'bridge', 'Dockerfile'), 'utf8');
const dockerignore = readFileSync(resolve(mcpDir, '.dockerignore'), 'utf8');

test('the bridge Docker context excludes every local node_modules directory', () => {
  assert.match(dockerignore, /^\*\*\/node_modules\/$/m);
});

test('the bridge Docker context excludes the test tree (repo-excluded e2e suites must not ship in the public image)', () => {
  // The public bridge image builds from this PRIVATE context while the e2e test
  // files are excluded from the public repo; without this ignore they would ship
  // in the image and bypass the CE export gates.
  assert.match(dockerignore, /^bridge\/test\/$/m);
});

test('bridge dependencies are installed after the final source copy', () => {
  const sourceCopy = dockerfile.indexOf('COPY bridge/ ./bridge/');
  const dependencyInstall = dockerfile.indexOf('RUN cd bridge && npm ci --omit=dev');
  assert.ok(sourceCopy >= 0, 'expected the bridge source copy');
  assert.ok(dependencyInstall > sourceCopy, 'npm ci must run after the bridge source copy');
});

test('root MCP dependencies are installed after their source copies', () => {
  const lastRootSourceCopy = dockerfile.indexOf('COPY shell-tool.mjs ./');
  const dependencyInstall = dockerfile.indexOf('RUN npm ci --omit=dev');
  assert.ok(lastRootSourceCopy >= 0, 'expected the root MCP source copies');
  assert.ok(dependencyInstall > lastRootSourceCopy, 'root npm ci must run after the root source copies');
  assert.match(dockerfile, /await import\('diff'\)/);
  assert.match(dockerfile, /await import\('@modelcontextprotocol\/sdk\/server\/index\.js'\)/);
});

test('the bridge image build asserts its runtime dependencies are present', () => {
  assert.match(dockerfile, /test -f node_modules\/express\/index\.js/);
  assert.match(dockerfile, /test -f node_modules\/ioredis\/built\/index\.js/);
});

test('npm ci skips the audit request so a TLS-MITM proxy cannot crash the build', () => {
  // npm 10.8.x crashes with "Exit handler never called!" when the audit HTTPS
  // request fails TLS verification behind an interception proxy. Every dependency
  // install in the build must opt out of audit so the build stays deterministic.
  const ciCommands = dockerfile.match(/npm ci [^\n\\]*/g) ?? [];
  assert.ok(ciCommands.length >= 2, 'expected the root and bridge npm ci commands');
  for (const cmd of ciCommands) {
    assert.match(cmd, /--no-audit/, `npm ci must pass --no-audit: ${cmd}`);
  }
});

test('the pip install carries its own C toolchain, and drops it in the same layer', () => {
  // tree-sitter, a mistral-vibe dependency, publishes musllinux wheels for x86_64 only,
  // so on aarch64 pip builds it from source. Without a compiler in the image that step
  // dies with "No such file or directory: 'cc'", which is how the first multi-arch CE
  // release failed - 40 minutes in, on a runner, with nothing published. The toolchain
  // must be installed AND removed inside the same RUN, or the published image grows by
  // an entire build-base on both arches.
  const runLayers = dockerfile
    .replace(/\\\r?\n/g, ' ') // one RUN per line, line continuations joined
    .split(/\r?\n/)
    .filter((line) => line.startsWith('RUN '));
  const layer = runLayers.find((line) => line.includes('pip3 install'));
  assert.ok(layer, 'expected a RUN layer that pip-installs mistral-vibe');
  assert.match(layer, /--virtual \.py-build-deps/, 'the toolchain must be a virtual apk package');
  assert.match(layer, /build-base/, 'a C compiler is required to build tree-sitter on aarch64');
  assert.match(layer, /python3-dev/, 'Python.h is required to build a CPython extension');
  assert.match(layer, /apk del \.py-build-deps/, 'the toolchain must be removed in the same layer');
});
