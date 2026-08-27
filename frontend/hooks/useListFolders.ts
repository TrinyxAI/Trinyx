'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import {
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { useResourceFolderActions } from '@/hooks/useResourceFolderActions';
import {
  emitResourceFolderTrail,
  folderUrl,
  FOLDER_QUERY_PARAM,
} from '@/lib/folders/foldersHeaderBus';
import type {
  FolderResourceKind,
  ResourceFolder,
  ResourceFolderTile,
} from '@/lib/api/orchestrator/resource-folder.service';

/** The folder-bearing part of any list response. */
export interface FolderListResponse {
  folders?: ResourceFolderTile[];
  folderTrail?: ResourceFolder[];
  folderMissing?: boolean;
}

interface UseListFoldersOptions {
  /** Which list's folders these are. */
  kind: FolderResourceKind;
  /** Re-fetch the list (the level, the tiles and the trail all come from it). */
  reload: () => void | Promise<void>;
  /** The list's current selection - a drag that starts on a selected card moves them all. */
  selectedIds: ReadonlySet<string>;
  clearSelection: () => void;
  /** True while a search term is active: a search looks through every folder. */
  searching: boolean;
  /**
   * True while the list is fetching. A tile click is ignored then, because the tiles on
   * screen may still be the previous level's.
   */
  busy?: boolean;
  /** False for a read-only surface (a VIEWER in an org workspace). */
  canMutate: boolean;
  /** Localized strings, resolved by the caller from the `folders` namespace. */
  labels: {
    actionFailed: string;
    createFailed: string;
    renameFailed: string;
    deleteFailed: string;
    moveFailed: string;
    moved: string;
    movedToFolder: (count: number, name: string) => string;
    movedToTopLevel: (count: number) => string;
  };
  /** Show a toast (each list already has its own toaster). */
  notify: (toast: { type: 'success' | 'error'; title: string; message: string }) => void;
  /**
   * Map a SELECTION id to the id the folder API files. Identity for the lists whose cards
   * are keyed by the resource itself; the applications list keys its cards by provenance
   * ("acquired-<id>") while its filing is by publication id, so it maps.
   */
  toResourceId?: (selectionId: string) => string;
}

/**
 * Everything a resource list needs to have folders, minus the rendering: which level it is
 * showing, the tiles and trail that came back with it, the create/rename/delete/move
 * dialogs' state, and the drag-and-drop wiring that files a card into a folder.
 *
 * <p>The navigation state lives here rather than in the folder service because it is a
 * fetch parameter, exactly like the page number: the list asks for one level and gets that
 * level's rows AND its tiles in a single response.
 */
