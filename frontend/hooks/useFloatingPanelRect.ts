import { useCallback, useEffect, useRef, useState } from 'react';
import { useIsomorphicLayoutEffect } from '@/lib/hooks/useIsomorphicLayoutEffect';
import { useCurrentOrg } from '@/lib/stores/current-org-store';

/**
 * Geometry of the DETACHED side panel: a movable, resizable card floating over
 * the app.
 *
 * Separate from {@link useMouseResize}, which resizes a panel welded to a
 * viewport edge (one axis, size derived from the cursor's distance to that
 * edge). A detached window has no edge to derive anything from: it owns a full
 * rect, and both a move and a resize are deltas applied to the rect the drag
 * started on.
 *
 * POINTER events, not mouse events: the detach is offered on tablets too, where
 * a mouse-only drag would be dead. Pointer capture also keeps the drag alive
 * over an iframe (the interface previews the panel is full of), which is exactly
 * where a plain listener loses the stream.
 */

export interface FloatingRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** The eight resize handles, named after the edge or corner they sit on. */
export type FloatingResizeMode = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw';
/** Which handle is being dragged. 'move' translates the rect, the rest resize it. */
export type FloatingDragMode = 'move' | FloatingResizeMode;

/**
 * Which sides each handle pulls.
 *
 * A table rather than substring tests on the mode: 'move' contains an 'e', and a
 * resize that quietly believed it was pulling the east edge would be a silent
 * geometry bug rather than a type error.
 */
const RESIZE_SIDES: Record<FloatingResizeMode, { n?: true; s?: true; e?: true; w?: true }> = {
  n: { n: true },
  s: { s: true },
  e: { e: true },
  w: { w: true },
  ne: { n: true, e: true },
  nw: { n: true, w: true },
  se: { s: true, e: true },
  sw: { s: true, w: true },
};

/**
 * Per-workspace, like every other side-panel layout value (position, default
 * position, bottom mode). The subsystem's rule is that the layout in Org A never
 * bleeds into Org B; a global geometry would have been the one value that did,
 * handing a user Org A's window position while the dock itself differed.
 */
const STORAGE_PREFIX = 'lc.sidePanel.floatingRect';

