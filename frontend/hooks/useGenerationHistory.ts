'use client';

import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  storageApi,
  type GenerationHistoryEntry,
  type GenerationProvenance,
} from '@/lib/api/storage-api';

/**
 * What this workspace has generated, and the recipe behind one asset.
 *
 * <p><b>Why there is a history at all.</b> A generated file is indistinguishable from an uploaded
 * one once it lands in Files: same row, same bytes, nothing saying which model made it or from
 * which words. So the two things people always want from something they generated - to look back at
 * what they have made, and to run one again with a single parameter changed - were both impossible,
 * and the only way to attempt the second was to retype the prompt from memory.
 *
 * <p>The history is not a list kept beside the files: it IS the files. Each generated asset carries
 * its own recipe, so an asset deleted from Files takes its entry with it and no entry can ever
 * point at something that is gone. That is also why this reads through the storage explorer rather
 * than through a generation endpoint - the rows are files, and they are read where files are read.
 *
 * <p>The query key is stated once here so every surface showing the history (the dialog and the
 * Files view) shares one cache: opening both must not cost two requests, and a generation that
 * lands must refresh both.
 */

export const GENERATION_HISTORY_QUERY_KEY = 'generation-history';

/** How many entries a page of history holds. Enough to fill a grid without a scroll marathon. */
export const GENERATION_HISTORY_PAGE_SIZE = 12;

export interface GenerationHistoryState {
  entries: GenerationHistoryEntry[];
  /**
   * Whether a next page exists.
   *
   * <p>The server answers a slice rather than a counted page: a total would cost a second pass over
   * every file the workspace owns, on every page view, for one number nobody acts on. This is the
   * half a pager actually uses.
   */
  hasMore: boolean;
  isLoading: boolean;
  /** True when the request itself failed - a DIFFERENT fact from "nothing has been generated". */
  isError: boolean;
}

/**
 * Read a page of the generation history.
 *
 * @param page zero-based
 * @param kind optional format filter, matched against the recipe's own kind - not against the mime
 *        type, which cannot tell a voice from a music track
 * @param enabled pass false to hold the request back (a panel that is closed). A disabled reader
 *        still gets whatever another reader has already put in the cache.
 */
export function useGenerationHistory(
  page: number = 0,
  kind?: string,
  enabled: boolean = true,
): GenerationHistoryState {
  const { data, isLoading, isError } = useQuery({
    queryKey: [GENERATION_HISTORY_QUERY_KEY, page, kind ?? null],
    queryFn: () => storageApi.getGenerationHistory({
      page,
      size: GENERATION_HISTORY_PAGE_SIZE,
      kind,
    }),
    enabled,
  });

  return {
    // Enforced at the boundary rather than trusted: callers map over this list, so a 200 with a
    // body that is not a page would crash during render instead of showing an empty history.
    entries: Array.isArray(data?.content) ? data.content : [],
    // Absent or unreadable reads as "no next page": offering one that does not exist strands the
    // reader on an empty screen, which is worse than stopping one page early.
    hasMore: data?.last === false,
    isLoading,
    isError,
  };
}

/**
 * Drop every cached page of the history.
 *
 * <p>Called when a generation finishes: the new asset belongs at the top of the first page, and
 * every page after it has shifted by one, so invalidating one page would leave the rest wrong.
 */
export function useInvalidateGenerationHistory(): () => void {
  const queryClient = useQueryClient();
  return useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: [GENERATION_HISTORY_QUERY_KEY] });
  }, [queryClient]);
}

/**
 * The recipe one asset was made from, or null when it was not generated here.
 *
 * <p>Null is the ORDINARY answer: almost every file in a workspace was uploaded or written by a
 * workflow. It is what keeps the Regenerate control off files that have nothing to regenerate.
 *
 * @param fileId storage row id; pass null/undefined to ask nothing
 * @param enabled pass false where the answer could not be acted on anyway (no way to open the
 *        dialog), so a file viewer that offers no Regenerate costs no request
 */
export function useGenerationProvenance(
  fileId: string | null | undefined,
  enabled: boolean = true,
): { provenance: GenerationProvenance | null; isLoading: boolean; isError: boolean } {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['generation-provenance', fileId ?? null],
    queryFn: () => storageApi.getGenerationProvenance(fileId as string),
    // A recipe is written once, when the asset is created, and never changes afterwards - so one
    // answer serves the session and re-opening a file costs nothing.
    staleTime: Infinity,
    enabled: enabled && Boolean(fileId),
  });

  // isError is surfaced, and it is NOT the same as a null recipe. The service resolves null for a
  // 404 ("this file was not generated") and throws for anything else ("we could not ask"), and a
  // caller that cannot tell them apart hides a Regenerate control on a hiccup exactly as it would
  // on an ordinary upload - silently, on the files where it matters.
  return { provenance: data ?? null, isLoading, isError };
}
