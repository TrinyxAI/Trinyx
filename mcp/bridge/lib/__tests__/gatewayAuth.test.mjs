// Tests for the gateway HMAC signer (lib/gatewayAuth.mjs).
//
// The parity block reads shared/contracts/gateway-signature-fixtures.json - the
// SAME fixture consumed by the Java twin GatewaySignatureParityTest. If the JS and
// Java HMAC implementations ever drift, one side fails against the shared golden.
//
// Run with: node --test mcp/bridge/lib/__tests__/gatewayAuth.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gatewaySignedHeaders, internalSignedHeaders, gatewaySignedHeadersV2, gatewayV2CanonicalPayload, bodySha256 } from '../gatewayAuth.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));

function locateFixture() {
  let here = __dirname;
  for (let i = 0; i < 6; i++) {
    const candidate = resolve(here, 'shared/contracts/gateway-signature-fixtures.json');
    if (existsSync(candidate)) return candidate;
    const parent = dirname(here);
    if (parent === here) break;
    here = parent;
  }
  throw new Error(`gateway-signature-fixtures.json not found from ${__dirname}`);
}

const fixture = JSON.parse(readFileSync(locateFixture(), 'utf8'));

test('cross-language parity: each fixture case reproduces the golden signature', () => {
  assert.ok(fixture.cases.length > 0, 'fixture has cases');
  for (const c of fixture.cases) {
    const headers = gatewaySignedHeaders({
      secretKey: fixture.secretKey,
      providerId: c.providerId,
      userId: c.userId,
      organizationId: c.organizationId,
      timestampMs: Number(c.timestamp),
    });
    assert.equal(headers['X-Gateway-Secret'], c.expectedSignature, `case "${c.name}" signature`);
    assert.equal(headers['X-Gateway-Timestamp'], String(c.timestamp), `case "${c.name}" timestamp echoed`);
    assert.equal(headers['X-Provider-ID'], c.providerId, `case "${c.name}" provider echoed`);
  }
});

test('signature changes when userId changes (binds the user)', () => {
  const base = { secretKey: 's3cr3t', providerId: 'p', organizationId: 'o', timestampMs: 1700000000000 };
  const a = gatewaySignedHeaders({ ...base, userId: '1' })['X-Gateway-Secret'];
  const b = gatewaySignedHeaders({ ...base, userId: '2' })['X-Gateway-Secret'];
  assert.notEqual(a, b);
});

test('signature changes when organizationId changes (binds the org)', () => {
  const base = { secretKey: 's3cr3t', providerId: 'p', userId: 'u', timestampMs: 1700000000000 };
  const a = gatewaySignedHeaders({ ...base, organizationId: 'orgA' })['X-Gateway-Secret'];
  const b = gatewaySignedHeaders({ ...base, organizationId: 'orgB' })['X-Gateway-Secret'];
  assert.notEqual(a, b);
});

test('signature is "gw_"-prefixed url-safe base64 with no padding', () => {
  const sig = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: 'u', timestampMs: 1 })['X-Gateway-Secret'];
  assert.match(sig, /^gw_[A-Za-z0-9_-]+$/, 'url-safe alphabet, no + / or = padding');
});

test('empty secret → provider-id-only fallback (no signature headers)', () => {
  const h = gatewaySignedHeaders({ secretKey: '', providerId: 'internal-credit-client', userId: '42' });
  assert.deepEqual(h, { 'X-Provider-ID': 'internal-credit-client' });
  assert.equal(h['X-Gateway-Secret'], undefined);
  assert.equal(h['X-Gateway-Timestamp'], undefined);
});

test('null user/org coerce to empty string (match Java safeUser/safeOrg)', () => {
  const withNulls = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: null, organizationId: null, timestampMs: 1700000000000 })['X-Gateway-Secret'];
  const withEmpties = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: '', organizationId: '', timestampMs: 1700000000000 })['X-Gateway-Secret'];
  assert.equal(withNulls, withEmpties);
});

test('numeric and string userId of the same value sign identically (String coercion)', () => {
  const asNum = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: 42, timestampMs: 1700000000000 })['X-Gateway-Secret'];
  const asStr = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: '42', timestampMs: 1700000000000 })['X-Gateway-Secret'];
  assert.equal(asNum, asStr);
});

// --- internalSignedHeaders: the wiring guarantee (sent identity == signed identity) ---

test('internalSignedHeaders: with org, sends X-User-ID + X-Organization-ID and signs the SAME org', () => {
  const args = { secretKey: 'k', providerId: 'internal-credit-client', userId: '42', organizationId: 'org_7', timestampMs: 1700000000000 };
  const h = internalSignedHeaders({ ...args, extra: { Accept: 'application/json' } });
  assert.equal(h['X-User-ID'], '42');
  assert.equal(h['X-Organization-ID'], 'org_7');
  assert.equal(h['Accept'], 'application/json');
  // The signature MUST be the one computed over the org we actually send - proving
  // sent-identity and signed-identity cannot diverge.
  const expected = gatewaySignedHeaders(args)['X-Gateway-Secret'];
  assert.equal(h['X-Gateway-Secret'], expected);
});

