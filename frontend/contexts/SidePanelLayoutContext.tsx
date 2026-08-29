'use client';

/**
 * SidePanelLayoutContext - layout state for WHERE the unified side panel docks.
 *
 * Orthogonal to SidePanelContext (which owns tabs/open state): this only holds the
 * dock POSITION so the layout, the header dock buttons, and the Settings selector
 * all agree. It is a purely-visual client preference (like the theme), so it lives
 * in localStorage - no backend round-trip.
 *
 * Three pieces of state:
 *  - `position` - the ACTIVE dock, driven by the header's two dock buttons:
 *      'right'       (default): the panel is a row sibling of the main content and
 *                    resizes by WIDTH (historical behavior).
 *      'bottom'      : the panel docks under the main content, content-width, and
 *                    resizes by HEIGHT. The main content shrinks vertically.
 *      'bottom-full' : same, but spanning the FULL viewport width (under the
 *                    sidebar too); the sidebar shrinks vertically above it.
 *      'floating'    : the panel is DETACHED - a movable, resizable card floating
 *                    over the app (position: fixed, so it takes no layout space and
 *                    the main content spans the full width). Desktop/tablet only:
 *                    below the mobile breakpoint the panel is already a full-screen
 *                    overlay, so SidePanel renders a stored 'floating' as a right
 *                    dock there instead. Never a `defaultPosition` - detaching is a
 *                    live action, not a way the app opens.
 *  - `defaultPosition` - the user PREFERENCE (Settings > Preferences) for where the
 *      panel opens BY DEFAULT across the app: 'right' | 'bottom'. It seeds `position`
 *      on every mount (a 'bottom' default resolves through `bottomMode` to its
 *      variant), so the whole app opens the panel where the user chose. The two
 *      header dock buttons still override the ACTIVE dock live for that session.
 *      When unset (never picked), we fall back to the last-used `position` so
 *      existing users keep their sticky behavior.
 *  - `bottomMode` - the user PREFERENCE (Settings > Preferences) for which bottom
 *      variant the header's bottom-dock button opens: 'bottom' | 'bottom-full'.
 *      Defaults to 'bottom-full'. Changing it while a bottom dock is active
 *      repositions the open panel immediately (WYSIWYG).
 *
 * ORG-AWARE: all values are scoped per active workspace (per-(user, org), like the
 * chat defaults). Each workspace remembers its own layout and switching the active
 * org re-hydrates the values - the layout in Org A never bleeds into Org B. The
 * active workspace comes from {@link useCurrentOrg} (localStorage `lc.activeOrg`);
 * the personal workspace (no org) uses the `personal` bucket.
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useCurrentOrg } from '@/lib/stores/current-org-store';

export type SidePanelPosition = 'right' | 'bottom' | 'bottom-full' | 'floating';
export type SidePanelBottomMode = 'bottom' | 'bottom-full';
/** The default-opening preference: only the two docks the user picks between. */
export type SidePanelDefaultPosition = 'right' | 'bottom';
/**
 * A real dock, i.e. every position except the detached one.
 *
 * Used for `lastDock`, whose whole job is to answer "where does re-attaching put
 * it back". Typing it as a plain `SidePanelPosition` let 'floating' through, and a
 * 'floating' in there turns the re-attach button into a silent no-op that no
 * compiler could see.
 */
export type SidePanelDock = Exclude<SidePanelPosition, 'floating'>;

export const DEFAULT_SIDE_PANEL_POSITION: SidePanelDock = 'right';
export const DEFAULT_SIDE_PANEL_BOTTOM_MODE: SidePanelBottomMode = 'bottom-full';
export const DEFAULT_SIDE_PANEL_DEFAULT_POSITION: SidePanelDefaultPosition = 'right';

interface SidePanelLayoutContextValue {
  position: SidePanelPosition;
  setPosition: (position: SidePanelPosition) => void;
  /**
   * The dock to return to when the panel is re-attached, i.e. the last position
   * that was not 'floating'. Session-only on purpose: it answers "put it back
   * where it was", which only means anything while the detach is on screen. A
   * reload lands on the stored position like any other preference.
   */
  lastDock: SidePanelDock;
  defaultPosition: SidePanelDefaultPosition;
  setDefaultPosition: (position: SidePanelDefaultPosition) => void;
  bottomMode: SidePanelBottomMode;
  setBottomMode: (mode: SidePanelBottomMode) => void;
}

