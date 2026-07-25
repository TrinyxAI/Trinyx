// Structural guarantees for the public blog bodies.
//
// The posts were rewritten (2026-07-24) from long-form essays into scannable
// public articles: a "short version" list up top, tables instead of paragraphs
// of comparison, at least one product screenshot where one exists, and an FAQ
// block for search. These tests pin that shape, and pin the translations to the
// SAME shape, because a locale that quietly loses a table or an image is the
// failure mode nobody notices until a reader reports it.

import { describe, it, expect } from 'vitest';
import { getAllPosts } from '../posts';
import { getLocalizedPosts } from '../localized';
import { BLOG_LOCALES } from '../i18n';

const EN_POSTS = getAllPosts();
const SLUGS = new Set(EN_POSTS.map((p) => p.slug));

/** Every markdown table has exactly one separator row, so this counts tables. */
const countTables = (body: string) => (body.match(/^\|-/gm) ?? []).length;
const countH2 = (body: string) => (body.match(/^## /gm) ?? []).length;
const countH3 = (body: string) => (body.match(/^### /gm) ?? []).length;
const imagePaths = (body: string) =>
  [...body.matchAll(/^!\[[^\]]*\]\(([^)]+)\)/gm)].map((m) => m[1]);
/** Internal blog links, e.g. `/blog/slug` or `/fr/blog/slug`. */
const blogLinks = (body: string) =>
  [...body.matchAll(/\]\((\/(?:[a-z]{2}\/)?blog\/[a-z0-9-]+)\)/g)].map((m) => m[1]);

describe('English post structure', () => {
  it.each(EN_POSTS.map((p) => [p.slug, p] as const))('%s is scannable', (_slug, post) => {
    // Opens with the direct answer, then a short bulleted summary.
    expect(post.content).toMatch(/^## The short version$/m);
    expect(post.content.indexOf('## The short version')).toBeLessThan(700);
    // Comparisons are tables, not paragraphs.
    expect(countTables(post.content)).toBeGreaterThanOrEqual(2);
    // Sectioned for scanning, and closed by an FAQ block for long-tail search.
    expect(countH2(post.content)).toBeGreaterThanOrEqual(6);
    expect(countH3(post.content)).toBeGreaterThanOrEqual(3);
    expect(post.content).toMatch(/^## Questions people ask$/m);
    expect(post.content).toMatch(/^## What to do next$/m);
  });

  it('links only to slugs that exist, without a locale prefix', () => {
    for (const post of EN_POSTS) {
      for (const link of blogLinks(post.content)) {
        expect(link).toMatch(/^\/blog\//);
        expect(SLUGS.has(link.replace('/blog/', ''))).toBe(true);
      }
    }
  });

  it('points every screenshot at a public asset path', () => {
    for (const post of EN_POSTS) {
      for (const src of imagePaths(post.content)) {
        expect(src).toMatch(/^\/(blog|landing)\/.+\.(png|jpg|webp)$/);
      }
    }
  });

  it('gives every screenshot descriptive alt text and a caption', () => {
    for (const post of EN_POSTS) {
      const images = [...post.content.matchAll(/^!\[([^\]]*)\]\([^)]+\)\n\n\*([^*]+)\*$/gm)];
      expect(images).toHaveLength(imagePaths(post.content).length);
      for (const [, alt] of images) expect(alt.length).toBeGreaterThan(40);
    }
  });
});

describe('translation parity', () => {
  it.each(BLOG_LOCALES)('%s keeps the English structure', (locale) => {
    const localized = getLocalizedPosts(locale);
    expect(localized).toHaveLength(EN_POSTS.length);

    for (let i = 0; i < EN_POSTS.length; i++) {
      const en = EN_POSTS[i];
      const tr = localized[i];
      const where = `${locale}/${en.slug}`;

      expect(`${where}: ${countH2(tr.content)}`).toBe(`${where}: ${countH2(en.content)}`);
      expect(`${where}: ${countH3(tr.content)}`).toBe(`${where}: ${countH3(en.content)}`);
      expect(`${where}: ${countTables(tr.content)}`).toBe(`${where}: ${countTables(en.content)}`);
      // Same screenshots, in the same order: a translation must not drop one.
      expect(imagePaths(tr.content)).toEqual(imagePaths(en.content));
      // Alt text and captions are translated, not copied from English.
      for (const [, alt] of tr.content.matchAll(/^!\[([^\]]*)\]/gm)) {
        expect(en.content).not.toContain(`![${alt}]`);
      }
    }
  });

  it.each(BLOG_LOCALES)('%s links stay inside the locale', (locale) => {
    for (const post of getLocalizedPosts(locale)) {
      const links = blogLinks(post.content);
      expect(links.length).toBeGreaterThan(0);
      for (const link of links) {
        expect(link.startsWith(`/${locale}/blog/`)).toBe(true);
        expect(SLUGS.has(link.replace(`/${locale}/blog/`, ''))).toBe(true);
      }
    }
  });
});

describe('house style', () => {
  const surfaces = [
    ...EN_POSTS.flatMap((p) => [p.title, p.excerpt, p.content]),
    ...BLOG_LOCALES.flatMap((l) =>
      getLocalizedPosts(l).flatMap((p) => [p.title, p.excerpt, p.coverAlt, p.content]),
    ),
  ];

  it('never uses an em dash or en dash', () => {
    for (const text of surfaces) {
      expect(text).not.toMatch(/[--]/);
    }
  });

  it('keeps excerpts short enough to serve as a meta description', () => {
    for (const post of EN_POSTS) {
      expect(post.excerpt.length).toBeLessThanOrEqual(175);
      expect(post.excerpt.length).toBeGreaterThanOrEqual(80);
    }
    for (const locale of BLOG_LOCALES) {
      for (const post of getLocalizedPosts(locale)) {
        expect(`${locale}/${post.slug}`.length && post.excerpt.length).toBeLessThanOrEqual(200);
      }
    }
  });
});
