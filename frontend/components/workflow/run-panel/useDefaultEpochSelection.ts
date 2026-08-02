'use client';

import { useEffect, useRef } from 'react';
import { latestEpoch, type EpochTimestamp } from './runFormatting';

/**
 * A run is always read THROUGH an epoch: the run surfaces never sit on "nothing
 * selected". This hook seeds the selection with the most recent epoch and keeps
 * following new epochs as they open, until the user picks one explicitly.
 *
 * The "is this run still auto-following?" flag lives at module scope, keyed by
 * run id, because the two surfaces that can change the epoch (the canvas and the
 * side-panel Run tab) are in separate React trees. A component-local flag would
 * let one tree undo the other's explicit choice - most visibly when the user
 * picks "All epochs" (a null selection), which the other tree would immediately
 * overwrite with the latest epoch.
 */
/**
 * The user's choice per run - the EPOCH, not just "they chose". A boolean was
 * not enough: `viewingEpoch` dies with its provider, so after the side panel is
 * closed and reopened the flag said "do not auto-follow" while the selection
 * itself was gone, leaving the panel on neither the pick nor the latest epoch.
 * `null` is a real value here ("All epochs").
 */
const pickedEpochByRun = new Map<string, number | null>();

/**
 * Only recently-viewed runs matter: this exists to stop one React tree from
 * undoing the other's choice while both show the SAME run, a question that dies
 * with the navigation. Without a bound, a session browsing hundreds of runs keeps
 * every id for its whole lifetime. Map iteration is insertion-ordered, so the
 * first entry is the oldest.
 */
const MAX_TRACKED_RUNS = 50;

/** Record the epoch the user picked for this run - stop auto-following it. */
export function markEpochPickedByUser(runId: string | null | undefined, epoch: number | null = null): void {
  if (!runId) return;
  // Delete first so a re-pick refreshes recency: otherwise the eviction below can
  // drop the run currently on screen while keeping ids nobody has looked at.
  pickedEpochByRun.delete(runId);
  pickedEpochByRun.set(runId, epoch);
  while (pickedEpochByRun.size > MAX_TRACKED_RUNS) {
    const oldest = pickedEpochByRun.keys().next().value;
    if (oldest === undefined) break;
    pickedEpochByRun.delete(oldest);
  }
}

/** True while the run's epoch selection still follows the newest epoch. */
export function isEpochAutoFollowing(runId: string | null | undefined): boolean {
  return !!runId && !pickedEpochByRun.has(runId);
}

/** The epoch the user picked for this run, or undefined if they never did. */
export function getPickedEpoch(runId: string | null | undefined): number | null | undefined {
  if (!runId) return undefined;
  return pickedEpochByRun.has(runId) ? pickedEpochByRun.get(runId) : undefined;
}

/** Forget a run's choice (used on org switch / tests). */
export function resetEpochSelectionState(runId?: string | null): void {
  if (runId) pickedEpochByRun.delete(runId);
  else pickedEpochByRun.clear();
}

export interface DefaultEpochSelectionOptions {
  runId: string | null | undefined;
  epochTimestamps: readonly EpochTimestamp[];
  selectedEpoch: number | null;
  onSelectEpoch: (epoch: number | null) => void;
  /** Skip entirely (e.g. edit mode, no run bound). */
  enabled?: boolean;
}

export function useDefaultEpochSelection({
  runId,
  epochTimestamps,
  selectedEpoch,
  onSelectEpoch,
  enabled = true,
}: DefaultEpochSelectionOptions): void {
  const prevLatestRef = useRef<number | null>(null);
  const prevRunIdRef = useRef<string | null | undefined>(runId);

  useEffect(() => {
    // A different run starts over: it has its own epochs and its own choice.
    if (prevRunIdRef.current !== runId) {
      prevRunIdRef.current = runId;
      prevLatestRef.current = null;
    }

    const latest = latestEpoch(epochTimestamps as EpochTimestamp[]);
    const prevLatest = prevLatestRef.current;
    prevLatestRef.current = latest;

    if (!enabled || latest == null || !runId) return;

    if (!isEpochAutoFollowing(runId)) {
      // The user picked an epoch for this run. Restore it when this surface has
      // no selection of its own (a freshly mounted panel), and otherwise leave
      // the selection alone - that is what "explicitly picked" means.
      const picked = getPickedEpoch(runId);
      if (selectedEpoch == null && picked != null) onSelectEpoch(picked);
      return;
    }

    // 1. Nothing selected yet → land on the most recent epoch.
    if (selectedEpoch == null) {
      onSelectEpoch(latest);
      return;
    }
    // 2. Parked on what WAS the newest epoch and a newer one just opened →
    //    follow it, so a live run keeps showing what is executing now.
    if (prevLatest != null && selectedEpoch === prevLatest && latest !== prevLatest) {
      onSelectEpoch(latest);
    }
  }, [enabled, runId, epochTimestamps, selectedEpoch, onSelectEpoch]);
}
