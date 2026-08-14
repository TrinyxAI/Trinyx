'use client';

/**
 * Canvas-wide expand/collapse state for the run-mode file strips.
 *
 * Each file-producing node hangs a {@code FileResultStrip} under its box in run
 * mode, and each of those is either folded (a one-line pill) or unfolded (a real
 * image/video/audio/pdf preview). On a run with a dozen file nodes, seeing every
 * result meant clicking every pill, one by one. This context lifts that boolean
 * to the canvas so the bottom toolbar can flip them all at once, and so it can
 * show the right state while doing it.
 *
 * The flip is also a REMEMBERED preference ({@link readFileStripPreference}): it
 * survives the run, the epoch and the browser session, and it is what a strip
 * falls back to when it has no opinion of its own. The toolbar control is
 * therefore always on screen, including in edit mode and on a run that produced
 * no file, because those are precisely the moments you would state it ahead of
 * time. (It used to hide itself whenever the canvas carried no strip, which meant
 * the only moment you could ask for open previews was a moment when they were
 * already in front of you.)
 *
 * The strips register themselves on mount rather than being enumerated from the
 * node list: whether a node ends up showing a strip is decided deep inside
 * {@code FileNodePreview} (run mode + completed + a FileRef actually resolved
 * from the step output), and re-deriving that condition in the toolbar would
 * duplicate it and drift from it.
 *
 * ## Why the state is NOT a set of expanded ids
 *
 * The builder canvas runs ReactFlow with {@code onlyRenderVisibleElements}, so a
 * node that leaves the viewport is UNMOUNTED and remounted when it comes back.
 * A design that stored "which strips are expanded" and pruned that set on
 * unmount therefore collapsed previews as the user simply panned across the
 * graph: expand-all, scroll, and half the canvas had silently folded back.
 *
 * So the state is an INTENT, not a snapshot of live components:
 *   - {@code defaultExpanded} - what a strip does when it has no opinion of its
 *     own. `expandAll`/`collapseAll` set exactly this, which is what makes them
 *     reach strips that are currently unmounted, and even nodes the user has
 *     never scrolled to.
 *   - `overrides` - the per-strip deviations from that default, keyed by the
 *     host node id (stable across an unmount/remount cycle), so a preview the
 *     user opened by hand survives panning away and back.
 * Unmounting a strip touches NEITHER, which is the whole point.
 *
 * `resetKey` is what draws the line between two contexts where the intent should
 * not carry over (another run, another epoch, leaving run mode): the ids are the
 * same but the files behind them are not. It rebuilds the registry from the
 * strips that are still mounted rather than emptying it, so the count stays
 * truthful across the switch instead of dipping through 0 (which is the value
 * that makes the toolbar read the stored preference instead of the strips).
 *
 * A consumer must read it through {@link useFileStripExpansionSafe}, which
 * returns {@code null} outside the provider: the strip is also mounted by
 * surfaces that carry no canvas chrome (the marketplace preview, isolated
 * tests), and it keeps its own local state there.
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { readFileStripPreference, writeFileStripPreference } from '@/lib/workflow/fileStripPreference';

export interface FileStripExpansionContextValue {
  /** Strips seen on the canvas for the current run/epoch. 0 means there is nothing on screen to act on. */
  stripCount: number;
  /** How many of those are currently expanded. */
  expandedCount: number;
  /**
   * The remembered choice a strip falls back to when it has no opinion of its own.
   * Exposed so the toolbar control can show the right state with NOTHING on screen:
   * with no strip, "are they all expanded?" is vacuously true, which would render
   * the toggle pressed while the stored preference says folded.
   */
  defaultExpanded: boolean;
  /** Announce a strip. Idempotent, and called again after a remount (virtualized scroll). */
  registerStrip: (stripId: string) => void;
  /**
   * Announce that a strip left the DOM. Deliberately does NOT change any
   * expansion state: with viewport virtualization this fires constantly while
   * panning, and reacting to it is exactly what used to fold previews on its own.
   */
  unregisterStrip: (stripId: string) => void;
  isExpanded: (stripId: string) => boolean;
  setExpanded: (stripId: string, expanded: boolean) => void;
  expandAll: () => void;
  collapseAll: () => void;
}

const FileStripExpansionContext = createContext<FileStripExpansionContextValue | null>(null);