test('internalSignedHeaders: empty org → no X-Organization-ID header, signature over org=""', () => {
  const args = { secretKey: 'k', providerId: 'internal-credit-client', userId: '42', organizationId: '', timestampMs: 1700000000000 };
  const h = internalSignedHeaders(args);
  assert.equal(h['X-User-ID'], '42');
  assert.equal('X-Organization-ID' in h, false, 'org header omitted when empty (filter reads missing as "")');
  const expected = gatewaySignedHeaders(args)['X-Gateway-Secret'];
  assert.equal(h['X-Gateway-Secret'], expected);
});

test('internalSignedHeaders: no secret → still sends X-User-ID + provider-id, no signature', () => {
  const h = internalSignedHeaders({ secretKey: '', providerId: 'internal-credit-client', userId: '42' });
  assert.equal(h['X-User-ID'], '42');
  assert.equal(h['X-Provider-ID'], 'internal-credit-client');
  assert.equal(h['X-Gateway-Secret'], undefined);
});

test('v2 binds method, target, body and canonical role context', () => {
  const base = {
    secretKey: 'v2-secret',
    providerId: 'sub',
    userId: '42',
    principalId: 'principal',
    billingSubjectId: 'billing',
    organizationId: 'org',
    organizationRole: 'owner',
    userRoles: 'user,ADMIN',
    installId: 'install',
    method: 'POST',
    requestTarget: '/api/ce-link/register?x=1',
    body: Buffer.from('{"a":1}'),
    timestampMs: 1700000000000,
    nonce: 'nonce-1',
  };
  const signed = gatewaySignedHeadersV2(base);
  assert.equal(signed['X-Gateway-Signature-Version'], '2');
  assert.equal(signed['X-User-Roles'], 'ADMIN,USER');
  assert.equal(signed['X-Organization-Role'], 'OWNER');
  assert.equal(signed['X-Gateway-Body-SHA256'], bodySha256(base.body));

  for (const mutation of [
    { method: 'PUT' },
    { requestTarget: '/api/ce-link/other?x=1' },
    { body: Buffer.from('{"a":2}') },
    { nonce: 'nonce-2' },
    { billingSubjectId: 'other-billing' },
    { userRoles: 'USER' },
  ]) {
    const changed = gatewaySignedHeadersV2({ ...base, ...mutation });
    assert.notEqual(changed['X-Gateway-Secret'], signed['X-Gateway-Secret']);
  }
});

test('v2 canonical payload is byte-stable and empty fields remain positional', () => {
  const payload = gatewayV2CanonicalPayload({
    timestamp: '1', nonce: 'n', method: 'get', requestTarget: '/x',
    bodySha256: 'ABC', providerId: 'p', userId: '', principalId: '',
    billingSubjectId: '', organizationId: '', organizationRole: '',
    userRoles: '', installId: '',
  });
  assert.equal(payload,
    'TRINYX-HMAC-V2\n1\nn\nGET\n/x\nabc\np\n\n\n\n\n\n\n');
});

test('v2 refuses to run without a secret', () => {
  assert.throws(() => gatewaySignedHeadersV2({
    secretKey: '', providerId: 'p', method: 'GET', requestTarget: '/'
  }), /secret is required/);
});

function locateV2Fixture() {
  let here = __dirname;
  for (let i = 0; i < 7; i++) {
    const candidate = resolve(here, 'shared/contracts/gateway-signature-v2-fixtures.json');
    if (existsSync(candidate)) return candidate;
    const parent = dirname(here);
    if (parent === here) break;
    here = parent;
  }
  throw new Error(`gateway-signature-v2-fixtures.json not found from ${__dirname}`);
}

const v2Fixture = JSON.parse(readFileSync(locateV2Fixture(), 'utf8'));

test('cross-language parity: v2 canonical payload matches the shared fixture', () => {
  for (const item of v2Fixture.cases) {
    const actual = gatewayV2CanonicalPayload({
      timestamp: item.timestamp,
      nonce: item.nonce,
      method: item.method,
      requestTarget: item.requestTarget,
      bodySha256: item.bodySha256,
      providerId: item.providerId,
      userId: item.userId,
      principalId: item.principalId,
      billingSubjectId: item.billingSubjectId,
      organizationId: item.organizationId,
      organizationRole: item.organizationRole,
      userRoles: item.userRoles,
      installId: item.installId,
    });
    assert.equal(actual, item.expectedCanonicalPayload, item.name);
    const body = item.method === 'POST' ? Buffer.from('{}') : Buffer.alloc(0);
    assert.equal(bodySha256(body), item.bodySha256, `${item.name} body fixture`);
    assert.match(gatewaySignedHeadersV2({
      secretKey: v2Fixture.secretKey,
      ...item,
      body,
      timestampMs: Number(item.timestamp),
    })['X-Gateway-Secret'], /^gw_[A-Za-z0-9_-]+$/);
  }
});
