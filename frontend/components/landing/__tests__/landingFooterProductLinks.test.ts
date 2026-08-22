import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { DOCS_PAGES } from '@/app/docs/_nav';

// The footer's Product column lists what the product does and sends each entry to
// the docs page that explains it. Two things can silently break that: a docs page
// renamed or removed under `app/docs/`, and a stray hard-coded `/docs/...` href
// that must keep resolving under the canonical on-site /docs tree. Both are checked here.
//
// Source-level, like landingFooterSocialLinks.test.ts: the footer renders on public
// pages that have no intl context, so it is not mounted in a test.
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

/** Just the Product column, so a match cannot come from Resources or Compare. */
const productColumn = (() => {
  const start = shellSrc.indexOf('>Product</p>');
  expect(start).toBeGreaterThan(-1);
  const end = shellSrc.indexOf('</ul>', start);
  expect(end).toBeGreaterThan(start);
  return shellSrc.slice(start, end);
})();

describe('landing footer Product column', () => {
  const docsEntries = [
    { page: 'workflows', label: 'Workflows' },
    { page: 'agents', label: 'Agents' },
    { page: 'interfaces', label: 'Interfaces &amp; apps' },
    { page: 'tables', label: 'Tables &amp; data' },
    { page: 'integrations', label: 'Integrations' },
  ];

  for (const { page, label } of docsEntries) {
    it(`links "${label}" to the ${page} docs page`, () => {
      expect(productColumn).toContain(`docsHref(siteBaseUrl, '${page}')`);
      expect(productColumn).toContain(`>${label}</Link>`);
    });

    it(`the ${page} docs page it links to exists in the docs nav`, () => {
      expect(DOCS_PAGES.map((p) => p.href)).toContain(`/docs/${page}`);
    });
  }

  it('keeps the two sign-in entries it already had', () => {
    expect(productColumn).toContain('returnTo="/app/marketplace"');
    expect(productColumn).toContain('returnTo="/app/settings/pricing"');
  });

  it('never hard-codes a docs path, so the links work on the docs host too', () => {
    // Every shared-chrome docs link goes through the canonical helper.
    expect(productColumn).not.toMatch(/href="\/docs\//);
    expect(productColumn).not.toMatch(/href="\/(workflows|agents|interfaces|tables|integrations)"/);
  });

  it('routes the header and Resources docs links through the same helper', () => {
    // The old inline `siteBaseUrl ? '/' : '/docs'` ternaries are gone, so there is
    // one definition of where the docs live.
    expect(shellSrc).not.toContain("siteBaseUrl ? '/' : '/docs'");
    expect(shellSrc.match(/docsHref\(siteBaseUrl\)/g) ?? []).toHaveLength(2);
  });
});
