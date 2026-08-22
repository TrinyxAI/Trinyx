import type { LucideIcon } from 'lucide-react';
import { Sparkles, Workflow, Bot, Database, Store, BookOpen } from 'lucide-react';

// Single source of truth for the docs information architecture.
// Consumed by: the sidebar (`DocsNav`), the mobile drawer, the in-page prev/next
// (`DocsPrevNext`), and `app/sitemap.ts`. Add or reorder pages HERE only.
//
// English-only: the whole `/docs` surface lives OUTSIDE `app/[locale]/`, so it has
// no next-intl context - never import `@/i18n/navigation` here or in any consumer.
//
// Every href is canonical on the public Trinyx site under `/docs`. Keeping
// the full route here makes the sidebar, pagination and sitemap agree.

export interface DocsNavItem {
  title: string;
  /** Absolute app path. `undefined` for a roadmap stub (rendered muted, no link). */
  href?: string;
  /** Short label shown muted next to a not-yet-written page. */
  badge?: string;
}

export interface DocsNavSection {
  title: string;
  icon: LucideIcon;
  items: DocsNavItem[];
}

export const DOCS_NAV: DocsNavSection[] = [
  {
    title: 'Get started',
    icon: Sparkles,
    items: [
      { title: 'Overview', href: '/docs' },
      { title: 'Getting started', href: '/docs/getting-started' },
      { title: 'Core concepts', href: '/docs/concepts' },
    ],
  },
  {
    title: 'Build',
    icon: Workflow,
    items: [
      { title: 'Chat', href: '/docs/chat' },
      { title: 'Workflows', href: '/docs/workflows' },
      { title: 'Node reference', href: '/docs/nodes' },
      { title: 'Triggers', href: '/docs/triggers' },
      { title: 'Interfaces & apps', href: '/docs/interfaces' },
      { title: 'Runs & execution', href: '/docs/runs' },
    ],
  },
  {
    title: 'AI',
    icon: Bot,
    items: [
      { title: 'Agents', href: '/docs/agents' },
      { title: 'Models & providers', href: '/docs/models' },
      { title: 'Browser Agent', href: '/docs/browser-agent' },
      { title: 'Skills', href: '/docs/skills' },
    ],
  },
  {
    title: 'Data',
    icon: Database,
    items: [
      { title: 'Tables & data', href: '/docs/tables' },
      { title: 'Integrations', href: '/docs/integrations' },
      { title: 'Files & storage', href: '/docs/files' },
    ],
  },
  {
    title: 'Share & host',
    icon: Store,
    items: [
      { title: 'Marketplace', href: '/docs/marketplace' },
      { title: 'Self-hosting', href: '/docs/self-host' },
      { title: 'Organizations & roles', href: '/docs/organizations' },
      { title: 'Plans & billing', href: '/docs/billing' },
    ],
  },
  {
    title: 'Reference',
    icon: BookOpen,
    items: [
      { title: 'Expressions & variables', href: '/docs/expressions' },
      { title: 'REST API & webhooks', href: '/docs/rest-api' },
    ],
  },
];

/** A page with a real route, plus the section it belongs to. */
export interface DocsPage {
  title: string;
  href: string;
  section: string;
}

/** Flat, ordered list of live docs pages (excludes roadmap stubs). */
export const DOCS_PAGES: DocsPage[] = DOCS_NAV.flatMap((section) =>
  section.items
    .filter((item): item is DocsNavItem & { href: string } => Boolean(item.href))
    .map((item) => ({ title: item.title, href: item.href, section: section.title })),
);

/** Previous / next page in reading order, for the in-page footer nav. */
export function getAdjacentPages(href: string): { prev: DocsPage | null; next: DocsPage | null } {
  const index = DOCS_PAGES.findIndex((page) => page.href === href);
  if (index === -1) return { prev: null, next: null };
  return {
    prev: index > 0 ? DOCS_PAGES[index - 1] : null,
    next: index < DOCS_PAGES.length - 1 ? DOCS_PAGES[index + 1] : null,
  };
}

/**
 * True when `href` is the active page for `pathname` (exact match, or a parent
 * of the current path). `/` (the Overview) matches ONLY exactly, so it does not
 * stay highlighted on every sub-page. Pure helper - unit-tested and reused by the
 * sidebar nav (`DocsNav`).
 */
export function isActiveDocPath(pathname: string | null | undefined, href: string): boolean {
  if (!pathname) return false;
  if (href === '/docs') return pathname === '/docs';
  return pathname === href || pathname.startsWith(href + '/');
}

/** Keep pathname handling explicit and stable for the on-site /docs tree. */
export function cleanDocsPathname(pathname: string | null | undefined): string {
  return pathname || '/docs';
}