const SidePanelLayoutContext = createContext<SidePanelLayoutContextValue | null>(null);

const STORAGE_PREFIX = 'lc.sidePanel.position';
const DEFAULT_POSITION_STORAGE_PREFIX = 'lc.sidePanel.defaultPosition';
const BOTTOM_MODE_STORAGE_PREFIX = 'lc.sidePanel.bottomMode';
/**
 * The dock a detached panel goes back to, in its own bucket.
 *
 * Needed because detaching overwrites the position bucket with 'floating', which
 * erases the dock the panel came from. Without this, a user whose habitual dock is
 * the bottom one detaches, reloads, presses re-attach, and lands on a right dock
 * they never chose.
 */
const LAST_DOCK_STORAGE_PREFIX = 'lc.sidePanel.lastDock';

export function isSidePanelPosition(value: string | null | undefined): value is SidePanelPosition {
  return value === 'right' || value === 'bottom' || value === 'bottom-full' || value === 'floating';
}

export function isSidePanelBottomMode(value: string | null | undefined): value is SidePanelBottomMode {
  return value === 'bottom' || value === 'bottom-full';
}

export function isSidePanelDefaultPosition(value: string | null | undefined): value is SidePanelDefaultPosition {
  return value === 'right' || value === 'bottom';
}

/** localStorage key for a given workspace (null org = personal workspace). */
function storageKey(orgId: string | null | undefined): string {
  return `${STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

function defaultPositionStorageKey(orgId: string | null | undefined): string {
  return `${DEFAULT_POSITION_STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

function bottomModeStorageKey(orgId: string | null | undefined): string {
  return `${BOTTOM_MODE_STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

function lastDockStorageKey(orgId: string | null | undefined): string {
  return `${LAST_DOCK_STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

/** The remembered return dock, or null when the user has never detached. */
function readStoredLastDock(orgId: string | null | undefined): SidePanelDock | null {
  if (typeof window === 'undefined') return null;
  try {
    const saved = window.localStorage.getItem(lastDockStorageKey(orgId));
    return isSidePanelPosition(saved) && saved !== 'floating' ? saved : null;
  } catch {
    return null;
  }
}

/**
 * The stored default-opening preference for a workspace, or `null` when the user
 * has never picked one (so callers can fall back to the last-used position).
 */
function readStoredDefaultPosition(orgId: string | null | undefined): SidePanelDefaultPosition | null {
  if (typeof window === 'undefined') return null;
  try {
    const saved = window.localStorage.getItem(defaultPositionStorageKey(orgId));
    return isSidePanelDefaultPosition(saved) ? saved : null;
  } catch {
    return null;
  }
}

function readStoredBottomMode(orgId: string | null | undefined): SidePanelBottomMode {
  if (typeof window === 'undefined') return DEFAULT_SIDE_PANEL_BOTTOM_MODE;
  try {
    const saved = window.localStorage.getItem(bottomModeStorageKey(orgId));
    if (isSidePanelBottomMode(saved)) return saved;
    // Legacy seed: before bottomMode existed, the dock position select stored the
    // chosen bottom variant in the position bucket. Honor it so a user who picked
    // 'bottom' (content width) keeps that variant on the new bottom button.
    const savedPosition = window.localStorage.getItem(storageKey(orgId));
    if (isSidePanelBottomMode(savedPosition)) return savedPosition;
    return DEFAULT_SIDE_PANEL_BOTTOM_MODE;
  } catch {
    return DEFAULT_SIDE_PANEL_BOTTOM_MODE;
  }
}

/** Resolve a default-opening preference to a concrete active dock. */
function resolveDefaultToPosition(
  defaultPosition: SidePanelDefaultPosition,
  bottomMode: SidePanelBottomMode,
): SidePanelDock {
  return defaultPosition === 'right' ? 'right' : bottomMode;
}

/**
 * The active dock the panel should open with on mount for a workspace:
 *  1. a stored DETACH outranks everything - see below;
 *  2. if the user picked a default-opening preference, honor it (a 'bottom' default
 *     resolves through the chosen bottomMode variant);
 *  3. otherwise fall back to the last-used stored position (backward-compatible
 *     sticky behavior for users who never set a default);
 *  4. otherwise the global default ('right').
 *
 * Rule 1 exists because the default-opening preference answers "which DOCK does the
 * panel open on", and a detached panel is on no dock: resolving the preference over
 * it would silently re-dock the panel on reload for every user who ever set that
 * preference, and leave it detached for everyone else. Detaching is the more recent
 * and more specific act, so it wins; the preference takes over again the moment the
 * user re-attaches, which writes a real dock back into the bucket.
 */
function readInitialPosition(
  orgId: string | null | undefined,
  bottomMode: SidePanelBottomMode,
): SidePanelPosition {
  if (typeof window === 'undefined') return DEFAULT_SIDE_PANEL_POSITION;
  let saved: string | null = null;
  try {
    saved = window.localStorage.getItem(storageKey(orgId));
  } catch {
    return DEFAULT_SIDE_PANEL_POSITION;
  }
  if (saved === 'floating') return 'floating';
  const preferred = readStoredDefaultPosition(orgId);
  if (preferred) return resolveDefaultToPosition(preferred, bottomMode);
  return isSidePanelPosition(saved) ? saved : DEFAULT_SIDE_PANEL_POSITION;
}

export function SidePanelLayoutProvider({ children }: { children: React.ReactNode }) {
  // Active workspace - drives which per-org value we read/write.
  const { currentOrgId } = useCurrentOrg();

  // Start with the defaults so SSR and the first client render agree (no hydration
  // mismatch); the effect below restores the saved values for the active workspace.
  const [position, setPositionState] = useState<SidePanelPosition>(DEFAULT_SIDE_PANEL_POSITION);
  const [defaultPosition, setDefaultPositionState] = useState<SidePanelDefaultPosition>(DEFAULT_SIDE_PANEL_DEFAULT_POSITION);
  const [bottomMode, setBottomModeState] = useState<SidePanelBottomMode>(DEFAULT_SIDE_PANEL_BOTTOM_MODE);
  // Where a detached panel goes back to. Tracks every non-floating position so
  // re-attaching restores the dock the user was actually on, not their default.
  const [lastDock, setLastDock] = useState<SidePanelDock>(DEFAULT_SIDE_PANEL_POSITION);

  // Re-hydrate whenever the active workspace changes (initial mount included, once
  // currentOrgId resolves post-hydration). Deliberately an effect, not a render-time
  // derivation: reading localStorage during render would diverge from the SSR default
  // and trip a hydration mismatch (same reason ThemeProvider restores in an effect).
  useEffect(() => {
    const mode = readStoredBottomMode(currentOrgId);
    const preferredDefault = readStoredDefaultPosition(currentOrgId);
    // Restoring persisted preferences into state on mount / org switch is the one
    // legitimate synchronous setState-in-effect here (SSR-safe hydration, like
    // ThemeProvider); reading localStorage during render would trip a mismatch.
    /* eslint-disable react-hooks/set-state-in-effect */
    setBottomModeState(mode);
    setDefaultPositionState(preferredDefault ?? DEFAULT_SIDE_PANEL_DEFAULT_POSITION);
    const initial = readInitialPosition(currentOrgId, mode);
    setPositionState(initial);
    // A restored detach reads the dock it was detached FROM, and only falls back to
    // the default-opening preference for an install that has never detached.
    setLastDock(initial === 'floating'
      ? readStoredLastDock(currentOrgId)
        ?? resolveDefaultToPosition(preferredDefault ?? DEFAULT_SIDE_PANEL_DEFAULT_POSITION, mode)
      : initial);
    /* eslint-enable react-hooks/set-state-in-effect */
    // Make the legacy seed STICKY: if the bottomMode bucket is empty but a legacy
    // bottom position is stored, persist the seed now. Otherwise a later dock
    // change (position bucket overwritten to 'right') would silently revert the
    // migrated preference to the default on the next mount.
    try {
      const storedMode = window.localStorage.getItem(bottomModeStorageKey(currentOrgId));
      if (!isSidePanelBottomMode(storedMode)) {
        const legacyPosition = window.localStorage.getItem(storageKey(currentOrgId));
        if (isSidePanelBottomMode(legacyPosition)) {
          window.localStorage.setItem(bottomModeStorageKey(currentOrgId), legacyPosition);
        }
      }
    } catch {
      /* localStorage unavailable - the in-memory seed still applies this session */
    }
  }, [currentOrgId]);

  const setPosition = useCallback((next: SidePanelPosition) => {
    setPositionState(next);
    if (next !== 'floating') setLastDock(next);
    try {
      window.localStorage.setItem(storageKey(currentOrgId), next);
      // Persisted separately, because the line above overwrites the dock with
      // 'floating' on a detach and there would be nothing left to come back to.
      if (next !== 'floating') window.localStorage.setItem(lastDockStorageKey(currentOrgId), next);
    } catch {
      /* localStorage unavailable (private mode / SSR) - keep the in-memory value */
    }
  }, [currentOrgId]);

  const setDefaultPosition = useCallback((next: SidePanelDefaultPosition) => {
    setDefaultPositionState(next);
    try {
      window.localStorage.setItem(defaultPositionStorageKey(currentOrgId), next);
    } catch {
      /* localStorage unavailable - keep the in-memory value */
    }
    // WYSIWYG: apply the newly chosen default to the ACTIVE dock right away so the
    // change is visible without waiting for the next open (a 'bottom' default lands
    // on the current bottomMode variant).
    //
    // This DOES end a detach, unlike setBottomMode above, and the asymmetry is the
    // point: picking a default dock is the user naming where the panel belongs, so
    // showing them that dock is the answer. Picking a bottom VARIANT only refines a
    // dock they may not currently be on, and re-docking on it would undo a detach
    // they never mentioned.
    setPosition(resolveDefaultToPosition(next, bottomMode));
  }, [currentOrgId, bottomMode, setPosition]);

  const setBottomMode = useCallback((next: SidePanelBottomMode) => {
    setBottomModeState(next);
    try {
      window.localStorage.setItem(bottomModeStorageKey(currentOrgId), next);
    } catch {
      /* localStorage unavailable - keep the in-memory value */
    }
    // WYSIWYG: if a bottom dock is currently active, reposition it to the newly
    // chosen variant right away. Tested against the bottom variants explicitly,
    // not against "not right": a DETACHED panel is neither, and re-docking it
    // because a preference changed elsewhere would undo the user's detach.
    if ((position === 'bottom' || position === 'bottom-full') && position !== next) {
      setPosition(next);
    }
    // Deliberately NOTHING for a detached panel, not even to keep `lastDock` in
    // step with the new variant. `lastDock` is what the app shell arranges itself
    // around while the panel floats, and 'bottom' and 'bottom-full' are mounted in
    // different places, so rewriting it mid-detach tears the panel subtree down
    // and takes a running canvas / SSE stream / interface iframe with it - the one
    // thing detaching is built never to do. Re-attaching resolves the CURRENT
    // variant instead (see the panel's detach toggle), so the preference is still
    // honoured, just at the moment it can be applied safely.
  }, [currentOrgId, position, setPosition]);

  const value = useMemo<SidePanelLayoutContextValue>(
    () => ({ position, setPosition, lastDock, defaultPosition, setDefaultPosition, bottomMode, setBottomMode }),
    [position, setPosition, lastDock, defaultPosition, setDefaultPosition, bottomMode, setBottomMode],
  );

  return (
    <SidePanelLayoutContext.Provider value={value}>
      {children}
    </SidePanelLayoutContext.Provider>
  );
}

/** Throws if used outside SidePanelLayoutProvider. */
export function useSidePanelLayout(): SidePanelLayoutContextValue {
  const ctx = useContext(SidePanelLayoutContext);
  if (!ctx) throw new Error('useSidePanelLayout must be used within SidePanelLayoutProvider');
  return ctx;
}

/**
 * Safe variant - returns the default 'right' layout (and no-op setters) when used
 * outside a provider (e.g. shared-conversation embeds, which always dock right).
 */
export function useSidePanelLayoutSafe(): SidePanelLayoutContextValue {
  const ctx = useContext(SidePanelLayoutContext);
  return ctx ?? {
    position: DEFAULT_SIDE_PANEL_POSITION,
    setPosition: () => {},
    lastDock: DEFAULT_SIDE_PANEL_POSITION,
    defaultPosition: DEFAULT_SIDE_PANEL_DEFAULT_POSITION,
    setDefaultPosition: () => {},
    bottomMode: DEFAULT_SIDE_PANEL_BOTTOM_MODE,
    setBottomMode: () => {},
  };
}