export function useListFolders({
  kind,
  reload,
  selectedIds,
  clearSelection,
  searching,
  busy = false,
  canMutate,
  labels,
  notify,
  toResourceId = (id) => id,
}: UseListFoldersOptions) {
  // WHICH folder is open lives in the URL, not in this hook: `?folder=<id>`. That is what
  // makes the path survive a reload, a shared link and the browser's back button, and what
  // lets the app header navigate the list by simply changing the address.
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const currentFolderId = searchParams.get(FOLDER_QUERY_PARAM);

  const [tiles, setTiles] = useState<ResourceFolderTile[]>([]);
  const [trail, setTrail] = useState<ResourceFolder[]>([]);
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [folderBeingRenamed, setFolderBeingRenamed] = useState<ResourceFolderTile | null>(null);
  const [folderBeingDeleted, setFolderBeingDeleted] = useState<ResourceFolderTile | null>(null);
  const [showMoveDialog, setShowMoveDialog] = useState(false);
  const [activeDrag, setActiveDrag] = useState<{ label: string; count: number } | null>(null);

  const onError = useCallback((message: string) => {
    notify({ type: 'error', title: labels.actionFailed, message });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [labels.actionFailed]);

  const actions = useResourceFolderActions({ kind, onChanged: reload, onError });

  /** Folders only make sense on a level; a search spans all of them. */
  const foldersEnabled = !searching;
  const canOrganize = canMutate && foldersEnabled;

  /**
   * Feed the hook the list response, so the tiles and trail follow the level.
   *
   * <p>A list with no folders gets the SAME empty arrays back rather than fresh ones: every
   * page load calls this, and handing React a new array each time re-renders the whole list
   * for nothing (and, on a page whose fetch callback is not identity-stable, loops).
   */
  const applyListResponse = useCallback((response: FolderListResponse) => {
    const nextTiles = response.folders ?? [];
    const nextTrail = response.folderTrail ?? [];
    setTiles((previous) => (previous.length === 0 && nextTiles.length === 0 ? previous : nextTiles));
    setTrail((previous) => (previous.length === 0 && nextTrail.length === 0 ? previous : nextTrail));
    // The folder we were in is gone (deleted here or by a teammate): the server already
    // answered with the top level, so take the address there rather than leaving a dead
    // folder id in it.
    if (response.folderMissing) {
      router.replace(folderUrl(pathname, searchParams, null), { scroll: false });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname, searchParams]);

  /** What to send as the list's `folderId` parameter. */
  const folderIdParam = currentFolderId ?? 'root';

  /**
   * Go to a level. `push`, not `replace`: each folder you open is a step, so the browser's
   * Back button walks back OUT of the folders one at a time, the way it does in the Files
   * browser. Replacing was the reason Back used to jump off the list entirely - the level
   * you came from was never in the history to return to.
   *
   * <p>Re-selecting the level already open is a no-op, so the crumb of the folder you are
   * standing in cannot stack a duplicate entry that costs an extra Back to get past.
   */
  const navigateToFolder = useCallback((folderId: string | null) => {
    if ((folderId ?? null) === (currentFolderId ?? null)) return;
    router.push(folderUrl(pathname, searchParams, folderId), { scroll: false });
    clearSelection();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname, searchParams, clearSelection, currentFolderId]);

  /**
   * Open a folder tile. Ignored while the listing is being fetched: the tiles on screen
   * still belong to the PREVIOUS level then, so a click during that window would push a
   * folder that is not a child of where the user thinks they are.
   */
  const openFolder = useCallback((folder: ResourceFolderTile) => {
    if (busy) return;
    navigateToFolder(folder.id);
  }, [navigateToFolder, busy]);

  /** File resources into a folder (null = back to the top level) and report what moved. */
  const fileResources = useCallback(async (folderId: string | null, ids: string[]) => {
    if (ids.length === 0) return;
    const moved = await actions.assignToFolder(folderId, ids.map(toResourceId), labels.moveFailed);
    if (moved === null) return;
    clearSelection();
    const name = folderId
      ? (tiles.find((f) => f.id === folderId)?.name ?? actions.allFolders.find((f) => f.id === folderId)?.name ?? '')
      : null;
    notify({
      type: 'success',
      title: labels.moved,
      message: name ? labels.movedToFolder(moved, name) : labels.movedToTopLevel(moved),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actions, tiles, clearSelection, toResourceId]);

  /**
   * File a resource that was just created into the folder the user is standing in.
   *
   * <p>Creating something while inside a folder must leave it there: landing back at the
   * top level would make the folder look like a filter that creation ignores. Silent on
   * purpose - the user asked to create, not to move, so there is no "moved" toast; and a
   * failure only means the new resource sits at the top level, where it is still visible.
   */
  const fileNewResource = useCallback(async (id: string) => {
    if (!currentFolderId) return;
    await actions.assignToFolder(currentFolderId, [toResourceId(id)], labels.moveFailed);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentFolderId, actions, toResourceId]);

  const createFolder = useCallback(async (name: string) => {
    // A folder is created where the user is standing, so it appears right away.
    await actions.createFolder(name, currentFolderId, labels.createFailed);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actions, currentFolderId]);

  const renameFolder = useCallback(async (name: string) => {
    if (!folderBeingRenamed) return;
    await actions.renameFolder(folderBeingRenamed.id, name, labels.renameFailed);
    setFolderBeingRenamed(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actions, folderBeingRenamed]);

  const confirmDeleteFolder = useCallback(async () => {
    if (!folderBeingDeleted) return;
    await actions.deleteFolder(folderBeingDeleted.id, labels.deleteFailed);
    setFolderBeingDeleted(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actions, folderBeingDeleted]);

  const openMoveDialog = useCallback(() => {
    // The picker needs every level, not just the one on screen.
    actions.loadAllFolders();
    setShowMoveDialog(true);
  }, [actions]);

  // Six pixels of travel before a drag starts, so clicking a card still opens it.
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  /**
   * Start of a drag. The caller resolves the dragged resource's display name; a drag that
   * starts on a selected card carries the whole selection.
   */
  const handleDragStart = useCallback((event: DragStartEvent, nameOf: (id: string) => string | undefined) => {
    const data = event.active.data.current as { type?: string; resourceId?: string } | undefined;
    if (data?.type === 'resource' && data.resourceId) {
      setActiveDrag({
        label: nameOf(data.resourceId) ?? '',
        count: selectedIds.has(data.resourceId) ? selectedIds.size : 1,
      });
    } else {
      setActiveDrag(null);
    }
  }, [selectedIds]);

  const handleDragEnd = useCallback(async (event: DragEndEvent) => {
    setActiveDrag(null);
    const over = event.over?.data.current as { type?: string; folderId?: string | null } | undefined;
    const active = event.active.data.current as
      { type?: string; resourceId?: string; folderId?: string } | undefined;
    if (!over || over.type !== 'folder') return;
    const targetFolderId = over.folderId ?? null;

    if (active?.type === 'resource' && active.resourceId) {
      const ids = selectedIds.has(active.resourceId) ? Array.from(selectedIds) : [active.resourceId];
      await fileResources(targetFolderId, ids);
      return;
    }
    if (active?.type === 'folder' && active.folderId && active.folderId !== targetFolderId) {
      // Nesting a folder. The backend refuses a folder dropped into its own subtree.
      await actions.moveFolder(active.folderId, targetFolderId, labels.moveFailed);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedIds, fileResources, actions]);

  const cancelDrag = useCallback(() => setActiveDrag(null), []);

  // Tell the app header what to print. Only the names travel: the header navigates by
  // changing the address, so it needs no other state of ours. On unmount the trail is
  // cleared, or the header would keep showing a path the user has left.
  useEffect(() => {
    emitResourceFolderTrail({ view: kind, trail: trail.map((f) => ({ id: f.id, name: f.name })) });
  }, [kind, trail]);
  useEffect(() => () => emitResourceFolderTrail({ view: kind, trail: [] }), [kind]);

  /** Shape the "move to..." picker reads. */
  const moveDialogFolders = useMemo(
    () => actions.allFolders.map((f) => ({ id: f.id, name: f.name, parentFolderId: f.parentFolderId ?? null })),
    [actions.allFolders],
  );

  return {
    // Where the list is
    currentFolderId,
    folderIdParam,
    tiles,
    trail,
    applyListResponse,
    navigateToFolder,
    openFolder,
    // What the caller may do
    foldersEnabled,
    canOrganize,
    // Writes
    createFolder,
    renameFolder,
    confirmDeleteFolder,
    fileResources,
    fileNewResource,
    // Dialog state
    showCreateDialog,
    setShowCreateDialog,
    folderBeingRenamed,
    setFolderBeingRenamed,
    folderBeingDeleted,
    setFolderBeingDeleted,
    showMoveDialog,
    setShowMoveDialog,
    openMoveDialog,
    moveDialogFolders,
    loadingFolders: actions.loadingFolders,
    // Drag and drop
    sensors,
    handleDragStart,
    handleDragEnd,
    cancelDrag,
    activeDrag,
  };
}

export type ListFolders = ReturnType<typeof useListFolders>;
