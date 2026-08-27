'use client';

import { useCallback, useRef, useState } from 'react';
import {
  resourceFolderService,
  type FolderResourceKind,
  type ResourceFolder,
} from '@/lib/api/orchestrator/resource-folder.service';

interface UseResourceFolderActionsOptions {
  /** Which list's folders these are. */
  kind: FolderResourceKind;
  /** Called after any successful change, so the page reloads its level. */
  onChanged: () => void | Promise<void>;
  /** Surfaces a failure to the user (the hook never swallows one silently). */
  onError: (message: string) => void;
}

/**
 * Creating, renaming, deleting and filing into folders, for any list page that has them.
 * The page keeps the navigation state (which folder it is showing) because that is a fetch
 * parameter like the page number; this hook keeps only what is about the folders
 * THEMSELVES - the writes, and the flat tree the "move to..." picker needs.
 */
export function useResourceFolderActions({ kind, onChanged, onError }: UseResourceFolderActionsOptions) {
  const [allFolders, setAllFolders] = useState<ResourceFolder[]>([]);
  const [loadingFolders, setLoadingFolders] = useState(false);
  const [busy, setBusy] = useState(false);
  // Only the newest load applies its result, so a slow first response cannot overwrite a
  // fresher tree (the picker can be opened twice in a row).
  const loadIdRef = useRef(0);

  /** Load the whole folder tree (the picker and the nesting rules need every level). */
  const loadAllFolders = useCallback(async () => {
    const loadId = ++loadIdRef.current;
    setLoadingFolders(true);
    try {
      const folders = await resourceFolderService.list(kind);
      if (loadId === loadIdRef.current) setAllFolders(folders);
    } catch (err) {
      console.error('Error loading folders:', err);
      if (loadId === loadIdRef.current) setAllFolders([]);
    } finally {
      if (loadId === loadIdRef.current) setLoadingFolders(false);
    }
  }, [kind]);

  const run = useCallback(async <T,>(action: () => Promise<T>, errorMessage: string): Promise<T | null> => {
    setBusy(true);
    try {
      const result = await action();
      await onChanged();
      return result;
    } catch (err) {
      console.error(errorMessage, err);
      onError(errorMessage);
      return null;
    } finally {
      setBusy(false);
    }
  }, [onChanged, onError]);

  const createFolder = useCallback(
    (name: string, parentFolderId: string | null, errorMessage: string) =>
      run(() => resourceFolderService.create(kind, name, parentFolderId), errorMessage),
    [kind, run],
  );

  const renameFolder = useCallback(
    (folderId: string, name: string, errorMessage: string) =>
      run(() => resourceFolderService.rename(kind, folderId, name), errorMessage),
    [kind, run],
  );

  const deleteFolder = useCallback(
    (folderId: string, errorMessage: string) =>
      run(() => resourceFolderService.remove(kind, folderId), errorMessage),
    [kind, run],
  );

  const moveFolder = useCallback(
    (folderId: string, parentFolderId: string | null, errorMessage: string) =>
      run(() => resourceFolderService.move(kind, folderId, parentFolderId), errorMessage),
    [kind, run],
  );

  /** File resources into a folder, or back to the top level with `null`. */
  const assignToFolder = useCallback(
    (folderId: string | null, resourceIds: string[], errorMessage: string) =>
      run(() => resourceFolderService.assign(kind, folderId, resourceIds), errorMessage),
    [kind, run],
  );

  return {
    allFolders,
    loadingFolders,
    busy,
    loadAllFolders,
    createFolder,
    renameFolder,
    deleteFolder,
    moveFolder,
    assignToFolder,
  };
}
