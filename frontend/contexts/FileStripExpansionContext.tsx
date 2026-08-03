'use client';

/**
 * Canvas-wide expand/collapse state for the run-mode file strips.
 *
 * Each file-producing node hangs a {@code FileResultStrip} under its box in run
 * mode, and each of those owns whether its inline preview is folded (a one-line
 * pill) or unfolded (a real image/video/audio/pdf preview). On a run with a
 * dozen file nodes, seeing every result meant clicking every pill, one by one.
 *
 * This context lifts that per-strip boolean to the canvas so the bottom toolbar
 * can flip them all at once, and so the toolbar can HIDE its control entirely
 * when the canvas carries no strip (edit mode, a run that produced no file).
 * Two facts drive that: {@link FileStripExpansionContextValue.stripCount} - how
 * many strips are mounted - and {@link FileStripExpansionContextValue.expandedCount}
 * - how many of them are open.
 *
 * The strips register themselves on mount rather than being enumerated from the
 * node list: whether a node ends up showing a strip is decided deep inside
 * {@code FileNodePreview} (run mode + completed + a FileRef actually resolved
 * from the step output), and re-deriving that condition in the toolbar would
 * duplicate it and drift from it.
 *
 * A consumer must read it through {@link useFileStripExpansionSafe}, which
 * returns {@code null} outside the provider: the strip is also mounted by
 * surfaces that carry no canvas chrome (the marketplace preview, isolated
 * tests), and it keeps its own local state there.
 */

import React, { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';

export interface FileStripExpansionContextValue {
  /** File strips currently mounted on the canvas. 0 means the toolbar control has nothing to act on. */
  stripCount: number;
  /** How many of those strips are currently expanded. */
  expandedCount: number;
  /** Announce a mounted strip. Idempotent: re-registering the same id is a no-op. */
  registerStrip: (stripId: string) => void;
  /** Drop a strip that left the canvas, from the registry AND from the expanded set. */
  unregisterStrip: (stripId: string) => void;
  isExpanded: (stripId: string) => boolean;
  setExpanded: (stripId: string, expanded: boolean) => void;
  expandAll: () => void;
  collapseAll: () => void;
}

const FileStripExpansionContext = createContext<FileStripExpansionContextValue | null>(null);

export function FileStripExpansionProvider({ children }: { children: React.ReactNode }) {
  // Ref mirror of the registry, not state: `expandAll` needs the CURRENT set of
  // strips at click time, and reading it from a state closure would expand the
  // set as it stood when the callback was created.
  const registeredRef = useRef<Set<string>>(new Set());
  // The size is mirrored into state because the toolbar renders off it.
  const [stripCount, setStripCount] = useState(0);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(() => new Set());

  const registerStrip = useCallback((stripId: string) => {
    if (registeredRef.current.has(stripId)) return;
    registeredRef.current.add(stripId);
    setStripCount(registeredRef.current.size);
  }, []);

  const unregisterStrip = useCallback((stripId: string) => {
    if (!registeredRef.current.delete(stripId)) return;
    setStripCount(registeredRef.current.size);
    // A strip that left the canvas must stop counting as expanded, otherwise the
    // toolbar keeps offering "collapse all" with nothing left to collapse - and,
    // worse, a node whose strip comes back (a rerun) would come back expanded.
    setExpandedIds((prev) => {
      if (!prev.has(stripId)) return prev;
      const next = new Set(prev);
      next.delete(stripId);
      return next;
    });
  }, []);

  const setExpanded = useCallback((stripId: string, expanded: boolean) => {
    setExpandedIds((prev) => {
      // Same-value writes return the previous Set so React can bail out of the
      // re-render: every strip subscribes to this context.
      if (prev.has(stripId) === expanded) return prev;
      const next = new Set(prev);
      if (expanded) next.add(stripId);
      else next.delete(stripId);
      return next;
    });
  }, []);

  const expandAll = useCallback(() => {
    setExpandedIds(new Set(registeredRef.current));
  }, []);

  const collapseAll = useCallback(() => {
    setExpandedIds((prev) => (prev.size === 0 ? prev : new Set()));
  }, []);

  const value = useMemo<FileStripExpansionContextValue>(
    () => ({
      stripCount,
      expandedCount: expandedIds.size,
      registerStrip,
      unregisterStrip,
      isExpanded: (stripId: string) => expandedIds.has(stripId),
      setExpanded,
      expandAll,
      collapseAll,
    }),
    [stripCount, expandedIds, registerStrip, unregisterStrip, setExpanded, expandAll, collapseAll],
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
