/**
 * Change the address of the page ALREADY on screen: same pathname, different query.
 *
 * <p>It goes through the history API rather than the router on purpose. Next drops a
 * `router.push` of a pathname the page was loaded at when the push only REMOVES query
 * parameters - which is exactly what "go back to the plain list" asks for. From a page opened
 * directly on `/app/workflow?folder=X` or `/app/agent?view=fleet` (a shared link, a reload, the
 * browser restoring a tab) the click then does nothing at all, and the page looks stuck in a
 * folder or a tab the user has already asked to leave.
 *
 * <p>Next synchronises `pushState`/`replaceState` with `usePathname` and `useSearchParams`, so
 * the page still re-reads the address, the address still carries the state, and Back still
 * walks back out. This is the mechanism Next documents for exactly this case.
 *
 * <p><b>Only for the page already on screen.</b> Next answers a `pushState` by re-rendering the
 * tree it already has, so naming a DIFFERENT pathname would leave the current page under
 * someone else's address with no error at all. To reach another page, route.
 *
 * @param url the address to show, of the page already rendered
 * @param currentUrl the address it is showing now, so an entry that changes nothing is not
 *   stacked onto the history (which would cost an extra Back to get past). Derived from the
 *   caller's own inputs rather than read from `window`, so the answer cannot depend on the two
 *   having caught up with one another.
 * @param mode `replace` corrects an address that was never valid; `push` records a step the
 *   user chose and can come back from
 */
export function showSamePageUrl(
  url: string,
  currentUrl: string,
  mode: 'push' | 'replace' = 'push',
): void {
  if (typeof window === 'undefined') return;
  if (url === currentUrl) return;
  // The page is not changing, so neither is the anchor within it: a caller builds its url from
  // a pathname and a query, and dropping the hash would silently jump the reader to the top.
  const withHash = url.includes('#') ? url : `${url}${window.location.hash}`;
  if (mode === 'replace') window.history.replaceState(null, '', withHash);
  else window.history.pushState(null, '', withHash);
}

/** The address a page is showing, from the pieces a component already holds. */
export function samePageUrl(
  pathname: string,
  searchParams: { toString(): string },
): string {
  const query = searchParams.toString();
  return query ? `${pathname}?${query}` : pathname;
}
