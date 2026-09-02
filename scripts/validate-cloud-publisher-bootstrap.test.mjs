#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import { validateCloudPublisherOidcBoundary } from './cloud-publisher-oidc-policy.mjs';

const backend = fs.readFileSync('.github/workflows/build-trinyx-backend.yml', 'utf8');
const cloud = fs.readFileSync('.github/workflows/build-trinyx-cloud-images.yml', 'utf8');

function mutateJob(source, jobName, mutate) {
  const jobs = /^jobs:\s*$/m.exec(source);
  assert.ok(jobs, 'jobs mapping must exist');
  const bodyOffset = jobs.index + jobs[0].length;
  const body = source.slice(bodyOffset);
  const starts = [...body.matchAll(/^  ([A-Za-z0-9_-]+):\s*$/gm)];
  const index = starts.findIndex((entry) => entry[1] === jobName);
  assert.notEqual(index, -1, 'job must exist: ' + jobName);
  const start = bodyOffset + starts[index].index;
  const end = index + 1 < starts.length ? bodyOffset + starts[index + 1].index : source.length;
  return source.slice(0, start) + mutate(source.slice(start, end)) + source.slice(end);
}

function grantIdToken(source, jobName) {
  return mutateJob(source, jobName, (block) => {
    if (/^    permissions:\s*$/m.test(block)) {
      return block.replace(/^    permissions:\s*$/m, '$&\n      id-token: write');
    }
    return block.replace(
      new RegExp('^  ' + jobName + ':\\s*$', 'm'),
      '$&\n    permissions:\n      id-token: write',
    );
  });
}

test('accepts the existing manual delegated staging and release OIDC bridges', () => {
  assert.doesNotThrow(() => validateCloudPublisherOidcBoundary(backend, cloud));
});

for (const jobName of ['build-pr', 'publish', 'publish-cloud-runtime']) {
  test('rejects id-token write on backend application job ' + jobName, () => {
    const changed = grantIdToken(backend, jobName);
    assert.throws(
      () => validateCloudPublisherOidcBoundary(changed, cloud),
      new RegExp('forbidden backend id-token write in job ' + jobName),
    );
  });
}

test('rejects id-token write anywhere in the Cloud publisher workflow', () => {
  const changed = cloud.replace(
    'permissions:\n  contents: read',
    'permissions:\n  contents: read\n  id-token: write',
  );
  assert.notEqual(changed, cloud);
  assert.throws(
    () => validateCloudPublisherOidcBoundary(backend, changed),
    /forbidden cloud id-token write/,
  );
});

test('rejects an arbitrary SHA-pinned backend OIDC bridge', () => {
  const unexpected = [
    '',
    '  unexpected_bridge:',
    "    if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/codex/platform-release-automation'",
    '    permissions:',
    '      contents: read',
    '      id-token: write',
    '    uses: TrinyxAI/Trinyx/.github/workflows/staging-oidc-probe-impl.yml@f25b094611c01f45d3876425a86fb6fdd9b00d91',
    '',
  ].join('\n');
  const changed = backend.replace('\n  test:\n', unexpected + '\n  test:\n');
  assert.notEqual(changed, backend);
  assert.throws(
    () => validateCloudPublisherOidcBoundary(changed, cloud),
    /forbidden backend id-token write in job unexpected_bridge/,
  );
});
