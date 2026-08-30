/**
 * Pure helpers for what the Files browser REMEMBERS between visits, and for what it
 * writes into the URL.
 *
 * <p>The split is deliberate and is the rule to keep:</p>
 * - **Preferences** (grid vs list, the sort criterion) live in localStorage. They describe how
 *   the user likes to look at files, not what they are looking at, so they should follow the
 *   user to every folder and every session - not ride in a link they might share.
 * - **Navigation** (which folder is open) lives in the URL. It describes what is on screen, so a
 *   refresh, a Back, or a pasted link must land on the same folder instead of snapping to the root.
 *
 * Every value read back from storage or the URL is re-validated here: a hand-edited URL or a
 * preference written by an older build must degrade to the default, never render a broken view.
 */
import type { ExplorerSortKey, ExplorerSortDirection } from '@/lib/api/storage-api';

/** Grid of tiles, or a dense table. */
export type FilesViewMode = 'grid' | 'list';

/** localStorage key for the grid/list choice. */
export const FILES_VIEW_MODE_STORAGE_KEY = 'files.viewMode';

/** localStorage key for the sort criterion + direction. */
export const FILES_SORT_STORAGE_KEY = 'files.sort';

/**
 * The file PICKER's density, kept apart from {@link FILES_VIEW_MODE_STORAGE_KEY} on purpose:
 * choosing a dense picker inside a modal should not also flatten the full-page browser.
 */
export const FILES_PICKER_VIEW_MODE_STORAGE_KEY = 'files.pickerViewMode';

/** URL query param carrying the open folder (absent = root). */
export const FILES_FOLDER_PARAM = 'folder';

/** How the listing is ordered. */
export interface FilesSortPreference {
  key: ExplorerSortKey;
  direction: ExplorerSortDirection;
}

/** Newest first - what every Files surface showed before sorting was configurable. */
export const DEFAULT_SORT_PREFERENCE: FilesSortPreference = { key: 'date', direction: 'desc' };

const SORT_KEYS: readonly ExplorerSortKey[] = ['date', 'name', 'size', 'type'];

/**
 * The direction a criterion reads naturally in when the user picks it: A→Z for the text
 * criteria, newest / biggest first for date and size. Matches the backend's fallback, so
 * choosing a criterion without touching the direction toggle gives the same order on both sides.
 */
export function naturalDirectionFor(key: ExplorerSortKey): ExplorerSortDirection {
  return key === 'name' || key === 'type' ? 'asc' : 'desc';
}

/** A stored/URL view mode, or 'grid' for anything unrecognised. */
export function normalizeViewMode(value: unknown): FilesViewMode {
  return value === 'list' ? 'list' : 'grid';
}

/**
 * A stored sort preference, defaulting field by field. A value written by an older build (or a
 * criterion since removed) yields the default rather than an unsortable listing; a known key with
 * a missing direction falls back to that key's natural direction, not to 'desc'.
 */
export function normalizeSortPreference(value: unknown): FilesSortPreference {
  if (!value || typeof value !== 'object') return DEFAULT_SORT_PREFERENCE;
  const raw = value as Partial<FilesSortPreference>;
  const key = SORT_KEYS.includes(raw.key as ExplorerSortKey) ? (raw.key as ExplorerSortKey) : DEFAULT_SORT_PREFERENCE.key;
  const direction = raw.direction === 'asc' || raw.direction === 'desc' ? raw.direction : naturalDirectionFor(key);
  return { key, direction };
}

/**
 * The query string for a folder navigation: the current params with `folder` set (or dropped, at
 * the root). Every OTHER param is preserved, so navigating a folder never silently discards a
 * filter or a flag another feature put in the URL.
 *
 * @param current the page's current query params (read-only - a new string is returned)
 * @param folderId the folder to open, or null for the root
 * @returns a query string WITHOUT the leading '?' (empty when no params remain)
 */
export function folderQueryString(current: URLSearchParams, folderId: string | null): string {
  const next = new URLSearchParams(current);
  if (folderId) next.set(FILES_FOLDER_PARAM, folderId);
  else next.delete(FILES_FOLDER_PARAM);
  return next.toString();
}

/**
 * True when a folder navigation would land exactly where we already are - the guard against a
 * second click on a folder card pushing a duplicate history entry (which is what turns a
 * breadcrumb into "Run 12 / Run 12 / Run 12" and takes three Backs to leave).
 */
export function isSameFolder(a: string | null | undefined, b: string | null | undefined): boolean {
  return (a ?? null) === (b ?? null);
}
