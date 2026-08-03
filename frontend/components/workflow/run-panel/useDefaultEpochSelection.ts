'use client';

import { useEffect } from 'react';

/**
 * Opening a run shows ALL of its epochs.
 *
 * A run is a sequence of fires and the cumulative view is what "the run" means:
 * landing on one specific epoch hid every other fire behind a selector the user
 * never asked for. So the run surfaces (the canvas and the Run tab) select
 * nothing on their own and follow nothing: `null` ("All epochs") is where every
 * run opens, and a single epoch is only ever shown because the user picked it.
 * (The application carousel still jumps to a new fire, but only for a user who
 * had pinned an epoch - it never leaves the cumulative view on its own.)
 *
 * What remains is continuity: an explicit pick must survive the surface that
 * made it. The choice lives at module scope, keyed by run id, because the two
 * surfaces that can change the epoch (the canvas and the side-panel Run tab) are
 * in separate React trees, and `viewingEpoch` dies with its provider - so a
 * panel that is closed and reopened would otherwise drop the choice.
 */
const pickedEpochByRun = new Map<string, number | null>();

/**
 * Only recently-viewed runs matter: this exists to carry a choice across a
 * remount of the surface showing that run, a question that dies with the
 * navigation. Without a bound, a session browsing hundreds of runs keeps every
 * id for its whole lifetime. Map iteration is insertion-ordered, so the first
 * entry is the oldest.
 */
const MAX_TRACKED_RUNS = 50;

/** Record the epoch the user picked for this run. `null` means "All epochs". */
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

/**
 * Go back to the default view, and REMEMBER that this is where the user wants
 * to be.
 *
 * Clearing the selection alone is not enough: a surface reporting "nothing
 * selected" is indistinguishable from one that just mounted, so the restore
 * below would re-apply the epoch the user was on and snap them back. Every
 * "fire from here" control goes through this (the trigger node's play, the
 * canvas toolbar's, the run-info step row's), because leaving the focused epoch
 * is part of what the user asked for. A reset that the user did NOT ask for
 * (the edit/run toggle dropping the chip) must stay a plain clear: it is not a
 * choice, and recording it would erase the pick the run actually has.
 */
export function selectAllEpochs(
  runId: string | null | undefined,
  onSelectEpoch: (epoch: number | null) => void,
): void {
  markEpochPickedByUser(runId, null);
  onSelectEpoch(null);
}

/**
 * The epoch the user picked for this run: a number, `null` for an explicit
 * "All epochs", or undefined when they never picked one (still on the default).
 */
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
  selectedEpoch: number | null;
  onSelectEpoch: (epoch: number | null) => void;
  /** Skip entirely (e.g. edit mode, no run bound). */
  enabled?: boolean;
}

/**
 * Holds a run's epoch selection at its default ("All epochs") and restores an
 * explicit pick on a surface that mounts with nothing selected. It never selects
 * an epoch on its own.
 */
export function useDefaultEpochSelection({
  runId,
  selectedEpoch,
  onSelectEpoch,
  enabled = true,
}: DefaultEpochSelectionOptions): void {
  useEffect(() => {
    if (!enabled || !runId) return;

    // Restore the epoch the user picked for this run when this surface has no
    // selection of its own (a freshly mounted panel). Each run carries its own
    // choice, so another run is unaffected. A `null` pick IS the default view,
    // so there is nothing to restore for it - which is what makes
    // `selectAllEpochs` stick instead of bouncing back.
    const picked = getPickedEpoch(runId);
    if (selectedEpoch == null && picked != null) onSelectEpoch(picked);
  }, [enabled, runId, selectedEpoch, onSelectEpoch]);
}
