const ALLOWED_BACKEND_OIDC_JOBS = new Set([
  'release_candidate',
  'staging_oidc_probe',
  'staging_release_register',
  'staging_legacy_normalization_plan',
  'staging_legacy_adopt',
  'staging_qualification',
]);

const BACKEND_APPLICATION_JOBS = [
  'test',
  'frontend-test',
  'build-pr',
  'publish',
  'publish-cloud-runtime',
];

function fail(label) {
  throw new Error('[cloud-bootstrap] ' + label);
}

function jobBlocks(source) {
  const jobs = /^jobs:\s*$/m.exec(source);
  if (!jobs) fail('cannot isolate workflow jobs');
  const body = source.slice(jobs.index + jobs[0].length);
  const starts = [...body.matchAll(/^  ([A-Za-z0-9_-]+):\s*$/gm)];
  if (starts.length === 0) fail('workflow has no jobs');
  const blocks = new Map();
  for (let index = 0; index < starts.length; index += 1) {
    const start = starts[index].index;
    const end = index + 1 < starts.length ? starts[index + 1].index : body.length;
    blocks.set(starts[index][1], body.slice(start, end));
  }
  return blocks;
}

function hasIdTokenWrite(block) {
  return /id-token:\s*write/.test(block);
}

export function validateCloudPublisherOidcBoundary(backend, cloud) {
  if (hasIdTokenWrite(cloud)) {
    fail('forbidden cloud id-token write');
  }

  const backendJobs = jobBlocks(backend);
  for (const name of BACKEND_APPLICATION_JOBS) {
    const block = backendJobs.get(name);
    if (!block) fail('missing backend application job ' + name);
    if (hasIdTokenWrite(block)) {
      fail('forbidden backend id-token write in job ' + name);
    }
  }

  for (const [name, block] of backendJobs) {
    if (!hasIdTokenWrite(block)) continue;
    if (!ALLOWED_BACKEND_OIDC_JOBS.has(name)) {
      fail('forbidden backend id-token write in job ' + name);
    }
    if (!block.includes("github.event_name == 'workflow_dispatch'")) {
      fail('OIDC bridge is not manual ' + name);
    }
    if (!block.includes("github.ref == 'refs/heads/codex/platform-release-automation'")) {
      fail('OIDC bridge is not platform-branch-bound ' + name);
    }
    if (!/^    uses: TrinyxAI\/Trinyx\/\.github\/workflows\/[A-Za-z0-9_.-]+\.yml@[0-9a-f]{40}\s*$/m.test(block)) {
      fail('OIDC bridge is not delegated to a SHA-pinned reusable workflow ' + name);
    }
    if (/^    steps:\s*$/m.test(block)) {
      fail('OIDC bridge contains direct steps ' + name);
    }
  }
}
