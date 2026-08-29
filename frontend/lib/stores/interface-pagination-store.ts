import * as React from 'react';
import { create } from 'zustand';

/**
 * Epoch pagination convention:
 *   - Backend sorts epochs newest-first (descending).
 *   - Page 0 = newest epoch, page N-1 = oldest.
 *   - Display shows `totalPages - page` so newest = highest number.
 *   - handleNewer() decrements page (→ newer epoch, higher display number).
 *   - handleOlder() increments page (→ older epoch, lower display number).
 */
interface InterfacePaginationState {
  pages: Record<string, number>;
  playing: Record<string, boolean>;
  /**
   * Which page of the application carousel each SURFACE is showing, keyed by
   * {@link carouselKeyFor}.
   *
   * It was a single number while at most one carousel existed at a time. The
   * side panel keeps every opened tab mounted, so several carousels now live
   * side by side (two applications opened from a chat, an application tab next
   * to a workflow tab) and one shared number slaved them all to whichever
   * navigated last - the same trap `shouldAdoptEpochEvent` closes for epochs.
   */
  carouselIndex: Record<string, number>;
  setPage: (interfaceId: string, page: number) => void;
  setPlaying: (interfaceId: string, playing: boolean) => void;
  setCarouselIndex: (carouselKey: string, index: number) => void;
  /**
   * Forget what a run switch actually invalidates.
   *
   * Epoch cursors (`pages`) and playback flags are keyed by interfaceId, which a
   * new run reuses, so those still go wholesale. The carousel page is now keyed
   * by SURFACE, and only the run being left is stale: wiping the whole map -
   * which is what `clear()` does - hands back the isolation the keying buys, so
   * that mounting a second application tab no longer resets the page every other
   * tab is showing.
   */
  clearForRunSwitch: (fromRunId?: string) => void;
  clear: () => void;
}

/**
 * Identity of one application carousel: the workflow it renders and the run it
 * renders against. Two tabs of the same application on different runs are two
 * surfaces, and each keeps its own page.
 */
export function carouselKeyFor(workflowId?: string | null, runId?: string | null): string {
  return `${workflowId ?? ''}:${runId ?? ''}`;
}

export const useInterfacePaginationStore = create<InterfacePaginationState>((set) => ({
  pages: {},
  playing: {},
  carouselIndex: {},
  setPage: (interfaceId, page) =>
    set((state) => ({ pages: { ...state.pages, [interfaceId]: page } })),
  setPlaying: (interfaceId, playing) =>
    set((state) => ({ playing: { ...state.playing, [interfaceId]: playing } })),
  setCarouselIndex: (carouselKey, index) =>
    set((state) => ({ carouselIndex: { ...state.carouselIndex, [carouselKey]: index } })),
  clearForRunSwitch: (fromRunId) =>
    set((state) => {
      // No run to forget (a surface that only ever observed someone else's
      // switch): the epoch cursors still go, the carousel pages all stay.
      const suffix = fromRunId ? `:${fromRunId}` : null;
      return {
        pages: {},
        playing: {},
        carouselIndex: suffix
          ? Object.fromEntries(
            Object.entries(state.carouselIndex).filter(([key]) => !key.endsWith(suffix)),
          )
          : state.carouselIndex,
      };
    }),
  clear: () => set({ pages: {}, playing: {}, carouselIndex: {} }),
}));

// Phase 6 (2026-05-18) - HMR-safe workspace-switch reset. Pagination state
// is keyed by interfaceId (UUID, no collision risk) but flagged stale by
// the audit because WorkflowModeContext only clears it on run-to-run
// switches within the same workspace. Switching workspace mid-flight
// leaves stale pagination cursors visible.
if (typeof window !== 'undefined') {
  const HMR_KEY = Symbol.for('__lc_orgReset:useInterfacePaginationStore');
  const g = globalThis as unknown as Record<symbol, (() => void) | undefined>;
  if (typeof g[HMR_KEY] === 'function') g[HMR_KEY]!();
  import('@/lib/stores/current-org-store').then(({ useCurrentOrgStore }) => {
    g[HMR_KEY] = useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => useInterfacePaginationStore.getState().clear(),
    );
  }).catch(() => {});
}

/**
 * Convenience hook that mirrors the useState<number> API:
 *   const [currentPage, setCurrentPage] = useSharedInterfacePage(interfaceId);
 *
 * Supports both direct values and functional updaters: setCurrentPage(2) or setCurrentPage(prev => prev + 1).
 */
export function useSharedInterfacePage(
  interfaceId: string | null,
): [number, (pageOrFn: number | ((prev: number) => number)) => void] {
  const currentPage = useInterfacePaginationStore(
    (s) => (interfaceId ? s.pages[interfaceId] ?? 0 : 0),
  );
  const setPage = useInterfacePaginationStore((s) => s.setPage);

  const setCurrentPage = React.useCallback(
    (pageOrFn: number | ((prev: number) => number)) => {
      if (!interfaceId) return;
      if (typeof pageOrFn === 'function') {
        const current = useInterfacePaginationStore.getState().pages[interfaceId] ?? 0;
        setPage(interfaceId, pageOrFn(current));
      } else {
        setPage(interfaceId, pageOrFn);
      }
    },
    [interfaceId, setPage],
  );

  return [currentPage, setCurrentPage];
}

/**
 * Shared play/pause state for an interface - synced across all consumers.
 *   const [isPlaying, setIsPlaying] = useSharedPlayState(interfaceId);
 */
export function useSharedPlayState(
  interfaceId: string | null,
): [boolean, (playing: boolean | ((prev: boolean) => boolean)) => void] {
  const isPlaying = useInterfacePaginationStore(
    (s) => (interfaceId ? s.playing[interfaceId] ?? false : false),
  );
  const setPlayingStore = useInterfacePaginationStore((s) => s.setPlaying);

  const setIsPlaying = React.useCallback(
    (playingOrFn: boolean | ((prev: boolean) => boolean)) => {
      if (!interfaceId) return;
      if (typeof playingOrFn === 'function') {
        const current = useInterfacePaginationStore.getState().playing[interfaceId] ?? false;
        setPlayingStore(interfaceId, playingOrFn(current));
      } else {
        setPlayingStore(interfaceId, playingOrFn);
      }
    },
    [interfaceId, setPlayingStore],
  );

  return [isPlaying, setIsPlaying];
}
