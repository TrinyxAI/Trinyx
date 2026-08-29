/**
 * @vitest-environment jsdom
 *
 * Geometry of the DETACHED side panel. The rules pinned here are the ones a user
 * notices the moment they break: the window never leaves the viewport (it carries
 * the only close and re-dock buttons), it never shrinks below a usable size, and a
 * drag is always measured from where it STARTED - not accumulated frame by frame,
 * which is what makes a clamped edge permanent.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';

import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import {
  APP_HEADER_HEIGHT,
  FLOATING_DRAG_CURSOR,
  clampFloatingRect,
  defaultFloatingRect,
  resizedFromOrigin,
  useFloatingPanelRect,
  type FloatingRect,
} from '@/hooks/useFloatingPanelRect';

/** Per-workspace bucket; these tests run in the personal workspace. */
const STORAGE_KEY = 'lc.sidePanel.floatingRect:personal';

function setViewport(width: number, height: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true, writable: true });
  Object.defineProperty(window, 'innerHeight', { value: height, configurable: true, writable: true });
}

function pointerEvent(type: string, x: number, y: number) {
  const ev = new Event(type) as Event & { pointerId: number; clientX: number; clientY: number };
  ev.pointerId = 7;
  ev.clientX = x;
  ev.clientY = y;
  return ev;
}

/** Drive a full pointer drag over a handle, as the DOM would. */
function drag(
  start: { x: number; y: number },
  end: { x: number; y: number },
  startDrag: (e: unknown) => void,
) {
  act(() => {
    startDrag({
      button: 0,
      pointerId: 7,
      clientX: start.x,
      clientY: start.y,
      currentTarget: document.createElement('div'),
      preventDefault: () => {},
    });
  });
  act(() => { window.dispatchEvent(pointerEvent('pointermove', end.x, end.y)); });
  act(() => { window.dispatchEvent(pointerEvent('pointerup', end.x, end.y)); });
}

