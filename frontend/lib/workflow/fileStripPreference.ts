/**
 * Whether run-mode file previews open by themselves.
 *
 * The canvas toolbar's expand/collapse control used to be a one-shot bulk action on
 * whatever was on screen: it reverted on the next run, the next epoch, and the next
 * page load. This turns it into a REMEMBERED choice, read once by
 * {@code FileStripExpansionProvider} and used as the default a strip falls back to
 * when it has no opinion of its own - including on runs the user has not opened yet.
 *
 * <p>Scope is the BROWSER, not the workspace: it describes how this person likes to
 * read a canvas, which does not change when they switch org. That differs from
 * {@code lc.workflow.layoutDirection}, which is per-workspace because the reading
 * direction belongs to the workflows themselves. Key style follows the other
 * builder-canvas preferences ({@code workflow:cursorMode}, {@code workflow:connectionType}).
 *
 * <p>Deliberately NOT read through {@code usePersistentState}: that hook reads storage
 * in the useState initializer, and the builder canvas is a client component the server
 * still renders on first load, so a stored `true` against a server-rendered `false` is
 * a hydration mismatch. The provider reads this AFTER mount instead, which costs one
 * frame of folded pills for a user whose preference is "expanded" and nothing at all
 * for the default.
 *
 * <p>A surface that mounts a strip OUTSIDE that provider (the marketplace preview,
 * isolated tests) keeps its own local state and does not consult this at all.
 */

export const FILE_STRIP_EXPANDED_STORAGE_KEY = 'workflow:fileStripExpanded';

/**
 * The stored preference, or null when nothing valid is stored.
 *
 * @param storage injection point for the failure modes a test cannot otherwise reach:
 *                a store that THROWS on access (Safari private mode, full quota), or
 *                an explicit {@code null} for "no storage at all", which is what a
 *                server render sees. Omitting it uses the browser's, and omitting it
 *                is not the same as passing null.
 */
export function readFileStripPreference(storage?: Storage | null): boolean | null {
  const store = storage === undefined
    ? (typeof window !== 'undefined' ? window.localStorage : null)
    : storage;
  if (!store) return null;
  try {
    const raw = store.getItem(FILE_STRIP_EXPANDED_STORAGE_KEY);
    // Only the two values this ever writes count. Anything else (a stale format, a
    // key collision, a user poking at devtools) falls back to the default rather
    // than being coerced - `Boolean('false')` is true, which is how this kind of
    // helper usually ships the wrong behaviour.
    if (raw === 'true') return true;
    if (raw === 'false') return false;
    return null;
  } catch {
    // Safari private mode and friends throw on access.
    return null;
  }
}

/** Records the choice. See {@link readFileStripPreference} for the `storage` contract. */
export function writeFileStripPreference(expanded: boolean, storage?: Storage | null): void {
  const store = storage === undefined
    ? (typeof window !== 'undefined' ? window.localStorage : null)
    : storage;
  if (!store) return;
  try {
    store.setItem(FILE_STRIP_EXPANDED_STORAGE_KEY, expanded ? 'true' : 'false');
  } catch {
    // Quota or private mode: the preference simply does not survive the session.
  }
}
