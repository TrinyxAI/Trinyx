#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const inventoryPath = path.join(root, 'docker/cloud-images.json');
const composePath = path.join(root, 'docker/docker-compose.cloud.yml');
const runtimePath = path.join(root, 'docker/docker-compose.cloud.runtime.yml');
const thirdPartyPath = path.join(root, 'platform/release/third-party-images.json');
const runtimeInventoryPath = path.join(root, 'platform/release/runtime-inventory.json');

function fail(message) {
  throw new Error('[cloud-images] ' + message);
}

function parseServices(source, label) {
  const lines = source.split(/\r?\n/);
  const services = new Map();
  let inServices = false;
  let current = null;
  for (const line of lines) {
    if (!inServices) {
      if (line === 'services:') inServices = true;
      continue;
    }
    if (/^[^\s#]/.test(line) && line.trim() !== '') break;
    const match = line.match(/^  ([A-Za-z0-9_-]+):\s*$/);
    if (match) {
      current = match[1];
      services.set(current, []);
      continue;
    }
    if (current) services.get(current).push(line);
  }
  if (!inServices || services.size === 0) fail(label + ' has no services map');
  return services;
}

function serviceKeys(lines) {
  return lines.map((line) => line.match(/^    ([A-Za-z0-9_-]+):/))
    .filter(Boolean).map((match) => match[1]).sort();
}

const inventory = JSON.parse(fs.readFileSync(inventoryPath, 'utf8'));
if (inventory.schemaVersion !== 1 || !Array.isArray(inventory.images)) {
  fail('docker/cloud-images.json must use schemaVersion 1 with an images array');
}
if (inventory.images.length !== 14) {
  fail('expected exactly 14 repository-built Cloud images, got ' + inventory.images.length);
}
for (const field of ['name', 'service', 'package', 'environment', 'context', 'dockerfile']) {
  const values = inventory.images.map((item) => item[field]);
  if (values.some((value) => typeof value !== 'string' || value.length === 0)) {
    fail('every image must define ' + field);
  }
  if (field !== 'context' && new Set(values).size !== values.length) {
    fail('duplicate image ' + field);
  }
}
for (const item of inventory.images) {
  if (!/^cloud-[a-z0-9-]+$/.test(item.name)) {
    fail(item.name + ' is not a canonical cloud-* release name');
  }
  if (!/^ghcr\.io\/trinyxai\/trinyx-cloud-[a-z0-9-]+$/.test(item.package)) {
    fail(item.name + ' has a non-canonical GHCR package: ' + item.package);
  }
  if (!/^TRINYX_CLOUD_[A-Z0-9_]+_IMAGE$/.test(item.environment)) {
    fail(item.name + ' has an invalid environment binding: ' + item.environment);
  }
  if (!fs.existsSync(path.join(root, item.context))) fail(item.name + ' build context is missing');
  if (!fs.existsSync(path.join(root, item.dockerfile))) fail(item.name + ' Dockerfile is missing');
}

const runtimeInventory = JSON.parse(fs.readFileSync(runtimeInventoryPath, 'utf8'));
if (runtimeInventory.schemaVersion !== 1 || !Array.isArray(runtimeInventory.images)) {
  fail('platform/release/runtime-inventory.json has an invalid schema');
}
const canonicalBuiltCloud = runtimeInventory.images
  .filter((item) => item.role === 'cloud' && /^cloud-/.test(item.name))
  .filter((item) => !['cloud-postgres', 'cloud-redis', 'cloud-minio', 'cloud-minio-init', 'cloud-searxng', 'cloud-edge'].includes(item.name));
if (canonicalBuiltCloud.length !== 14) {
  fail('runtime inventory must contain exactly 14 repository-built Cloud entries');
}
const canonicalByName = new Map(canonicalBuiltCloud.map((item) => [item.name, item]));
for (const item of inventory.images) {
  const expected = canonicalByName.get(item.name);
  if (!expected) fail('repository-built Cloud image is missing from runtime inventory: ' + item.name);
  if (expected.service !== item.service || expected.environment !== item.environment) {
    fail('runtime inventory binding mismatch for ' + item.name);
  }
}
if (canonicalByName.size !== inventory.images.length) {
  fail('repository-built Cloud image name set differs from runtime inventory');
}

const thirdParty = JSON.parse(fs.readFileSync(thirdPartyPath, 'utf8'));
if (thirdParty.schemaVersion !== 1 || thirdParty.kind !== 'static-third-party' || !Array.isArray(thirdParty.images)) {
  fail('platform/release/third-party-images.json has an invalid schema');
}
const cloudDependencies = thirdParty.images.filter((item) => item.role === 'cloud');
if (cloudDependencies.length !== 6) {
  fail('expected exactly 6 pinned Cloud dependencies, got ' + cloudDependencies.length);
}
for (const item of cloudDependencies) {
  for (const field of ['name', 'service', 'package', 'environment', 'digest', 'immutableRef']) {
    if (typeof item[field] !== 'string' || item[field].length === 0) {
      fail('Cloud dependency ' + (item.name || '<unnamed>') + ' is missing ' + field);
    }
  }
  if (!/^sha256:[0-9a-f]{64}$/.test(item.digest)) {
    fail(item.name + ' has an invalid pinned digest');
  }
  if (item.immutableRef !== item.package + '@' + item.digest) {
    fail(item.name + ' immutableRef does not match package + digest');
  }
  if (!/^TRINYX_CLOUD_[A-Z0-9_]+_IMAGE$/.test(item.environment)) {
    fail(item.name + ' has an invalid environment binding: ' + item.environment);
  }
}

const composeServices = parseServices(fs.readFileSync(composePath, 'utf8'), 'development Compose');
const builtServices = [...composeServices]
  .filter(([, lines]) => lines.some((line) => /^    build:/.test(line)))
  .map(([name]) => name)
  .sort();
const inventoryServices = inventory.images.map((item) => item.service).sort();
if (JSON.stringify(builtServices) !== JSON.stringify(inventoryServices)) {
  fail('build inventory mismatch: Compose=' + builtServices.join(',') +
    ' inventory=' + inventoryServices.join(','));
}

const runtimeServices = parseServices(
  fs.readFileSync(runtimePath, 'utf8'), 'immutable runtime override');
const expectedRuntimeServices = [
  ...inventory.images.map((item) => item.service),
  ...cloudDependencies.map((item) => item.service),
].sort();
const actualRuntimeServices = [...runtimeServices.keys()].sort();
if (JSON.stringify(actualRuntimeServices) !== JSON.stringify(expectedRuntimeServices)) {
  fail('runtime override service set mismatch: expected=' + expectedRuntimeServices.join(',') +
    ' actual=' + actualRuntimeServices.join(','));
}

for (const item of inventory.images) {
  const lines = runtimeServices.get(item.service);
  if (!lines) fail('runtime override omits ' + item.service);
  const keys = serviceKeys(lines);
  if (JSON.stringify(keys) !== JSON.stringify(['build', 'image'])) {
    fail(item.service + ' runtime override may change only build and image');
  }
  if (!lines.some((line) => line.trim() === 'build: !reset null')) {
    fail(item.service + ' does not reset its local build definition');
  }
  const imageLine = lines.find((line) => line.trimStart().startsWith('image: ')) || '';
  if (!imageLine.includes('${' + item.environment + ':?') ||
      !imageLine.includes(item.package + '@sha256')) {
    fail(item.service + ' does not require its immutable digest environment value');
  }
}

for (const item of cloudDependencies) {
  const lines = runtimeServices.get(item.service);
  if (!lines) fail('runtime override omits dependency ' + item.service);
  const keys = serviceKeys(lines);
  if (JSON.stringify(keys) !== JSON.stringify(['image'])) {
    fail(item.service + ' dependency runtime override may change only image');
  }
  const imageLine = lines.find((line) => line.trimStart().startsWith('image: ')) || '';
  if (!imageLine.includes('${' + item.environment + ':?')) {
    fail(item.service + ' does not require its pinned dependency environment value');
  }
}

const renderedArg = process.argv.indexOf('--rendered');
if (renderedArg !== -1) {
  const renderedPath = process.argv[renderedArg + 1];
  if (!renderedPath) fail('--rendered requires a Compose JSON path');
  const rendered = JSON.parse(fs.readFileSync(renderedPath, 'utf8'));

  for (const item of inventory.images) {
    const service = rendered.services?.[item.service];
    if (!service) fail('rendered runtime omits ' + item.service);
    if (Object.hasOwn(service, 'build')) {
      fail(item.service + ' retains a build definition in immutable runtime');
    }
    const packagePattern = item.package.replaceAll('.', '\\.');
    const expected = new RegExp('^' + packagePattern + '@sha256:[0-9a-f]{64}$');
    if (!expected.test(service.image || '')) {
      fail(item.service + ' is not digest-pinned: ' + (service.image || '<missing>'));
    }
  }

  for (const item of cloudDependencies) {
    const service = rendered.services?.[item.service];
    if (!service) fail('rendered runtime omits dependency ' + item.service);
    if (service.image !== item.immutableRef) {
      fail(item.service + ' dependency digest mismatch: ' + (service.image || '<missing>'));
    }
  }

  for (const [serviceName, service] of Object.entries(rendered.services || {})) {
    const image = service.image || '';
    if (image.startsWith('trinyx-cloud/') || /:local(?:$|@)|:latest(?:$|@)/.test(image)) {
      fail(serviceName + ' retained a mutable/local runtime image: ' + image);
    }
  }
}

console.log('[cloud-images] validated canonical 14 built + 6 dependencies = 20 immutable Cloud runtime services');