beforeEach(() => {
  window.localStorage.clear();
  setViewport(1600, 900);
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

describe('clampFloatingRect', () => {
  it('keeps the whole window inside the viewport when it is dragged past an edge', () => {
    const r = clampFloatingRect({ left: 5000, top: 5000, width: 400, height: 300 }, 1600, 900);
    expect(r).toEqual({ left: 1200, top: 600, width: 400, height: 300 });
  });

  it('pins a negative origin back to the top-left corner', () => {
    const r = clampFloatingRect({ left: -300, top: -80, width: 400, height: 300 }, 1600, 900);
    expect(r.left).toBe(0);
    expect(r.top).toBe(0);
  });

  it('refuses to shrink below the minimum usable size', () => {
    const r = clampFloatingRect({ left: 0, top: 0, width: 40, height: 20 }, 1600, 900);
    expect(r.width).toBe(320);
    expect(r.height).toBe(240);
  });

  it('lets a viewport SMALLER than the minimum win, so the origin stays valid', () => {
    // A 200px-wide viewport cannot hold the 320px minimum. Capping to the viewport
    // (rather than holding the minimum) is what keeps `left` from going negative.
    const r = clampFloatingRect({ left: 0, top: 0, width: 800, height: 600 }, 200, 150);
    expect(r).toEqual({ left: 0, top: 0, width: 200, height: 150 });
  });

  it('caps to the viewport before the minimum, on a viewport narrower than the minimum', () => {
    // Where the two Math.mins actually interact: a sub-minimum rect on a
    // sub-minimum viewport. The viewport has to win, or `left` goes negative.
    const r = clampFloatingRect({ left: 0, top: 0, width: 40, height: 20 }, 200, 150);
    expect(r).toEqual({ left: 0, top: 0, width: 200, height: 150 });
  });

  it('caps a window larger than the viewport to the viewport', () => {
    const r = clampFloatingRect({ left: 100, top: 100, width: 4000, height: 4000 }, 1600, 900);
    expect(r).toEqual({ left: 0, top: 0, width: 1600, height: 900 });
  });
});

describe('resizedFromOrigin', () => {
  const origin = { left: 300, top: 200, width: 400, height: 300 };

  it('floors east and south itself, rather than leaning on the caller to repair it', () => {
    // The north and west branches clamp their own delta; east and south used to
    // return an invalid rect and trust whoever called them to fix it. That is a
    // trap for the next caller - and was one for the collapsed window, whose
    // clamp had been relaxed. Asserted on the helper, because every caller today
    // repairs it and so cannot tell the difference.
    expect(resizedFromOrigin(origin, 'e', -600, 0, 1600, 900).width).toBe(320);
    expect(resizedFromOrigin(origin, 's', 0, -600, 1600, 900).height).toBe(240);
    expect(resizedFromOrigin(origin, 'se', -600, -600, 1600, 900))
      .toEqual({ left: 300, top: 200, width: 320, height: 240 });
  });

  it('never returns a rect the caller would have to move to make legal', () => {
    // Every direction, pushed far past both of its limits: the anchored edges must
    // still be where they were, and nothing may be negative or below the minimum.
    for (const mode of ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as const) {
      for (const [dx, dy] of [[-5000, -5000], [5000, 5000]] as const) {
        const r = resizedFromOrigin(origin, mode, dx, dy, 1600, 900);
        expect(r.width, `${mode} width`).toBeGreaterThanOrEqual(320);
        expect(r.height, `${mode} height`).toBeGreaterThanOrEqual(240);
        expect(r.left, `${mode} left`).toBeGreaterThanOrEqual(0);
        expect(r.top, `${mode} top`).toBeGreaterThanOrEqual(0);
      }
    }
  });
});

describe('defaultFloatingRect', () => {
  it('parks the window top-right, inset from the edges and CLEAR of the app header', () => {
    // A window that opens on top of the header buries the dock buttons that put
    // it back, which is the first thing a user reaches for after detaching.
    setViewport(1600, 900);
    const r = defaultFloatingRect();
    // Derived, not a literal: a test that restates the constant cannot catch the
    // drift the constant's own comment warns about.
    expect(r.top).toBeGreaterThanOrEqual(APP_HEADER_HEIGHT);
    expect(r.left + r.width).toBe(1600 - 24);
    expect(r.width).toBeGreaterThanOrEqual(320);
    expect(r.top + r.height, 'and it still fits above the bottom edge').toBeLessThanOrEqual(900 - 24);
  });

  it('falls back to a fixed size when there is no window at all (server render)', () => {
    // The module is imported by a client component that Next also renders on the
    // server, so the initializer must not throw there.
    const w = globalThis.window;
    // Deleting the global is the only way to simulate a server render in jsdom.
    delete (globalThis as { window?: Window }).window;
    try {
      const r = defaultFloatingRect();
      expect(r.top).toBe(APP_HEADER_HEIGHT + 24);
      expect(r.left + r.width, 'inset from the 1280px fallback width').toBe(1280 - 24);
      expect(r.top + r.height, 'and fits the 800px fallback height').toBeLessThanOrEqual(800 - 24);
      expect(r.width).toBeGreaterThanOrEqual(320);
    } finally {
      globalThis.window = w;
    }
  });

  it('never proposes a window a narrow viewport cannot hold', () => {
    setViewport(700, 500);
    const r = clampFloatingRect(defaultFloatingRect(), 700, 500);
    expect(r.left).toBeGreaterThanOrEqual(0);
    expect(r.left + r.width).toBeLessThanOrEqual(700);
    expect(r.top + r.height).toBeLessThanOrEqual(500);
  });
});

describe('useFloatingPanelRect - the FIRST detach, with nothing stored', () => {
  it('opens fully on screen on a short viewport', () => {
    // The default is computed from constants, so on a short window it hangs off the
    // bottom - carrying the close and re-dock controls, which live in the title bar
    // of a card the user cannot reach. Detach is gated on WIDTH, so a wide-but-short
    // desktop window reaches this. Nothing corrects it until the next resize: the
    // re-clamp layout effect runs BEFORE the passive restore effect in that commit.
    // Asserted on the rect the HOOK renders, not on the clamp helper, because it is
    // the hook that chose not to call it.
    setViewport(1000, 300);
    const { result } = renderHook(() => useFloatingPanelRect(true));

    const r = result.current.rect;
    expect(r.top + r.height, 'bottom edge inside the viewport').toBeLessThanOrEqual(300);
    expect(r.left + r.width).toBeLessThanOrEqual(1000);
    expect(r.top).toBeGreaterThanOrEqual(0);
    expect(r.left).toBeGreaterThanOrEqual(0);
  });

  it('leaves a roomy viewport alone', () => {
    // The clamp must not become a second layout engine: on a normal screen the
    // first detach still lands exactly where the default puts it.
    setViewport(1600, 900);
    const { result } = renderHook(() => useFloatingPanelRect(true));

    expect(result.current.rect).toEqual(defaultFloatingRect());
  });
});

describe('useFloatingPanelRect', () => {
  it('restores the stored rect when the panel is detached', () => {
    const stored: FloatingRect = { left: 120, top: 60, width: 500, height: 400 };
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.rect).toEqual(stored);
  });

  it('ignores a corrupt stored rect instead of rendering a broken window', () => {
    window.localStorage.setItem(STORAGE_KEY, '{"left":"nope"}');
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.rect).toEqual(defaultFloatingRect());
  });

  it('clamps a stored rect that no longer fits the current viewport', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 1400, top: 800, width: 500, height: 400 }));
    setViewport(1000, 700);
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.rect.left + result.current.rect.width).toBeLessThanOrEqual(1000);
    expect(result.current.rect.top + result.current.rect.height).toBeLessThanOrEqual(700);
  });

  it('moves the window by the pointer delta and persists where it landed', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 200, y: 200 }, { x: 260, y: 240 }, result.current.startDrag('move'));

    expect(result.current.rect).toEqual({ left: 160, top: 140, width: 400, height: 300 });
    expect((result.current.dragMode !== null)).toBe(false);
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual(result.current.rect);
  });

  it('measures every frame from the rect the drag STARTED on, so a clamped edge is not permanent', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    const startDrag = result.current.startDrag('move');

    act(() => {
      startDrag({
        button: 0, pointerId: 7, clientX: 200, clientY: 200,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });
    // Shove it far past the right edge - it clamps to left = 1600 - 400.
    act(() => { window.dispatchEvent(pointerEvent('pointermove', 5000, 200)); });
    expect(result.current.rect.left).toBe(1200);
    // ...then come back. Accumulated deltas would have kept it pinned at the edge.
    act(() => { window.dispatchEvent(pointerEvent('pointermove', 150, 200)); });
    expect(result.current.rect.left).toBe(50);
  });

  it('resizes east on width only, and south on height only', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 500, y: 400 }, { x: 620, y: 480 }, result.current.startDrag('e'));
    expect(result.current.rect).toEqual({ left: 100, top: 100, width: 520, height: 300 });

    drag({ x: 500, y: 400 }, { x: 620, y: 480 }, result.current.startDrag('s'));
    expect(result.current.rect).toEqual({ left: 100, top: 100, width: 520, height: 380 });
  });

  it('resizes both axes from the corner', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    drag({ x: 500, y: 400 }, { x: 560, y: 450 }, result.current.startDrag('se'));
    expect(result.current.rect).toEqual({ left: 100, top: 100, width: 460, height: 350 });
  });

  it('pulls the WEST edge by moving the origin and the width together', () => {
    // The two must come from one clamped delta. Derived separately, the window
    // jumps the instant one of them hits its limit while the other keeps
    // following the cursor - which is why these edges were left out at first.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 400, top: 300, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 400, y: 450 }, { x: 340, y: 450 }, result.current.startDrag('w'));

    expect(result.current.rect).toEqual({ left: 340, top: 300, width: 460, height: 300 });
    expect(result.current.rect.left + result.current.rect.width, 'the east edge never moved').toBe(800);
  });

  it('pulls the NORTH edge the same way', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 400, top: 300, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 600, y: 300 }, { x: 600, y: 250 }, result.current.startDrag('n'));

    expect(result.current.rect).toEqual({ left: 400, top: 250, width: 400, height: 350 });
    expect(result.current.rect.top + result.current.rect.height, 'the south edge never moved').toBe(600);
  });

  it('stops a west drag at the viewport instead of pushing the window off it', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 60, top: 300, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 60, y: 450 }, { x: -500, y: 450 }, result.current.startDrag('w'));

    expect(result.current.rect.left, 'pinned at the edge').toBe(0);
    expect(result.current.rect.width, 'and the width stops with it').toBe(460);
    expect(result.current.rect.left + result.current.rect.width, 'the anchored edge held').toBe(460);
  });

  it('stops a west drag at the minimum width without moving the anchored edge', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 400, top: 300, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    // Dragged far PAST the east edge: the window must bottom out, not invert.
    drag({ x: 400, y: 450 }, { x: 1200, y: 450 }, result.current.startDrag('w'));

    expect(result.current.rect.width).toBe(320);
    expect(result.current.rect.left).toBe(480);
    expect(result.current.rect.left + result.current.rect.width, 'still anchored').toBe(800);
  });

  it('stops a north drag at the viewport instead of pushing the window off it', () => {
    // The west edge has this test; the north one had neither of its two clamps,
    // so both could be deleted with the suite still green.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 400, top: 60, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 600, y: 60 }, { x: 600, y: -400 }, result.current.startDrag('n'));

    expect(result.current.rect.top, 'pinned at the edge').toBe(0);
    expect(result.current.rect.height, 'and the height stops with it').toBe(360);
    expect(result.current.rect.top + result.current.rect.height, 'the anchored edge held').toBe(360);
  });

  it('stops a north drag at the minimum height without moving the anchored edge', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 400, top: 300, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    // Dragged far PAST the south edge: the window must bottom out, not invert.
    drag({ x: 600, y: 300 }, { x: 600, y: 1000 }, result.current.startDrag('n'));

    expect(result.current.rect.height).toBe(240);
    expect(result.current.rect.top).toBe(360);
    expect(result.current.rect.top + result.current.rect.height, 'still anchored').toBe(600);
  });

  it('clamps BOTH axes of a corner drag at once, each on its own limit', () => {
    // A corner is where the two rules meet; nothing covered them binding together.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 40, top: 30, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 40, y: 30 }, { x: -600, y: -600 }, result.current.startDrag('nw'));

    expect(result.current.rect).toEqual({ left: 0, top: 0, width: 440, height: 330 });
  });

  it('clamps a collapsed window against the box it PAINTS, so it can reach a corner', () => {
    // Collapsed the caller renders a small strip while the rect stays the size the
    // window will expand back to. Clamping the move against that rect reserves room
    // for a window that is not on screen, and the strip stops short of the right
    // and bottom edges by its own expanded size - it cannot be parked in a corner,
    // which is the entire point of collapsing it.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 300, top: 200, width: 520, height: 420 }));
    const { result } = renderHook(() => useFloatingPanelRect(true, { width: 260, height: 36 }));

    act(() => { result.current.nudge(9999, 9999); });

    expect(result.current.rect.left, 'the strip reaches the right edge').toBe(1600 - 260);
    expect(result.current.rect.top, 'and the bottom edge').toBe(900 - 36);
    // The rect it expands back to is untouched.
    expect(result.current.rect.width).toBe(520);
    expect(result.current.rect.height).toBe(420);
  });

  it('REFUSES a keyboard resize while a render override is active', () => {
    // The override lets the ORIGIN go past what a full window allows, so the
    // caller can park a small painted box in a corner. A resize measured from that
    // relaxed origin caps against a `left`/`top` no real window may have: one
    // keypress floored a corner-parked window to the minimum, in memory and in
    // storage, while the fixed-size strip on screen did not move at all.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 300, top: 200, width: 520, height: 420 }));
    const { result } = renderHook(() => useFloatingPanelRect(true, { width: 260, height: 36 }));
    act(() => { result.current.nudge(9999, 9999); });          // park it in the corner
    const parked = result.current.rect;

    for (let i = 0; i < 5; i += 1) act(() => { result.current.nudge(16, 0, 'se'); });

    expect(result.current.rect, 'the window the user sized is untouched').toEqual(parked);
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!).width, 'and nothing invalid was stored').toBe(520);
  });

  it('still MOVES from the keyboard while the override is active', () => {
    // The refusal is for the resize verb only: the strip has to stay placeable.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 300, top: 200, width: 520, height: 420 }));
    const { result } = renderHook(() => useFloatingPanelRect(true, { width: 260, height: 36 }));

    act(() => { result.current.nudge(-16, -16); });

    expect(result.current.rect.left).toBe(284);
    expect(result.current.rect.top).toBe(184);
    expect(result.current.rect.width, 'and the size is left alone').toBe(520);
  });

  it('stores a rect that is valid as a WINDOW, even when parked as a strip', () => {
    // While collapsed the origin is legitimately past what a full window allows.
    // Storing that raw leaves the remembered geometry disagreeing with what the
    // user sees the moment they expand.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 300, top: 200, width: 520, height: 420 }));
    const { result } = renderHook(() => useFloatingPanelRect(true, { width: 260, height: 36 }));

    act(() => { result.current.nudge(9999, 9999); });

    const stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY)!);
    expect(stored.left + stored.width, 'fits the viewport as a window').toBeLessThanOrEqual(1600);
    expect(stored.top + stored.height).toBeLessThanOrEqual(900);
    expect(result.current.rect.left, 'while the live strip stays in the corner').toBe(1600 - 260);
  });

  it('re-clamps when the render override appears and when it goes away', () => {
    // Parked in a corner as a strip, the rect keeps the size it will expand back
    // to. Nothing re-clamped on the flip, so expanding put the window - and the
    // only close and re-dock controls it carries - off the bottom-right of the
    // screen with 36px left on it.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 300, top: 200, width: 520, height: 420 }));
    const { result, rerender } = renderHook(
      ({ painted }) => useFloatingPanelRect(true, painted),
      { initialProps: { painted: undefined as { width: number; height: number } | undefined } },
    );

    rerender({ painted: { width: 260, height: 36 } });
    act(() => { result.current.nudge(9999, 9999); });
    expect(result.current.rect.left, 'the strip reaches the corner').toBe(1600 - 260);
    expect(result.current.rect.top).toBe(900 - 36);

    rerender({ painted: undefined });

    expect(result.current.rect.left + result.current.rect.width, 'expanded back ON screen')
      .toBeLessThanOrEqual(1600);
    expect(result.current.rect.top + result.current.rect.height).toBeLessThanOrEqual(900);
    expect(result.current.rect.width, 'at the size the user had chosen').toBe(520);
  });

  it('survives a caller that passes the painted box as an object literal', () => {
    // Which is the obvious way to call it. Depending on the object's IDENTITY made
    // the re-clamp effect re-run on every render, set state, and render again -
    // the hook never settled and the worker died. Found by writing this very test.
    const { result } = renderHook(() => useFloatingPanelRect(true, { width: 260, height: 36 }));
    expect(result.current.rect.width).toBeGreaterThanOrEqual(320);
    act(() => { result.current.nudge(9999, 9999); });
    expect(result.current.rect.left, 'and it still clamps on the painted box').toBe(1600 - 260);
  });

  it('clamps the size on a viewport shrink even while a render override is active', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 300, top: 200, width: 520, height: 420 }));
    const { result } = renderHook(() => useFloatingPanelRect(true, { width: 260, height: 36 }));

    act(() => { setViewport(700, 400); window.dispatchEvent(new Event('resize')); });

    expect(result.current.rect.width, 'a window wider than the screen is not a window').toBeLessThanOrEqual(700);
    expect(result.current.rect.height).toBeLessThanOrEqual(400);
  });

  it('pulls both axes from every corner, each in its own direction', () => {
    const seed = { left: 400, top: 300, width: 400, height: 300 };
    const cases = [
      ['nw', -40, -30, { left: 360, top: 270, width: 440, height: 330 }],
      ['ne', 40, -30, { left: 400, top: 270, width: 440, height: 330 }],
      ['sw', -40, 30, { left: 360, top: 300, width: 440, height: 330 }],
      ['se', 40, 30, { left: 400, top: 300, width: 440, height: 330 }],
    ] as const;
    for (const [mode, dx, dy, expected] of cases) {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
      const { result, unmount } = renderHook(() => useFloatingPanelRect(true));
      drag({ x: 600, y: 450 }, { x: 600 + dx, y: 450 + dy }, result.current.startDrag(mode));
      expect(result.current.rect, mode).toEqual(expected);
      unmount();
    }
  });

  it('gives every handle the cursor its direction implies', () => {
    expect(FLOATING_DRAG_CURSOR.n).toBe('ns-resize');
    expect(FLOATING_DRAG_CURSOR.s).toBe('ns-resize');
    expect(FLOATING_DRAG_CURSOR.w).toBe('ew-resize');
    expect(FLOATING_DRAG_CURSOR.e).toBe('ew-resize');
    // The two diagonals are mirror images, so they must not share a cursor.
    expect(FLOATING_DRAG_CURSOR.nw).toBe('nwse-resize');
    expect(FLOATING_DRAG_CURSOR.se).toBe('nwse-resize');
    expect(FLOATING_DRAG_CURSOR.ne).toBe('nesw-resize');
    expect(FLOATING_DRAG_CURSOR.sw).toBe('nesw-resize');
  });

  it('never moves the window while resizing it, even when the size clamps', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    // Drag the corner far up and left: width/height bottom out at the minimum...
    drag({ x: 500, y: 400 }, { x: 0, y: 0 }, result.current.startDrag('se'));
    expect(result.current.rect.width).toBe(320);
    expect(result.current.rect.height).toBe(240);
    // ...and the origin is untouched: that is the rule for the two edges that grow
    // AWAY from it. The north and west edges move the origin on purpose, and clamp
    // the delta instead (covered below).
    expect(result.current.rect.left).toBe(100);
    expect(result.current.rect.top).toBe(100);
  });

  it('caps a resize at the viewport instead of sliding the window out from under the cursor', () => {
    // Found live: a window near the bottom edge, dragged taller, stayed inside the
    // viewport only because the containment clamp pulled its TOP up - so the card
    // crept upwards while the user was resizing it. The size is what must stop.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 1100, top: 600, width: 400, height: 260 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 1500, y: 860 }, { x: 1900, y: 1400 }, result.current.startDrag('se'));

    expect(result.current.rect.left).toBe(1100);
    expect(result.current.rect.top).toBe(600);
    // Grown to exactly the room left below and right of the origin, no further.
    expect(result.current.rect.width).toBe(1600 - 1100);
    expect(result.current.rect.height).toBe(900 - 600);
  });

  it('suppresses the default pointerdown, which is what the title bar then compensates for', () => {
    // preventDefault is what stops a drag painting a text selection across the app.
    // It also kills the default focus, which is why the title bar focuses itself -
    // a behaviour that has its own test and would be inexplicable without this one.
    const { result } = renderHook(() => useFloatingPanelRect(true));
    const preventDefault = vi.fn();
    const setPointerCapture = vi.fn();
    const handle = Object.assign(document.createElement('div'), { setPointerCapture });
    act(() => {
      result.current.startDrag('move')({
        button: 0, pointerId: 7, clientX: 0, clientY: 0, currentTarget: handle, preventDefault,
      } as never);
    });
    expect(preventDefault).toHaveBeenCalled();
    // Capture is what keeps the gesture alive over an iframe - the panel is full of them.
    expect(setPointerCapture).toHaveBeenCalledWith(7);
    act(() => { window.dispatchEvent(pointerEvent('pointerup', 0, 0)); });
  });

  it('ignores a SECOND pointer moving during a drag, so a stray touch cannot steer the window', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    act(() => {
      result.current.startDrag('move')({
        button: 0, pointerId: 7, clientX: 200, clientY: 200,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });

    const other = new Event('pointermove') as Event & { pointerId: number; clientX: number; clientY: number };
    other.pointerId = 99; other.clientX = 900; other.clientY = 900;
    act(() => { window.dispatchEvent(other); });
    expect(result.current.rect.left, 'a foreign pointer must not move the window').toBe(100);

    // The tracked pointer still does.
    act(() => { window.dispatchEvent(pointerEvent('pointermove', 240, 200)); });
    expect(result.current.rect.left).toBe(140);

    // ...and a foreign pointerup must not end the drag either.
    const otherUp = new Event('pointerup') as Event & { pointerId: number };
    otherUp.pointerId = 99;
    act(() => { window.dispatchEvent(otherUp); });
    expect((result.current.dragMode !== null)).toBe(true);
    act(() => { window.dispatchEvent(pointerEvent('pointerup', 240, 200)); });
    expect((result.current.dragMode !== null)).toBe(false);
  });

  it('ignores a non-primary button, so a right-click never starts an unstoppable drag', () => {
    const { result } = renderHook(() => useFloatingPanelRect(true));
    const before = result.current.rect;
    act(() => {
      result.current.startDrag('move')({
        button: 2, pointerId: 7, clientX: 0, clientY: 0,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });
    act(() => { window.dispatchEvent(pointerEvent('pointermove', 400, 400)); });
    expect(result.current.rect).toEqual(before);
    expect((result.current.dragMode !== null)).toBe(false);
  });

  it('ends the drag on pointercancel, so a gesture stolen by the browser does not weld the window to the cursor', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    act(() => {
      result.current.startDrag('move')({
        button: 0, pointerId: 7, clientX: 200, clientY: 200,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });
    act(() => { window.dispatchEvent(pointerEvent('pointercancel', 200, 200)); });
    expect((result.current.dragMode !== null)).toBe(false);

    const settled = result.current.rect;
    act(() => { window.dispatchEvent(pointerEvent('pointermove', 900, 900)); });
    expect(result.current.rect).toEqual(settled);
  });

  it('re-clamps when the viewport shrinks under the detached window', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 1000, top: 500, width: 500, height: 350 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.rect.left).toBe(1000);

    act(() => { setViewport(900, 600); window.dispatchEvent(new Event('resize')); });

    expect(result.current.rect.left + result.current.rect.width).toBeLessThanOrEqual(900);
    expect(result.current.rect.top + result.current.rect.height).toBeLessThanOrEqual(600);
  });

  it('ends the drag on window blur, so alt-tabbing away does not weld the window to the cursor', () => {
    // The pointerup is delivered to the other application, never here.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    act(() => {
      result.current.startDrag('move')({
        button: 0, pointerId: 7, clientX: 200, clientY: 200,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });
    act(() => { window.dispatchEvent(new Event('blur')); });

    expect((result.current.dragMode !== null)).toBe(false);
    expect(document.body.style.userSelect, 'text selection is handed back').not.toBe('none');
    const settled = result.current.rect;
    act(() => { window.dispatchEvent(pointerEvent('pointermove', 900, 900)); });
    expect(result.current.rect).toEqual(settled);
  });

  it('hands text selection back when it is unmounted mid-drag', () => {
    // A route change or a locale switch can unmount the owner while the pointer is
    // down. Nothing then delivers the pointerup, so without an unmount teardown
    // `userSelect: none` stays on <body> and selection is dead document-wide.
    const { result, unmount } = renderHook(() => useFloatingPanelRect(true));
    act(() => {
      result.current.startDrag('move')({
        button: 0, pointerId: 7, clientX: 200, clientY: 200,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });
    expect(document.body.style.userSelect).toBe('none');

    unmount();

    expect(document.body.style.userSelect).not.toBe('none');
  });

  it('removes its window listeners when it is unmounted mid-drag', () => {
    // Restoring userSelect is only half the teardown: a leaked pointermove listener
    // keeps a dead hook responding to every mouse move for the rest of the session.
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const abort = vi.spyOn(AbortController.prototype, 'abort');
    try {
      const { result, unmount } = renderHook(() => useFloatingPanelRect(true));
      act(() => {
        result.current.startDrag('move')({
          button: 0, pointerId: 7, clientX: 200, clientY: 200,
          currentTarget: document.createElement('div'), preventDefault: () => {},
        } as never);
      });
      // Every drag listener is registered against one AbortSignal...
      const dragListeners = add.mock.calls.filter(([type]) =>
        ['pointermove', 'pointerup', 'pointercancel', 'blur'].includes(type as string));
      expect(dragListeners).toHaveLength(4);
      expect(dragListeners.every(([, , opts]) => !!(opts as AddEventListenerOptions)?.signal)).toBe(true);

      abort.mockClear();
      unmount();

      // ...so aborting it is what removes them, and it must happen on unmount.
      expect(abort).toHaveBeenCalled();
      expect(remove).not.toHaveBeenCalledWith('pointermove', expect.anything());
    } finally {
      add.mockRestore(); remove.mockRestore(); abort.mockRestore();
    }
  });

  it('refuses a second drag while one is in flight', () => {
    // The second one would capture the FIRST one's already-neutralised userSelect
    // as the value to restore, so whichever ends last leaves the document dead.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    const press = (mode: 'move' | 'se') => act(() => {
      result.current.startDrag(mode)({
        button: 0, pointerId: 7, clientX: 200, clientY: 200,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });

    press('move');
    expect(result.current.dragMode).toBe('move');
    press('se');
    expect(result.current.dragMode, 'the first drag keeps the pointer').toBe('move');

    act(() => { window.dispatchEvent(pointerEvent('pointerup', 200, 200)); });
    expect(result.current.dragMode).toBeNull();
    expect(document.body.style.userSelect).not.toBe('none');
  });

  it('reports which handle is being dragged, so the overlay can wear its cursor', () => {
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.dragMode).toBeNull();
    act(() => {
      result.current.startDrag('e')({
        button: 0, pointerId: 7, clientX: 0, clientY: 0,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });
    expect(result.current.dragMode).toBe('e');
    expect(FLOATING_DRAG_CURSOR[result.current.dragMode!]).toBe('ew-resize');
    act(() => { window.dispatchEvent(pointerEvent('pointerup', 0, 0)); });
  });

  it('caps an east and a south resize at the viewport on their own axis', () => {
    // The corner covers both at once; each single-axis grip has to stop too.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 1100, top: 600, width: 400, height: 260 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    drag({ x: 1500, y: 860 }, { x: 2400, y: 860 }, result.current.startDrag('e'));
    expect(result.current.rect).toEqual({ left: 1100, top: 600, width: 500, height: 260 });

    drag({ x: 1600, y: 860 }, { x: 1600, y: 1800 }, result.current.startDrag('s'));
    expect(result.current.rect).toEqual({ left: 1100, top: 600, width: 500, height: 300 });
  });

  it('nudges by keyboard with the same rules as a drag - move, then resize, never both', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    act(() => { result.current.nudge(-16, 0); });
    expect(result.current.rect).toEqual({ left: 84, top: 100, width: 400, height: 300 });

    act(() => { result.current.nudge(16, 24, 'se'); });
    expect(result.current.rect).toEqual({ left: 84, top: 100, width: 416, height: 324 });

    // The other anchor: the keyboard reaches the north and west edges too, holding
    // the opposite edge still. Pinned to 'se' alone, half the window was mouse-only.
    act(() => { result.current.nudge(-16, -24, 'nw'); });
    expect(result.current.rect, 'grew to the left and up, right and bottom held')
      .toEqual({ left: 68, top: 76, width: 432, height: 348 });
  });

  it('nudges are clamped like drags, so the keyboard cannot push the window off screen', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 0, top: 0, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    act(() => { result.current.nudge(-16, -16); });
    expect(result.current.rect.left).toBe(0);
    expect(result.current.rect.top).toBe(0);
  });

  it('ignores a stored value that is not JSON at all', () => {
    // The shape guard covers valid-but-wrong JSON; this covers the parse itself,
    // which is what a truncated or half-written entry produces.
    window.localStorage.setItem(STORAGE_KEY, 'not json {');
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.rect).toEqual(defaultFloatingRect());
  });

  it('keeps working when localStorage refuses to be written to', () => {
    // Private mode / blocked site data: the window must still be movable, it just
    // will not be remembered.
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('QuotaExceededError');
    });
    try {
      const { result } = renderHook(() => useFloatingPanelRect(true));
      expect(() => drag({ x: 200, y: 200 }, { x: 240, y: 200 }, result.current.startDrag('move'))).not.toThrow();
      expect(result.current.rect.left).toBeGreaterThan(0);
    } finally {
      setItem.mockRestore();
    }
  });

  it('never stamps one workspace geometry into another bucket on a switch', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 10, top: 10, width: 400, height: 300 }));
    window.localStorage.setItem('lc.sidePanel.floatingRect:org-b', JSON.stringify({ left: 700, top: 300, width: 420, height: 320 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    try {
      act(() => { useCurrentOrgStore.getState().setCurrentOrg('org-b', 'OWNER'); });
      expect(result.current.rect.left).toBe(700);
      expect(
        setItem.mock.calls.filter(([k]) => String(k).startsWith('lc.sidePanel.floatingRect')),
        'a switch reads, it never writes',
      ).toHaveLength(0);
      expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!).left, 'the old bucket is intact').toBe(10);
    } finally {
      setItem.mockRestore();
    }
  });

  it('keeps each workspace geometry in its own bucket', () => {
    // The side-panel layout is org-aware by rule ("the layout in Org A never bleeds
    // into Org B"); a global rect would be the one value that did.
    window.localStorage.setItem('lc.sidePanel.floatingRect:org-a', JSON.stringify({ left: 40, top: 40, width: 500, height: 400 }));
    act(() => { useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'); });
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.rect.left).toBe(40);

    act(() => { result.current.nudge(60, 0); });
    expect(JSON.parse(window.localStorage.getItem('lc.sidePanel.floatingRect:org-a')!).left).toBe(100);
    expect(window.localStorage.getItem(STORAGE_KEY), 'the personal bucket is untouched').toBeNull();
  });

  it('re-reads the geometry when the workspace changes under it', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 10, top: 10, width: 400, height: 300 }));
    window.localStorage.setItem('lc.sidePanel.floatingRect:org-b', JSON.stringify({ left: 700, top: 300, width: 420, height: 320 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.rect.left).toBe(10);

    act(() => { useCurrentOrgStore.getState().setCurrentOrg('org-b', 'OWNER'); });

    expect(result.current.rect.left).toBe(700);
  });

  it('falls back to the default window in a workspace that has never been detached in', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 10, top: 10, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    act(() => { useCurrentOrgStore.getState().setCurrentOrg('org-c', 'OWNER'); });
    expect(result.current.rect).toEqual(defaultFloatingRect());
  });

  it('writes ONCE per drag, not once per frame', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 100, top: 100, width: 400, height: 300 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    try {
      act(() => {
        result.current.startDrag('move')({
          button: 0, pointerId: 7, clientX: 200, clientY: 200,
          currentTarget: document.createElement('div'), preventDefault: () => {},
        } as never);
      });
      act(() => { window.dispatchEvent(pointerEvent('pointermove', 210, 200)); });
      act(() => { window.dispatchEvent(pointerEvent('pointermove', 230, 200)); });
      act(() => { window.dispatchEvent(pointerEvent('pointermove', 260, 200)); });
      expect(setItem.mock.calls.filter(([k]) => k === STORAGE_KEY), 'nothing written mid-drag')
        .toHaveLength(0);

      act(() => { window.dispatchEvent(pointerEvent('pointerup', 260, 200)); });

      const writes = setItem.mock.calls.filter(([k]) => k === STORAGE_KEY);
      expect(writes).toHaveLength(1);
      expect(JSON.parse(writes[0][1] as string).left, 'and it carries where the drag SETTLED').toBe(160);
    } finally {
      setItem.mockRestore();
    }
  });

  it('does not persist the clamp a viewport shrink applies', () => {
    // A transient shrink (minimise, a snapped window, a rotation) must not
    // overwrite the size the user picked: restoring the viewport has to bring the
    // window back, and a persisted clamp makes that impossible.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ left: 1000, top: 500, width: 500, height: 350 }));
    const { result } = renderHook(() => useFloatingPanelRect(true));

    act(() => { setViewport(900, 600); window.dispatchEvent(new Event('resize')); });

    expect(result.current.rect.left, 'on screen now').toBeLessThan(1000);
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!), 'but the chosen geometry is kept')
      .toEqual({ left: 1000, top: 500, width: 500, height: 350 });
  });

  it('reports the viewport it clamps against, and keeps it current', () => {
    // The caller lays the resize grips outside the card and has to pull them back
    // inside when the window is flush against an edge.
    const { result } = renderHook(() => useFloatingPanelRect(true));
    expect(result.current.viewport).toEqual({ width: 1600, height: 900 });

    act(() => { setViewport(1000, 700); window.dispatchEvent(new Event('resize')); });

    expect(result.current.viewport).toEqual({ width: 1000, height: 700 });
  });

  it('ends an in-flight drag when the panel stops being detached', () => {
    // Crossing the mobile breakpoint mid-drag re-docks the panel and unmounts its
    // chrome; without this the caller's full-viewport drag overlay stays up,
    // unclickable, waiting for a pointerup nobody will deliver.
    const { result, rerender } = renderHook(({ on }) => useFloatingPanelRect(on), {
      initialProps: { on: true },
    });
    act(() => {
      result.current.startDrag('move')({
        button: 0, pointerId: 7, clientX: 200, clientY: 200,
        currentTarget: document.createElement('div'), preventDefault: () => {},
      } as never);
    });
    expect(result.current.dragMode).toBe('move');

    rerender({ on: false });

    expect(result.current.dragMode).toBeNull();
    expect(document.body.style.userSelect).not.toBe('none');
  });

  it('writes nothing while the panel is still docked', () => {
    renderHook(() => useFloatingPanelRect(false));
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});
