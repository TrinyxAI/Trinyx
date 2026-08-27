import type { FolderResourceKind } from '@/lib/api/orchestrator/resource-folder.service';

/**
 * Window-event channel that lets a resource LIST tell the app header which folder path it is
 * showing, so the header breadcrumb reads Home / Workflows / Marketing / Q4 - the same
 * CustomEvent pattern the Files browser already uses ({@code filesHeaderBus}).
 *
 * <p>Only the NAMES travel here. Which folder is open lives in the URL (`?folder=<id>`), so
 * it survives a reload, a shared link and the back button - the header reads it from there
 * and navigates by changing it. The list is simply the only place that already knows what
 * those folder ids are called, and sending the trail costs it nothing.
 */

/** One hop of the folder path: the id the URL carries, and the name the header prints. */
export interface ResourceFolderCrumb {
  id: string;
  name: string;
}

export interface ResourceFolderTrailState {
  /** Which list is speaking, so the header ignores a trail left by another one. */
  view: FolderResourceKind;
  /** Root -> ... -> current folder. Empty at the top level. */
  trail: ResourceFolderCrumb[];
}

/** list → header: the folder path being shown changed. */
export const RESOURCE_FOLDER_TRAIL_CHANGED = 'resourceFolderTrailChanged';

/** List: broadcast the folder path it is currently showing. */
export function emitResourceFolderTrail(state: ResourceFolderTrailState): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(
    new CustomEvent<ResourceFolderTrailState>(RESOURCE_FOLDER_TRAIL_CHANGED, { detail: state }),
  );
}

/** Header: subscribe to folder-path changes. Returns an unsubscribe fn. */
export function onResourceFolderTrail(
  handler: (state: ResourceFolderTrailState) => void,
): () => void {
  if (typeof window === 'undefined') return () => {};
  const listener = (e: Event) => handler((e as CustomEvent<ResourceFolderTrailState>).detail);
  window.addEventListener(RESOURCE_FOLDER_TRAIL_CHANGED, listener);
  return () => window.removeEventListener(RESOURCE_FOLDER_TRAIL_CHANGED, listener);
}

/** The URL parameter every list uses to say which folder it is showing. */
export const FOLDER_QUERY_PARAM = 'folder';

/**
 * The URL for a level of a list: the page's own path plus `?folder=<id>`, or the path alone
 * at the top level. Every other parameter the page carries (a tab, a search) is preserved -
 * navigating into a folder must not silently drop the rest of the view.
 */
export function folderUrl(
  pathname: string,
  searchParams: URLSearchParams | ReadonlyURLSearchParamsLike,
  folderId: string | null,
): string {
  const params = new URLSearchParams(searchParams.toString());
  if (folderId) params.set(FOLDER_QUERY_PARAM, folderId);
  else params.delete(FOLDER_QUERY_PARAM);
  const query = params.toString();
  return query ? `${pathname}?${query}` : pathname;
}

/** Next's ReadonlyURLSearchParams, structurally - avoids importing it just for a type. */
interface ReadonlyURLSearchParamsLike {
  toString(): string;
}
