/**
 * Legacy compatibility hook for the former documentation subdomain.
 *
 * Documentation is now canonical on the Trinyx public site under `/docs`.
 * Keeping this helper as an explicit no-op prevents old host-specific routing
 * from being reintroduced at call sites.
 */
export type DocsRouteAction =
  | { kind: 'rewrite'; pathname: string }
  | { kind: 'redirect'; url: string }
  | null;

export function resolveDocsRoute(
  _host: string | null | undefined,
  _pathname: string,
): DocsRouteAction {
  return null;
}

/** Build an on-site link to a documentation page. */
export function docsHref(_base: string | undefined, page?: string): string {
  return page ? `/docs/${page}` : '/docs';
}
