/**
 * Resource Folder Service
 *
 * Folders of a RESOURCE LIST page - the workflow list today, the agent / table /
 * interface / application lists as each one is wired. Every list keeps its own folders
 * (a folder of workflows is not a folder of agents), so the calls are keyed by `kind`
 * and each kind resolves to the service that owns that list.
 *
 * The folder TILES (count + preview of what is inside) do not come from here: they ride
 * with the list response itself, which is where the scoped resources already are.
 */

import { apiClient } from '../api-client';

/** The list pages that can be organised into folders. */
export type FolderResourceKind = 'workflow' | 'agent' | 'interface' | 'table' | 'application';

/** Which service owns each kind's folders - the one that serves that list. */
const FOLDER_BASE_PATH: Record<FolderResourceKind, string> = {
  workflow: '/workflow-folders',
  agent: '/agent-folders',
  interface: '/interface-folders',
  table: '/table-folders',
  application: '/application-folders',
};

/** What each list calls a batch of its own ids. */
const ITEM_IDS_FIELD: Record<FolderResourceKind, string> = {
  workflow: 'workflowIds',
  agent: 'agentIds',
  interface: 'interfaceIds',
  table: 'tableIds',
  application: 'publicationIds',
};

/** A folder row: a name and a place in the tree, nothing more. */
export interface ResourceFolder {
  id: string;
  name: string;
  parentFolderId: string | null;
  createdAt?: string;
  updatedAt?: string;
}

/** One item drawn inside a folder tile. Each list reads the fields its own card style uses. */
export interface FolderPreviewItem {
  id: string;
  name?: string;
  /** Node icons, for the workflow tile (same data the workflow card paints). */
  icons?: Array<Record<string, unknown>>;
  /** Avatar / thumbnail / cover, for the tiles that show images. */
  imageUrl?: string;
  subtitle?: string;
}

/**
 * A folder as the list renders it. The counts and dates cover the folder's WHOLE subtree,
 * so a folder holding only subfolders never reads as empty - and they are what places it
 * in the page's ordering.
 */
export interface ResourceFolderTile extends ResourceFolder {
  itemCount: number;
  subfolderCount: number;
  lastModifiedAt?: string | null;
  lastActivityAt?: string | null;
  activityCount?: number | null;
  preview: FolderPreviewItem[];
}

export class ResourceFolderService {
  private basePath(kind: FolderResourceKind): string {
    return FOLDER_BASE_PATH[kind];
  }

  /** Every folder of the workspace, flat - the breadcrumb and the "move to..." picker build the tree. */
  async list(kind: FolderResourceKind): Promise<ResourceFolder[]> {
    const data = await apiClient.get<{ folders?: ResourceFolder[] }>(this.basePath(kind));
    return data.folders ?? [];
  }

  /** Create a folder, at the top level when `parentFolderId` is null. */
  async create(kind: FolderResourceKind, name: string, parentFolderId: string | null = null): Promise<ResourceFolder> {
    return apiClient.post<ResourceFolder>(this.basePath(kind), { name, parentFolderId });
  }

  async rename(kind: FolderResourceKind, folderId: string, name: string): Promise<ResourceFolder> {
    return apiClient.put<ResourceFolder>(`${this.basePath(kind)}/${folderId}`, { name });
  }

  /** Re-parent a folder. `null` brings it back to the top level. */
  async move(kind: FolderResourceKind, folderId: string, parentFolderId: string | null): Promise<ResourceFolder> {
    return apiClient.put<ResourceFolder>(`${this.basePath(kind)}/${folderId}/move`, { parentFolderId });
  }

  /**
   * Delete a folder and its subfolders. What was filed in them goes back to the top level -
   * deleting a folder never deletes a resource.
   *
   * @returns the folder ids that were removed
   */
  async remove(kind: FolderResourceKind, folderId: string): Promise<string[]> {
    const data = await apiClient.delete<{ deletedFolderIds?: string[] }>(`${this.basePath(kind)}/${folderId}`);
    return data?.deletedFolderIds ?? [folderId];
  }

  /**
   * Where each application is filed (`publicationId -> folderId`). Only the applications
   * list needs this: its filing lives apart from the resource (a publication row is shared
   * between publisher and acquirers), and the page builds its own tiles from the whole set
   * it already holds.
   */
  async memberships(kind: Extract<FolderResourceKind, 'application'>): Promise<Map<string, string>> {
    const data = await apiClient.get<{ memberships?: Record<string, string> }>(
      `${this.basePath(kind)}/memberships`,
    );
    return new Map(Object.entries(data.memberships ?? {}));
  }

  /**
   * File resources into a folder, or back to the top level with `folderId: null`.
   *
   * @returns how many were actually moved (ids outside the workspace are not)
   */
  async assign(kind: FolderResourceKind, folderId: string | null, resourceIds: string[]): Promise<number> {
    // Each list names its own ids, so the folder call speaks that list's language.
    const payload: Record<string, unknown> = { folderId, [ITEM_IDS_FIELD[kind]]: resourceIds };
    const data = await apiClient.post<{ moved?: number }>(`${this.basePath(kind)}/items`, payload);
    return data?.moved ?? 0;
  }
}

export const resourceFolderService = new ResourceFolderService();
