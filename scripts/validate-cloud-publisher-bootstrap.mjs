#!/usr/bin/env node

import fs from 'node:fs';
import { validateCloudPublisherOidcBoundary } from './cloud-publisher-oidc-policy.mjs';

const backendPath = '.github/workflows/build-trinyx-backend.yml';
const cloudPath = '.github/workflows/build-trinyx-cloud-images.yml';
const backend = fs.readFileSync(backendPath, 'utf8').replace(/\r\n/g, '\n');
const cloud = fs.readFileSync(cloudPath, 'utf8').replace(/\r\n/g, '\n');

function requireText(source, expected, label) {
  if (!source.includes(expected)) throw new Error('[cloud-bootstrap] missing ' + label);
}
function forbid(source, pattern, label) {
  if (pattern.test(source)) throw new Error('[cloud-bootstrap] forbidden ' + label);
}

requireText(cloud, 'workflow_call:', 'Cloud workflow_call trigger');
requireText(cloud, 'publish:', 'Cloud reusable publish input');
requireText(
  cloud,
  "if: github.event_name == 'workflow_dispatch' || (github.event_name == 'workflow_call' && inputs.publish)",
  'explicit direct/reusable publication gate');
requireText(
  cloud,
  "cancel-in-progress: ${{ github.event_name == 'pull_request' }}",
  'non-cancellable manual publication');
requireText(backend, "if: github.event_name == 'workflow_dispatch'", 'manual bootstrap gate');
requireText(
  backend,
  'uses: ./.github/workflows/build-trinyx-cloud-images.yml',
  'same-commit local reusable workflow');
requireText(backend, 'publish: true', 'explicit reusable publication input');
requireText(backend, 'needs: [test, frontend-test, publish]', 'backend-before-Cloud ordering');
requireText(
  backend,
  "cancel-in-progress: ${{ github.event_name != 'workflow_dispatch' }}",
  'non-cancellable backend manual publication');
requireText(backend, 'packages: write', 'backend publication permission');
requireText(cloud, 'packages: write', 'Cloud publication permission');
requireText(backend, 'org.opencontainers.image.revision=${{ github.sha }}', 'backend SHA label');
requireText(cloud, 'org.opencontainers.image.revision=${{ github.sha }}', 'Cloud SHA label');
requireText(backend, 'Refusing to move immutable', 'backend SHA conflict guard');
requireText(cloud, 'Refusing to move immutable', 'Cloud SHA conflict guard');

for (const [name, source] of [['backend', backend], ['cloud', cloud]]) {
  requireText(source, 'permissions:\n  contents: read', name + ' global read-only permissions');
  forbid(source, /pull_request_target\s*:/, name + ' pull_request_target');
  forbid(source, /aws-actions\//, name + ' AWS action');
  forbid(source, /:\s*latest(?:\s|$)/m, name + ' latest image tag');
  forbid(source, /(?:PAT|GHCR_TOKEN|CUSTOM_GITHUB_TOKEN)/, name + ' custom long-lived token');
}

validateCloudPublisherOidcBoundary(backend, cloud);

const cloudPullRequestJob = cloud.match(/\n  build-pr:[\s\S]*?\n  publish:/);
if (!cloudPullRequestJob) throw new Error('[cloud-bootstrap] cannot isolate Cloud PR job');
forbid(cloudPullRequestJob[0], /packages:\s*write/, 'packages write in Cloud PR build');
requireText(cloudPullRequestJob[0], 'push: false', 'Cloud PR push=false');

const backendCall = backend.match(/\n  publish-cloud-runtime:[\s\S]*$/);
if (!backendCall) throw new Error('[cloud-bootstrap] cannot isolate backend bootstrap job');
requireText(backendCall[0], "if: github.event_name == 'workflow_dispatch'", 'dispatch-only call');
requireText(backendCall[0], 'packages: write', 'caller package permission ceiling');

console.log('[cloud-bootstrap] reusable publication path and least-privilege gates validated');