function storageKey(orgId: string | null | undefined): string {
  return `${STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}
const MIN_WIDTH = 320;
const MIN_HEIGHT = 240;
/** Breathing room between the default window and the viewport edges. */
const MARGIN = 24;
/**
 * The app header bar's height, in px - `h-14` on the header row.
 *
 * Duplicated from Tailwind rather than imported because the header is a component,
 * not a constant; keeping it named (and derived below) is what makes a drift
 * visible instead of leaving a magic number nobody can check.
 */
export const APP_HEADER_HEIGHT = 56;
/**
 * Where the default window starts vertically: below the app header.
 *
 * A detached window paints over the whole app, header included, and the user can
 * drag it anywhere - but it must not OPEN on top of the app chrome, or the first
 * thing a detach does is bury the very buttons that dock it back.
 */
const APP_HEADER_CLEARANCE = APP_HEADER_HEIGHT + MARGIN;

function isRect(value: unknown): value is FloatingRect {
  if (!value || typeof value !== 'object') return false;
  const r = value as Record<string, unknown>;
  return ['left', 'top', 'width', 'height'].every(
    (k) => typeof r[k] === 'number' && Number.isFinite(r[k] as number),
  );
}

/** A window sized like the default right dock, parked top-right under the header. */
export function defaultFloatingRect(): FloatingRect {
  const vw = typeof window === 'undefined' ? 1280 : window.innerWidth;
  const vh = typeof window === 'undefined' ? 800 : window.innerHeight;
  const width = Math.max(MIN_WIDTH, Math.min(640, Math.floor(vw * 0.35)));
  const height = Math.max(MIN_HEIGHT, Math.min(Math.floor(vh * 0.7), vh - APP_HEADER_CLEARANCE - MARGIN));
  return { left: Math.max(MARGIN, vw - width - MARGIN), top: APP_HEADER_CLEARANCE, width, height };
}

/**
 * Keep the whole window on screen.
 *
 * Full containment rather than "a sliver must stay visible": the panel holds the
 * only close and re-dock controls, and a window dragged mostly off screen takes
 * them with it. Size is clamped BEFORE position, so a viewport that shrank below
 * the stored size still yields a valid origin.
 */
export function clampFloatingRect(rect: FloatingRect, vw: number, vh: number): FloatingRect {
  const width = Math.max(Math.min(MIN_WIDTH, vw), Math.min(rect.width, vw));
  const height = Math.max(Math.min(MIN_HEIGHT, vh), Math.min(rect.height, vh));
  return {
    width,
    height,
    left: Math.max(0, Math.min(rect.left, vw - width)),
    top: Math.max(0, Math.min(rect.top, vh - height)),
  };
}

/**
 * Grow/shrink the rect from one of its eight handles.
 *
 * Two different rules, because the edges are not symmetric. The south and east
 * edges grow AWAY from a fixed origin, so only the SIZE is capped, at the room the
 * viewport has past that origin: without the cap such a drag stays legal only
 * because {@link clampFloatingRect} pulls the opposite side in, and the card
 * slides out from under the cursor mid-gesture. The north and west edges move the
 * origin, so the DELTA is clamped and both values are derived from it.
 *
 * Both rules enforce the minimum themselves rather than leaning on the caller's
 * clamp: a helper that returns an invalid rect and trusts someone downstream to
 * fix it is a trap for the next caller, and was one for the collapsed window.
 * Exported for that reason - the contract is the function's, not its caller's, so
 * it is pinned directly rather than through a path that would repair it.
 */
export function resizedFromOrigin(
  origin: FloatingRect,
  mode: FloatingResizeMode,
  dx: number,
  dy: number,
  vw: number,
  vh: number,
): FloatingRect {
  const sides = RESIZE_SIDES[mode];
  let { left, top, width, height } = origin;

  // Edges that grow AWAY from the origin: the origin is fixed, so only the size
  // is capped, at the room the viewport still has past it.
  if (sides.e) width = Math.max(MIN_WIDTH, Math.min(origin.width + dx, vw - origin.left));
  if (sides.s) height = Math.max(MIN_HEIGHT, Math.min(origin.height + dy, vh - origin.top));

  // Edges that grow TOWARDS the origin move it, so the delta itself is clamped
  // and both values are derived from the clamped delta. Deriving them separately
  // is what makes a window jump the instant one of the two hits its limit while
  // the other keeps following the cursor.
  if (sides.w) {
    const d = Math.max(-origin.left, Math.min(dx, origin.width - MIN_WIDTH));
    left = origin.left + d;
    width = origin.width - d;
  }
  if (sides.n) {
    const d = Math.max(-origin.top, Math.min(dy, origin.height - MIN_HEIGHT));
    top = origin.top + d;
    height = origin.height - d;
  }
  return { left, top, width, height };
}

function readStoredRect(orgId: string | null | undefined): FloatingRect | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(storageKey(orgId));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    return isRect(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/** The CSS cursor each handle owns, shared by the handle and the drag overlay. */
export const FLOATING_DRAG_CURSOR: Record<FloatingDragMode, string> = {
  move: 'grabbing',
  n: 'ns-resize',
  s: 'ns-resize',
  e: 'ew-resize',
  w: 'ew-resize',
  ne: 'nesw-resize',
  sw: 'nesw-resize',
  nw: 'nwse-resize',
  se: 'nwse-resize',
};

export interface UseFloatingPanelRect {
  rect: FloatingRect;
  /**
   * The handle currently being dragged, or null when idle.
   *
   * Callers need the MODE, not just "a drag is happening": the full-viewport
   * overlay that keeps the gesture alive over an iframe also owns the cursor for
   * its whole duration, so a single "dragging" flag painted a closed hand over a
   * corner resize.
   */
  dragMode: FloatingDragMode | null;
  /**
   * The viewport the rect is currently clamped against.
   *
   * Exposed because the caller lays the resize grips OUTSIDE the card, and a
   * window dragged flush against an edge (which the clamp actively produces) would
   * otherwise push its grip band off screen and leave a 1px target behind.
   */
  viewport: { width: number; height: number };
  /** Attach to a handle's `onPointerDown`. */
  startDrag: (mode: FloatingDragMode) => (e: React.PointerEvent<HTMLElement>) => void;
  /**
   * Nudge the window by keyboard - `resize` grows/shrinks instead of moving.
   *
   * A resize is ignored while a painted override is active: the caller is drawing
   * a fixed box, so the gesture cannot change anything on screen, and running it
   * anyway would resize the window the user cannot currently see.
   */
  nudge: (dx: number, dy: number, resize?: false | FloatingResizeMode) => void;
}

/**
 * @param enabled whether the panel is currently detached. While false the hook
 *   holds its rect but installs nothing, so a docked panel pays no listeners.
 * @param renderSize the box the caller actually paints, when that differs from
 *   the rect - a collapsed window renders as a small strip while keeping the rect
 *   it will expand back to. The ORIGIN is clamped against this, so the strip can
 *   be parked in a corner; without it the clamp reserves room for a window that is
 *   not on screen and the strip stops short of the edge by its own expanded size.
 */
export function useFloatingPanelRect(
  enabled: boolean,
  renderSize?: { width: number; height: number },
): UseFloatingPanelRect {
  // The initializer reads the viewport, so server and client do NOT agree on it.
  // That is safe only because the floating branch is never rendered during
  // hydration: `position` starts at the stored-independent default, so `enabled`
  // is false and no caller puts this rect in the DOM on the first client render.
  // Anything that makes the dock position hydrate synchronously must revisit this.
  // The stored rect is restored in an effect, like every other localStorage-backed
  // preference in the app.
  const [rect, setRect] = useState<FloatingRect>(defaultFloatingRect);
  const [dragMode, setDragMode] = useState<FloatingDragMode | null>(null);
  const [viewport, setViewport] = useState(() => ({
    width: typeof window === 'undefined' ? 1280 : window.innerWidth,
    height: typeof window === 'undefined' ? 800 : window.innerHeight,
  }));
  const { currentOrgId } = useCurrentOrg();

  /**
   * The rect as of the LAST call, not as of the last commit.
   *
   * Updated in the same statement as `setRect` rather than in an effect, because
   * a drag reads it between frames (at pointerdown, and again when it settles) and
   * an effect-synced copy is one commit behind at exactly those moments.
   */
  const rectRef = useRef(rect);
  const applyRect = useCallback((next: FloatingRect) => {
    rectRef.current = next;
    setRect(next);
  }, []);

  /**
   * The painted box, read by VALUE.
   *
   * Not through a ref: a ref written in an effect made correctness depend on this
   * hook's effects being declared in a particular order (the ref had to be current
   * before the re-clamp effect read it), which nothing enforced and a reorder would
   * silently break. Closing over the two numbers instead means `clampForRender`'s
   * own identity changes with them, and every effect that depends on it re-runs for
   * free. By value and not by object identity, because a caller passing an object
   * literal would otherwise rebuild it every render and loop.
   */
  const paintedWidth = renderSize?.width;
  const paintedHeight = renderSize?.height;
  /**
   * Clamp a rect whose ORIGIN must fit the painted box, keeping the rect's own
   * size. Identical to `clampFloatingRect` when nothing overrides the size.
   */
  const clampForRender = useCallback((next: FloatingRect, vw: number, vh: number): FloatingRect => {
    // The SIZE is clamped unconditionally. `clampFloatingRect` does three jobs -
    // minimum floor, viewport cap, origin bound - and only the third one may be
    // relaxed for a painted override. Skipping the whole call let a keyboard
    // resize on the collapsed row drive the rect negative and persist it, and let
    // a viewport shrink leave a window larger than the screen.
    const sized = clampFloatingRect(next, vw, vh);
    if (paintedWidth == null || paintedHeight == null) return sized;
    // Only the origin follows the painted box: it is deliberately smaller than a
    // window may be, so bounding it through the minimum floor would reserve room
    // for a size nothing on screen occupies.
    const w = Math.min(paintedWidth, vw);
    const h = Math.min(paintedHeight, vh);
    return {
      ...sized,
      left: Math.max(0, Math.min(next.left, vw - w)),
      top: Math.max(0, Math.min(next.top, vh - h)),
    };
  }, [paintedWidth, paintedHeight]);

  /**
   * Write the geometry the user CHOSE.
   *
   * Called explicitly at the end of a drag and after a keyboard nudge, never from
   * an effect watching `rect`. An effect would also persist the clamp a viewport
   * shrink applies, so minimising the browser window once would overwrite the size
   * the user had picked and restoring the window would not bring it back.
   */
  const persist = useCallback((next: FloatingRect) => {
    if (!enabled) return;
    try {
      // Clamped on the way out: while collapsed the origin is legitimately past
      // what a full window allows, and storing that raw leaves the remembered
      // geometry disagreeing with what the user sees the moment they expand.
      const stored = clampFloatingRect(next, window.innerWidth, window.innerHeight);
      window.localStorage.setItem(storageKey(currentOrgId), JSON.stringify(stored));
    } catch {
      /* localStorage unavailable (private mode) - the in-memory rect still applies */
    }
  }, [enabled, currentOrgId]);
  /**
   * Teardown of the drag currently in flight, so it can be drained on unmount.
   *
   * Without it a component that unmounts mid-drag (a route change, a locale
   * switch) never reaches pointerup, so `document.body.style.userSelect` stays
   * 'none' and text selection is dead for the whole document until a full reload.
   */
  const activeCleanupRef = useRef<(() => void) | null>(null);
  useEffect(() => () => { activeCleanupRef.current?.(); }, []);

  /**
   * Restore the stored geometry.
   *
   * Re-run on every workspace switch (the bucket it reads from changes with the
   * org) AND whenever the panel is detached, so a window with nothing stored is
   * laid out against the viewport as it is NOW: a page loaded narrow and later
   * widened would otherwise open its first detached window using the width it had
   * at mount, and the clamp only corrects the shrink direction.
   *
   * Deliberately NOT gated on `enabled`: reading once at mount is what keeps the
   * first detach from painting a default rect for one frame before the stored one
   * lands. The cost is one localStorage read on surfaces that never detach.
   */
  useEffect(() => {
    const stored = readStoredRect(currentOrgId);
    // Restoring a persisted preference on mount is the one legitimate synchronous
    // setState-in-effect here (same reason as SidePanelLayoutContext / ThemeProvider):
    // reading localStorage during render would diverge from SSR and trip a mismatch.
    //
    // The DEFAULT is clamped too, not only the stored rect: the default is computed
    // from constants, so on a short viewport (roughly under 320px tall, reachable on
    // a resized desktop window since detach is gated on WIDTH) it hangs off the
    // bottom, taking the window's only close and re-dock controls with it. Nothing
    // corrects that until the next resize event, because the re-clamp layout effect
    // below runs BEFORE this passive effect in the same commit.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    applyRect(clampFloatingRect(stored ?? defaultFloatingRect(), window.innerWidth, window.innerHeight));
  }, [currentOrgId, enabled, applyRect]);

  /**
   * A viewport that shrank must not strand the window off screen - and neither
   * must the collapse/expand flip.
   *
   * A LAYOUT effect (through the repo's SSR-safe alias): on expand the card is
   * painted at its full size from the
   * strip's relaxed origin, so a plain effect lets one frame land mostly off the
   * bottom-right corner before pulling it back.
   */
  useIsomorphicLayoutEffect(() => {
    if (!enabled) return;
    const onResize = () => {
      setViewport({ width: window.innerWidth, height: window.innerHeight });
      applyRect(clampForRender(rectRef.current, window.innerWidth, window.innerHeight));
    };
    window.addEventListener('resize', onResize);
    onResize();
    return () => window.removeEventListener('resize', onResize);
    // The painted box is in the deps so the rect is re-clamped on the collapse and
    // expand flips too. Without it a strip parked in a corner - which the override
    // exists to allow - expands back to its full size straight off the screen,
    // taking the window's only close and re-dock controls with it.
    //
    // `clampForRender` carries the painted box, so it is the only dep needed here.
  }, [enabled, applyRect, clampForRender]);

  /**
   * A drag cannot outlive the detached state.
   *
   * Crossing the mobile breakpoint mid-drag (rotating a tablet, snapping the
   * window) re-docks the panel and unmounts its chrome, but the pointer is still
   * down: without this the full-viewport drag overlay stays up, unclickable, until
   * a pointerup nobody is going to deliver to a handle that no longer exists.
   */
  useEffect(() => {
    if (!enabled) activeCleanupRef.current?.();
  }, [enabled]);

  const startDrag = useCallback((mode: FloatingDragMode) => (e: React.PointerEvent<HTMLElement>) => {
    // Primary button only - a right-click on the title bar must open the context
    // menu, not start a drag that never gets its pointerup.
    if (e.button !== 0) return;
    // Suppressed even on the ignored path below: the point of preventDefault is to
    // stop the native text selection a press-and-drag starts, and a press that is
    // ignored for geometry still starts one.
    e.preventDefault();
    // One drag at a time. A second one would capture the FIRST one's already
    // neutralised `userSelect` as the value to restore, so whichever ends last
    // leaves the document unselectable.
    if (activeCleanupRef.current) return;
    const handle = e.currentTarget;
    const pointerId = e.pointerId;
    const startX = e.clientX;
    const startY = e.clientY;
    const origin = rectRef.current;
    // Best-effort: the move/up listeners hang off `window`, so a refused capture
    // costs nothing for the DRAG. It is not free for anything that also needs a
    // `click` out of the same press, which is why the collapsed row resolves its
    // tap on the window's pointerup rather than on a click event.
    try { handle.setPointerCapture(pointerId); } catch { /* refused: see above */ }
    setDragMode(mode);

    // One AbortController for every listener of this drag, so the teardown does
    // not have to name them - and so `cleanup` closes over nothing declared after
    // it, which is what a mutual handler/teardown pair forces.
    const controller = new AbortController();
    const { signal } = controller;
    const prevUserSelect = document.body.style.userSelect;
    document.body.style.userSelect = 'none';

    // Idempotent: pointerup and blur can both land, and the unmount teardown may
    // run after either. A local flag rather than a self-reference, which reads as
    // a missing memo dependency.
    let tornDown = false;
    const cleanup = () => {
      if (tornDown) return;
      tornDown = true;
      activeCleanupRef.current = null;
      controller.abort();
      document.body.style.userSelect = prevUserSelect;
      try { handle.releasePointerCapture(pointerId); } catch { /* already released */ }
      setDragMode(null);
      // One write per drag, carrying where it actually settled.
      persist(rectRef.current);
    };
    activeCleanupRef.current = cleanup;

    const onMove = (ev: PointerEvent) => {
      if (ev.pointerId !== pointerId) return;
      const dx = ev.clientX - startX;
      const dy = ev.clientY - startY;
      // Always applied to the rect the drag STARTED on, never to the previous
      // frame: accumulating per-frame deltas makes every clamp permanent, so a
      // window pushed against an edge no longer follows the cursor back.
      const next: FloatingRect = mode === 'move'
        ? { ...origin, left: origin.left + dx, top: origin.top + dy }
        : resizedFromOrigin(origin, mode, dx, dy, window.innerWidth, window.innerHeight);
      applyRect(clampForRender(next, window.innerWidth, window.innerHeight));
    };
    const stop = (ev: PointerEvent) => {
      if (ev.pointerId !== pointerId) return;
      cleanup();
    };

    window.addEventListener('pointermove', onMove, { signal });
    window.addEventListener('pointerup', stop, { signal });
    window.addEventListener('pointercancel', stop, { signal });
    // Alt-tabbing away never delivers the pointerup, which would leave the window
    // welded to the cursor.
    window.addEventListener('blur', cleanup, { signal });
  }, [applyRect, clampForRender, persist]);

  /**
   * Keyboard equivalent of a drag, so the window is not pointer-only.
   *
   * Reached through the same helpers as the pointer path, so a keyboard resize
   * stops at the viewport rather than pushing the origin, for exactly the reason a
   * pointer resize must - except that the resize verb is refused entirely while a
   * painted override is active (see below).
   *
   * `resize` names the ANCHOR, it is not a boolean: pinning it to 'se' gave the
   * pointer eight directions and the keyboard one, so the north and west edges were
   * reachable with a mouse and not otherwise. Moving the window and resizing it is
   * not the same gesture: it does not hold the opposite edge still.
   */
  const nudge = useCallback((dx: number, dy: number, resize: false | FloatingResizeMode = false) => {
    // A resize is refused outright while a painted override is active. The caller
    // is drawing a FIXED box, so the gesture can change nothing on screen - but it
    // would still run against the relaxed origin the override allows, whose east
    // and south caps are then measured from a `left`/`top` no full window may
    // have. One keypress on a corner-parked strip floored the window to the
    // minimum, in memory and in storage, with no visible feedback at all.
    if (resize && paintedWidth != null) return;
    const from = rectRef.current;
    const moved = resize
      ? resizedFromOrigin(from, resize, dx, dy, window.innerWidth, window.innerHeight)
      : { ...from, left: from.left + dx, top: from.top + dy };
    const next = clampForRender(moved, window.innerWidth, window.innerHeight);
    applyRect(next);
    persist(next);
  }, [applyRect, clampForRender, persist, paintedWidth]);

  return { rect, dragMode, viewport, startDrag, nudge };
}
