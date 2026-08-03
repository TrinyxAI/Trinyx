import { locales } from '@/i18n/routing';

/**
 * The workflow page's own routes: which workflow a pathname is about, and where
 * a given run of it lives.
 *
 * A path is either `/app/workflow/...` or `/<locale>/app/workflow/...` (the
 * default locale carries no prefix at all, next-intl `localePrefix:
 * 'as-needed'`), and nothing else may precede it. The accepted prefixes come
 * from the routing config, so adding a locale never silently stops the URL from
 * following the run.
 */
const LOCALE_SEGMENTS: ReadonlySet<string> = new Set(locales.map(l => `/${l}`));

/**
 * The address of a run on the workflow page.
 *
 * The URL is the only part of "which run am I looking at" that survives a
 * reload, so binding a run in place has to write it too. Built from the CURRENT
 * pathname rather than from a template, so the locale segment is preserved.
 *
 * Returns null when the pathname is not this workflow's page: there is then no
 * URL to keep in sync, and rewriting an unrelated one would be a navigation.
 */
export function runRoutePathFor(
  pathname: string | null | undefined,
  workflowId: string | null | undefined,
  runId: string | null | undefined,
): string | null {
  if (!pathname || !workflowId || !runId) return null;

  const base = `/app/workflow/${workflowId}`;
  const at = pathname.indexOf(base);
  if (at < 0) return null;

  // Only a bare locale segment may precede the base ('' or '/xx'), never a
  // deeper path that merely contains it.
  const prefix = pathname.slice(0, at);
  if (prefix && !LOCALE_SEGMENTS.has(prefix)) return null;

  // The tail must be the page itself or a run route. Anything under `/run/`
  // describes the run being left, so it is replaced along with it; any other
  // tail is a page this has no business rewriting.
  const tail = pathname.slice(at + base.length);
  if (tail !== '' && tail !== '/' && !tail.startsWith('/run/')) return null;

  return `${prefix}${base}/run/${runId}`;
}

/**
 * The workflow a pathname is about, or null when it is not a workflow page.
 *
 * A WorkflowModeProvider adopts the run named by the URL, and several providers
 * are mounted at once (the page, a sub-workflow tab, an application tab). Only
 * the one whose workflow the URL actually names may adopt it, or a run pick on
 * the page injects that run into a tab showing a different workflow.
 */
export function workflowIdFromPathname(pathname: string | null | undefined): string | null {
  if (!pathname) return null;
  const base = '/app/workflow/';
  const at = pathname.indexOf(base);
  if (at < 0) return null;

  const prefix = pathname.slice(0, at);
  if (prefix && !LOCALE_SEGMENTS.has(prefix)) return null;

  const id = pathname.slice(at + base.length).split('/')[0];
  return id || null;
}
