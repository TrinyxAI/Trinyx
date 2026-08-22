import { describe, it, expect } from 'vitest';
import { resolveDocsRoute, docsHref } from '../docsHostRewrite';

describe('resolveDocsRoute', () => {
  it('does not redirect or rewrite documentation requests to a subdomain', () => {
    expect(resolveDocsRoute('trinyx.fr', '/docs')).toBeNull();
    expect(resolveDocsRoute('trinyx.fr', '/docs/agents')).toBeNull();
    expect(resolveDocsRoute('docs.livecontext.ai', '/docs')).toBeNull();
    expect(resolveDocsRoute('docs.livecontext.ai', '/agents')).toBeNull();
  });
});

describe('docsHref', () => {
  it('always links to the on-site /docs tree', () => {
    expect(docsHref(undefined)).toBe('/docs');
    expect(docsHref(undefined, 'workflows')).toBe('/docs/workflows');
    expect(docsHref('https://trinyx.fr')).toBe('/docs');
    expect(docsHref('https://trinyx.fr', 'agents')).toBe('/docs/agents');
  });
});
