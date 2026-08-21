// Localization for the public blog. English is the canonical version served at
// `/blog` (outside the `[locale]` tree, like the rest of the marketing site).
// The other app locales are served at `/<locale>/blog` via `app/[locale]/blog`,
// reusing the same presentational components with a translated UI + content
// bundle. Shared, locale-independent post metadata (date, authors, tags, cover)
// lives in `posts.ts`; only title, excerpt, coverAlt and the body are localized.

export type BlogLocale = 'fr' | 'es' | 'de' | 'pt' | 'zh';

// Every non-English app locale the blog is translated into (en is canonical and
// not part of the `[locale]/blog` static params).
export const BLOG_LOCALES: BlogLocale[] = ['fr', 'es', 'de', 'pt', 'zh'];

/** Chrome/UI strings shown around the posts, per locale. */
export interface BlogUi {
  eyebrow: string;
  blogTitle: string;
  lead: string;
  latest: string;
  readThePost: string;
  readMore: string;
  allPosts: string;
  minRead: string;
  by: string;
  and: string;
  ctaTitle: string;
  ctaText: string;
  startFree: string;
  metaTitle: string;
  metaDescription: string;
}

/** The localized parts of a single post (everything else is shared from posts.ts). */
export interface LocalizedPost {
  title: string;
  excerpt: string;
  coverAlt: string;
  content: string;
}

export interface BlogTranslation {
  ui: BlogUi;
  posts: Record<string, LocalizedPost>;
}

/** English UI strings (canonical). Kept here so the shared views take a uniform `ui` prop. */
export const EN_BLOG_UI: BlogUi = {
  eyebrow: 'Field notes',
  blogTitle: 'Blog',
  lead:
    'Practical guides to AI automation: what agents really cost, what to log, and how to turn a niche dataset into a workflow that runs itself.',
  latest: 'Latest',
  readThePost: 'Read the post',
  readMore: 'Read more',
  allPosts: 'All posts',
  minRead: 'min read',
  by: 'By',
  and: 'and',
  ctaTitle: 'Turn your niche data into a working automation',
  ctaText: 'Describe the job in chat and Trinyx builds the workflow in front of you.',
  startFree: 'Start free',
  metaTitle: 'Blog - Trinyx',
  metaDescription:
    'Practical guides to AI automation and niche data: what AI agents really cost, what to log for every run, and how to turn a dataset into a workflow that runs itself.',
};