export function FileStripExpansionProvider({
  children,
  resetKey,
}: {
  children: React.ReactNode;
  /**
   * Identity of what the canvas is showing (mode + run + epoch). When it
   * changes, the strips carry the same node ids but stand for different files,
   * so the expansion intent is dropped and the registry rebuilt from whatever is
   * still mounted. Omit it on a surface with nothing to reset.
   */
  resetKey?: string;
}) {
  // Live DOM presence. A ref, not state: it churns on every pan and nothing
  // renders off it - it exists only to rebuild the registry on a reset.
  const mountedRef = useRef<Set<string>>(new Set());
  // Every strip seen since the last reset, mounted or not. This is what the
  // toolbar counts, so panning a file node off-screen does not make the control
  // disappear mid-gesture.
  const [knownIds, setKnownIds] = useState<Set<string>>(() => new Set());
  const [defaultExpanded, setDefaultExpanded] = useState(false);
  const [overrides, setOverrides] = useState<Map<string, boolean>>(() => new Map());

  const registerStrip = useCallback((stripId: string) => {
    mountedRef.current.add(stripId);
    setKnownIds((prev) => (prev.has(stripId) ? prev : new Set(prev).add(stripId)));
  }, []);

  const unregisterStrip = useCallback((stripId: string) => {
    mountedRef.current.delete(stripId);
  }, []);

  const setExpanded = useCallback((stripId: string, expanded: boolean) => {
    setOverrides((prev) => {
      if (prev.get(stripId) === expanded) return prev;
      const next = new Map(prev);
      next.set(stripId, expanded);
      return next;
    });
  }, []);

  // Apply the remembered preference once mounted. Read here rather than in the
  // useState initializer: the canvas is a client component the server still
  // renders on first load, and a stored `true` against a server-rendered `false`
  // is a hydration mismatch. Costs one frame of folded pills for a user whose
  // preference is "expanded".
  // Same shape, and same reason, as WorkflowLayoutDirectionContext.
  useEffect(() => {
    const stored = readFileStripPreference();
    // Setting state from a mount effect IS the fix here, not the smell the rule
    // usually catches: reading storage during render is what would produce the
    // mismatch described just above. One extra render, once, on mount.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (stored !== null) setDefaultExpanded(stored);
  }, []);

  // Bulk actions move the DEFAULT and drop the per-strip deviations, so they
  // apply to unmounted strips and to nodes not yet scrolled to. They also RECORD
  // the choice: it is the same intent, and the user expressing it once should not
  // have to repeat it on the next run, epoch or visit.
  const expandAll = useCallback(() => {
    setDefaultExpanded(true);
    writeFileStripPreference(true);
    setOverrides((prev) => (prev.size === 0 ? prev : new Map()));
  }, []);

  const collapseAll = useCallback(() => {
    setDefaultExpanded(false);
    writeFileStripPreference(false);
    setOverrides((prev) => (prev.size === 0 ? prev : new Map()));
  }, []);

  const previousResetKeyRef = useRef(resetKey);
  useEffect(() => {
    if (previousResetKeyRef.current === resetKey) return;
    previousResetKeyRef.current = resetKey;
    // Rebuild from the live strips rather than emptying: the mounted ones are still
    // on screen, and a count that dipped through 0 would make the toolbar briefly
    // show the STORED preference instead of what those strips are actually doing.
    setKnownIds(new Set(mountedRef.current));
    // Only the per-strip deviations are dropped: they were about the files that just
    // went away. `defaultExpanded` is left ALONE - it already holds the preference,
    // and every path that changes it persists it.
    //
    // Re-reading storage here instead would look equivalent and is not. Two canvases
    // can be mounted at once (the builder plus a sub-workflow side panel), so a
    // choice made in one would jump into the other at its next epoch, minutes later,
    // with nothing on screen to explain it. And when the write silently failed
    // (Safari private mode, quota), the re-read would return "nothing stored" and
    // fold everything back on the next epoch - resurrecting, for those users only,
    // the exact bug this feature removes.
    setOverrides(new Map());
  }, [resetKey]);

  const isExpanded = useCallback(
    (stripId: string) => overrides.get(stripId) ?? defaultExpanded,
    [overrides, defaultExpanded],
  );

  const expandedCount = useMemo(() => {
    let count = 0;
    knownIds.forEach((id) => {
      if (overrides.get(id) ?? defaultExpanded) count += 1;
    });
    return count;
  }, [knownIds, overrides, defaultExpanded]);

  const value = useMemo<FileStripExpansionContextValue>(
    () => ({
      stripCount: knownIds.size,
      defaultExpanded,
      expandedCount,
      registerStrip,
      unregisterStrip,
      isExpanded,
      setExpanded,
      expandAll,
      collapseAll,
    }),
    [knownIds, expandedCount, defaultExpanded, registerStrip, unregisterStrip, isExpanded, setExpanded, expandAll, collapseAll],
  );

  return <FileStripExpansionContext.Provider value={value}>{children}</FileStripExpansionContext.Provider>;
}

/**
 * The value, or {@code null} outside the provider.
 *
 * Deliberately nullable rather than a defaulted no-op object (the shape
 * {@code useWorkflowLayoutDirectionSafe} uses): a strip must be able to tell
 * "nobody is coordinating me" from "I am registered", because in the first case
 * it falls back to its OWN local expanded state and a silent no-op setter would
 * leave its pill permanently unclickable.
 *
 * The identity of the returned value changes whenever any strip expands, so a
 * consumer that only needs to act (register / unregister / set) should depend on
 * the individual callbacks, which are stable, rather than on the whole value.
 */
export function useFileStripExpansionSafe(): FileStripExpansionContextValue | null {
  return useContext(FileStripExpansionContext);
}
