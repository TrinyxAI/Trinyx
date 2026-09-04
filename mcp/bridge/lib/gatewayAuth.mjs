import { createHash, createHmac, randomUUID } from 'node:crypto';

const SIGNATURE_PREFIX = 'gw_';

export function gatewaySignedHeaders({ secretKey, providerId, userId = '', organizationId = '', timestampMs } = {}) {
  const safeProvider = providerId == null ? '' : String(providerId);
  if (!secretKey) return { 'X-Provider-ID': safeProvider };
  const timestamp = String(timestampMs == null ? Date.now() : timestampMs);
  const safeUser = userId == null ? '' : String(userId);
  const safeOrg = organizationId == null ? '' : String(organizationId);
  const data = `${safeProvider}|${safeUser}|${safeOrg}|${timestamp}`;
  return {
    'X-Provider-ID': safeProvider,
    'X-Gateway-Timestamp': timestamp,
    'X-Gateway-Secret': SIGNATURE_PREFIX
      + createHmac('sha256', secretKey).update(data, 'utf8').digest('base64url'),
  };
}

export function internalSignedHeaders({ secretKey, providerId, userId = '', organizationId = '', timestampMs, extra = {} } = {}) {
  const safeOrg = organizationId == null ? '' : String(organizationId);
  const headers = {
    ...extra,
    'X-User-ID': userId == null ? '' : String(userId),
    ...gatewaySignedHeaders({ secretKey, providerId, userId, organizationId: safeOrg, timestampMs }),
  };
  if (safeOrg) headers['X-Organization-ID'] = safeOrg;
  return headers;
}

export function canonicalRoles(value = '') {
  return [...new Set(String(value).split(',')
    .map(role => role.trim().toUpperCase())
    .filter(Boolean))]
    .sort()
    .join(',');
}

export function bodySha256(body = Buffer.alloc(0)) {
  const bytes = Buffer.isBuffer(body) ? body : Buffer.from(body);
  return createHash('sha256').update(bytes).digest('hex');
}

export function gatewayV2CanonicalPayload(context) {
  const value = key => context[key] == null ? '' : String(context[key]);
  return [
    'TRINYX-HMAC-V2',
    value('timestamp'),
    value('nonce'),
    value('method').toUpperCase(),
    value('requestTarget'),
    value('bodySha256').toLowerCase(),
    value('providerId'),
    value('userId'),
    value('principalId'),
    value('billingSubjectId'),
    value('organizationId'),
    canonicalRoles(value('organizationRole')),
    canonicalRoles(value('userRoles')),
    value('installId'),
  ].join('\n');
}

/**
 * Gateway HMAC v2 signer. requestTarget MUST be the exact downstream path
 * (including the raw query string) after gateway route rewriting.
 */
export function gatewaySignedHeadersV2({
  secretKey,
  providerId,
  userId = '',
  principalId = '',
  billingSubjectId = '',
  organizationId = '',
  organizationRole = '',
  userRoles = '',
  installId = '',
  method,
  requestTarget,
  body = Buffer.alloc(0),
  timestampMs = Date.now(),
  nonce = randomUUID(),
  extra = {},
} = {}) {
  if (!secretKey) throw new Error('gateway HMAC v2 secret is required');
  if (!providerId || !method || !requestTarget) {
    throw new Error('providerId, method and requestTarget are required');
  }

  const hash = bodySha256(body);
  const context = {
    timestamp: String(timestampMs),
    nonce: String(nonce),
    method,
    requestTarget,
    bodySha256: hash,
    providerId: String(providerId),
    userId: String(userId ?? ''),
    principalId: String(principalId ?? ''),
    billingSubjectId: String(billingSubjectId ?? ''),
    organizationId: String(organizationId ?? ''),
    organizationRole: canonicalRoles(organizationRole),
    userRoles: canonicalRoles(userRoles),
    installId: String(installId ?? ''),
  };
  const signature = SIGNATURE_PREFIX
    + createHmac('sha256', secretKey)
      .update(gatewayV2CanonicalPayload(context), 'utf8')
      .digest('base64url');

  const headers = {
    ...extra,
    'X-Gateway-Signature-Version': '2',
    'X-Gateway-Timestamp': context.timestamp,
    'X-Gateway-Nonce': context.nonce,
    'X-Gateway-Body-SHA256': hash,
    'X-Gateway-Secret': signature,
    'X-Provider-ID': context.providerId,
    'X-User-ID': context.userId,
    'X-Principal-ID': context.principalId,
    'X-Billing-Subject-ID': context.billingSubjectId,
    'X-Organization-ID': context.organizationId,
    'X-Organization-Role': context.organizationRole,
    'X-User-Roles': context.userRoles,
    'X-Install-ID': context.installId,
  };
  return Object.fromEntries(Object.entries(headers).filter(([, value]) => value !== ''));
}
