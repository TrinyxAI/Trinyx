import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * `GET /api/pricing-event` is a Next route handler, not a backend endpoint.
 *
 * The cloud ingress routes `/api` to the GATEWAY and carves out only the specific
 * prefixes that belong to the frontend. Without an explicit carve-out the gateway
 * answers 404 for this path, `usePricingEvent` degrades to "no window open", and every
 * price surface silently renders the plain price. Nothing errors, nothing logs: the
 * feature is simply absent in production while being green everywhere else.
 *
 * That is exactly what happened on 2026-08-25. It was invisible to the CE e2e suite
 * because the CE monolith serves Next directly, so the only topology under test was the
 * one where it works. This test covers the topology that is NOT exercised end to end.
 */

const VALUES_FILES = [
  'values.yaml',
  'values-prod.yaml',
  'values-preprod.yaml',
  'values-staging-cx33.yaml',
  'values-staging.example.yaml',
];

function readValues(file: string): string {
  return readFileSync(join(process.cwd(), '..', 'deploy', 'helm', 'livecontext', file), 'utf8');
}

/** The `service:` a path block declares, or null when the path is absent. */
function serviceForPath(yaml: string, path: string): string | null {
  const lines = yaml.split(/\r?\n/);
  const start = lines.findIndex((l) => l.trim() === `- path: ${path}`);
  if (start === -1) return null;
  for (const line of lines.slice(start + 1, start + 4)) {
    const match = line.match(/^\s*service:\s*(\S+)/);
    if (match) return match[1];
    if (line.trim().startsWith('- path:')) break;
  }
  return null;
}

describe('pricing-event ingress routing', () => {
  it.each(VALUES_FILES)('%s routes /api/pricing-event to the frontend', (file) => {
    expect(serviceForPath(readValues(file), '/api/pricing-event')).toBe('frontend');
  });

  it.each(VALUES_FILES)('%s still sends the rest of /api to the gateway', (file) => {
    // Pins the reason the carve-out is needed. If this ever stops being true the
    // carve-out is redundant rather than wrong, but the change deserves a second look.
    expect(serviceForPath(readValues(file), '/api')).toBe('gateway');
  });

  it('carves out a longer prefix than the catch-all it escapes', () => {
    // ingress-nginx resolves Prefix rules longest-match-first, so the carve-out only
    // wins because it is strictly longer than "/api". A shorter or equal path would be
    // shadowed and the 404 would come straight back.
    expect('/api/pricing-event'.length).toBeGreaterThan('/api'.length);
    expect('/api/pricing-event'.startsWith('/api')).toBe(true);
  });
});
