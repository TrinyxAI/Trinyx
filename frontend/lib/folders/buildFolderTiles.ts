import type { ResourceFolder, ResourceFolderTile } from '@/lib/api/orchestrator/resource-folder.service';

/** The few fields a tile needs from whatever the list holds. */
export interface FolderTileSource {
  id: string;
  name?: string;
  /** Avatar / cover, for the lists whose tiles show images. */
  imageUrl?: string;
  /** ISO timestamp of the last change, or null when unknown. */
  lastModifiedAt?: string | null;
  /** ISO timestamp of the last use (a run), or null when it never ran. */
  lastActivityAt?: string | null;
}

/** How the tiles are ordered - mirrors the server-side ResourceFolderOrdering. */
export type FolderTileSort = 'name' | 'lastModified' | 'lastActivity';

/** Items drawn inside one tile: the face is a 3x2 grid, so six of them fill it. */
const PREVIEW_SIZE = 6;

/**
 * Build the folder tiles of ONE level, client-side.
 *
 * <p>Four of the five list pages get their tiles from the server, because the server holds
 * the page they list. The applications page is the exception: it merges its own published
 * apps with the ones it acquired and enriches them before showing anything, so the whole set
 * only exists here - and a tile computed from anything less would lie about what a folder
 * holds. Same rules as the server all the same: counts and dates cover the folder's WHOLE
 * subtree, and an empty folder carries no date so the ordering sinks it.
 */
export function buildFolderTiles(options: {
  /** Every folder of the workspace, flat. */
  folders: ResourceFolder[];
  /** The level to build ({@code null} = the top level). */
  parentFolderId: string | null;
  /** Every item the user can see. */
  items: FolderTileSource[];
  /** item id -> folder id. An item filed nowhere is simply absent. */
  memberships: Map<string, string>;
  sort: FolderTileSort;
}): ResourceFolderTile[] {
  const { folders, parentFolderId, items, memberships, sort } = options;

  const children = folders.filter((f) => (f.parentFolderId ?? null) === parentFolderId);
  if (children.length === 0) return [];

  const byFolder = new Map<string, FolderTileSource[]>();
  for (const item of items) {
    const folderId = memberships.get(item.id);
    if (!folderId) continue;
    const bucket = byFolder.get(folderId);
    if (bucket) bucket.push(item);
    else byFolder.set(folderId, [item]);
  }

  const tiles = children.map((folder) => {
    const subtree = subtreeIds(folders, folder.id);
    const contained: FolderTileSource[] = [];
    for (const folderId of subtree) contained.push(...(byFolder.get(folderId) ?? []));

    const lastModifiedAt = newest(contained.map((i) => i.lastModifiedAt));
    const lastActivityAt = newest(contained.map((i) => i.lastActivityAt));
    const preview = [...contained]
      .sort((a, b) => time(b.lastModifiedAt) - time(a.lastModifiedAt))
      .slice(0, PREVIEW_SIZE)
      .map((item) => ({ id: item.id, name: item.name, imageUrl: item.imageUrl }));

    return {
      ...folder,
      itemCount: contained.length,
      subfolderCount: folders.filter((f) => f.parentFolderId === folder.id).length,
      lastModifiedAt,
      lastActivityAt,
      activityCount: null,
      preview,
    } satisfies ResourceFolderTile;
  });

  return sortTiles(tiles, sort);
}

/** A folder plus every folder below it. */
function subtreeIds(folders: ResourceFolder[], rootId: string): string[] {
  const ids = [rootId];
  for (let i = 0; i < ids.length; i += 1) {
    const current = ids[i];
    for (const folder of folders) {
      if (folder.parentFolderId === current && !ids.includes(folder.id)) ids.push(folder.id);
    }
  }
  return ids;
}

/**
 * Ordered like the server does it: newest first on the time keys, and an empty folder LAST
 * rather than first (the naive descending compare floats a missing date to the top).
 */
function sortTiles(tiles: ResourceFolderTile[], sort: FolderTileSort): ResourceFolderTile[] {
  const ordered = [...tiles];
  if (sort === 'name') {
    ordered.sort((a, b) => (a.name ?? '').localeCompare(b.name ?? '', undefined, { sensitivity: 'base' }));
  } else {
    const field = sort === 'lastActivity' ? 'lastActivityAt' : 'lastModifiedAt';
    ordered.sort((a, b) => {
      const left = time(a[field]);
      const right = time(b[field]);
      if (left === right) return 0;
      if (left === 0) return 1;
      if (right === 0) return -1;
      return right - left;
    });
  }
  return ordered;
}

function newest(values: Array<string | null | undefined>): string | null {
  let best: string | null = null;
  for (const value of values) {
    if (!value) continue;
    if (best === null || time(value) > time(best)) best = value;
  }
  return best;
}

function time(value: string | null | undefined): number {
  if (!value) return 0;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? 0 : parsed;
}

/**
 * Root -> ... -> folder, so a client-built level can render the path it navigated into. A
 * broken chain (a parent deleted concurrently) simply ends the trail rather than failing.
 */
export function buildFolderTrail(folders: ResourceFolder[], folderId: string | null): ResourceFolder[] {
  if (!folderId) return [];
  const byId = new Map(folders.map((f) => [f.id, f]));
  const trail: ResourceFolder[] = [];
  const seen = new Set<string>();
  let current: string | null = folderId;
  while (current && !seen.has(current)) {
    seen.add(current);
    const folder: ResourceFolder | undefined = byId.get(current);
    if (!folder) break;
    trail.unshift(folder);
    current = folder.parentFolderId ?? null;
  }
  return trail;
}
