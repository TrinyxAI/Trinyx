import type { Metadata } from 'next';

const SITE_URL = (process.env.NEXT_PUBLIC_SITE_URL ?? 'https://trinyx.fr').replace(/\/$/, '');
const DOCS_URL = `${SITE_URL}/docs`;

/** Per-page metadata helper. `path` is the route under the on-site docs tree. */
export function docsMetadata(opts: { title: string; description: string; path: string }): Metadata {
  const suffix = opts.path === '/docs' ? '' : opts.path.replace(/^\/docs/, '');
  const url = `${DOCS_URL}${suffix}`;
  return {
    title: opts.title,
    description: opts.description,
    alternates: { canonical: url },
    openGraph: {
      title: `${opts.title} · Trinyx Docs`,
      description: opts.description,
      url,
      type: 'article',
    },
  };
}
