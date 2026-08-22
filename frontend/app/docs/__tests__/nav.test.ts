import { describe, it, expect } from 'vitest';
import { DOCS_NAV, DOCS_PAGES, cleanDocsPathname, getAdjacentPages, isActiveDocPath } from '../_nav';

describe('docs IA - DOCS_NAV / DOCS_PAGES', () => {
  it('derives 22 unique live pages from the six sections', () => {
    expect(DOCS_NAV.map((section) => section.title)).toEqual([
      'Get started',
      'Build',
      'AI',
      'Data',
      'Share & host',
      'Reference',
    ]);
    expect(DOCS_PAGES).toHaveLength(22);
    expect(new Set(DOCS_PAGES.map((page) => page.href)).size).toBe(22);
  });

  it('keeps every live href inside the canonical /docs tree', () => {
    expect(DOCS_PAGES[0].href).toBe('/docs');
    for (const page of DOCS_PAGES) {
      expect(page.href === '/docs' || page.href.startsWith('/docs/')).toBe(true);
    }
    expect(DOCS_PAGES.map((page) => page.href)).toContain('/docs/agents');
    expect(DOCS_PAGES.map((page) => page.href)).toContain('/docs/rest-api');
  });

  it('preserves the nav order and owning section metadata', () => {
    const flattened = DOCS_NAV.flatMap((section) =>
      section.items.filter((item) => item.href).map((item) => item.href),
    );
    expect(DOCS_PAGES.map((page) => page.href)).toEqual(flattened);
    expect(DOCS_PAGES.find((page) => page.href === '/docs/workflows')?.section).toBe('Build');
  });
});

describe('getAdjacentPages', () => {
  it('returns neighbours in reading order', () => {
    const first = getAdjacentPages('/docs');
    expect(first.prev).toBeNull();
    expect(first.next?.href).toBe(DOCS_PAGES[1].href);

    const middle = DOCS_PAGES[2];
    expect(getAdjacentPages(middle.href)).toEqual({
      prev: DOCS_PAGES[1],
      next: DOCS_PAGES[3],
    });

    const last = DOCS_PAGES[DOCS_PAGES.length - 1];
    expect(getAdjacentPages(last.href).next).toBeNull();
  });

  it('returns nulls for an unknown path', () => {
    expect(getAdjacentPages('/docs/does-not-exist')).toEqual({ prev: null, next: null });
  });
});

describe('isActiveDocPath', () => {
  it('matches the overview only exactly', () => {
    expect(isActiveDocPath('/docs', '/docs')).toBe(true);
    expect(isActiveDocPath('/docs/agents', '/docs')).toBe(false);
  });

  it('matches a page exactly and as a parent of deeper paths', () => {
    expect(isActiveDocPath('/docs/agents', '/docs/agents')).toBe(true);
    expect(isActiveDocPath('/docs/agents/budgets', '/docs/agents')).toBe(true);
    expect(isActiveDocPath('/docs/agents', '/docs/agent')).toBe(false);
  });
});

describe('cleanDocsPathname', () => {
  it('keeps canonical docs paths unchanged and defaults missing paths to /docs', () => {
    expect(cleanDocsPathname('/docs')).toBe('/docs');
    expect(cleanDocsPathname('/docs/agents')).toBe('/docs/agents');
    expect(cleanDocsPathname(null)).toBe('/docs');
    expect(cleanDocsPathname(undefined)).toBe('/docs');
  });
});
