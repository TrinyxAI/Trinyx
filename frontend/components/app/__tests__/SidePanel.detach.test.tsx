/**
 * @vitest-environment jsdom
 *
 * Detaching the unified side panel: a fourth dock ('floating') that turns the
 * panel into a movable, resizable card floating over the app.
 *
 * What matters here is that detaching is a MODE FLIP and nothing else - the same
 * container, the same React subtree - because that is what lets a detach keep the
 * live state the panel holds (a running canvas, an SSE stream, an interface
 * iframe). The tests therefore pin the box model, the controls that appear with
 * it, and the two places it must stay out of: a phone, and a shared conversation.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/app/c/conv-1',
}));
const mobile = vi.hoisted(() => ({ value: false }));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => mobile.value }));
const shared = vi.hoisted(() => ({ value: null as unknown }));
vi.mock('@/contexts/SharedConversationContext', () => ({ useSharedConversation: () => shared.value }));
vi.mock('@/components/app/AddTabPicker', () => ({ AddTabPicker: () => null }));
// Forwards `onResizeStart` so a test can actually drive a dock resize; the real
// handle is a fixed overlay whose own geometry is not what these tests are about.
vi.mock('@/components/ui/PanelResizeHandle', () => ({
  PanelResizeHandle: ({ onResizeStart }: { onResizeStart: () => void }) => (
    <div data-testid="edge-resize-handle" onMouseDown={onResizeStart} />
  ),
}));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { SidePanelLayoutProvider, useSidePanelLayout } from '@/contexts/SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { SidePanel } from '@/components/app/SidePanel';

const POSITION_KEY = 'lc.sidePanel.position:personal';
// Per-workspace, like every other side-panel layout value.
const RECT_KEY = 'lc.sidePanel.floatingRect:personal';
/** The collapsed strip's painted box - kept in step with SidePanel's constants. */
const COLLAPSED_W = 180;
const COLLAPSED_H = 36;

function setViewport(width: number, height: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true, writable: true });
  Object.defineProperty(window, 'innerHeight', { value: height, configurable: true, writable: true });
}

beforeEach(() => {
  window.localStorage.clear();
  mobile.value = false;
  shared.value = null;
  // A viewport with room to spare, so a geometry assertion measures the DRAG and
  // not the containment clamp (which has its own tests, on the hook).
  setViewport(1600, 900);
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

function Opener({ keepMounted = false }: { keepMounted?: boolean }) {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    sp.openTab({
      id: 'workflow-1', label: 'WF', icon: <span />, content: <div>body</div>, keepMounted,
    } as SidePanelTab);
  }, [sp, keepMounted]);
  return null;
}

/** Flips the dock from inside the provider, the way the header buttons do. */
function DockToBottom() {
  const { setPosition } = useSidePanelLayout();
  return (
    <>
      <button type="button" data-testid="dock-to-bottom" onClick={() => setPosition('bottom')} />
      {/* Stands in for a workspace switch re-hydrating a stored 'floating'. */}
      <button type="button" data-testid="dock-to-floating" onClick={() => setPosition('floating')} />
      {/* The one dock AppShell renders in a different branch, so it remounts. */}
      <button type="button" data-testid="dock-to-bottom-full" onClick={() => setPosition('bottom-full')} />
    </>
  );
}

function renderPanel() {
  return render(
    <SidePanelLayoutProvider>
      <SidePanelProvider>
        <DockToBottom />
        <Opener />
        <SidePanel />
      </SidePanelProvider>
    </SidePanelLayoutProvider>,
  );
}

const panelBox = () => screen.getByTestId('side-panel');
const overlay = () => document.querySelector<HTMLElement>('[data-side-panel-drag-overlay]');
const grip = (dir: string) => document.querySelector<HTMLElement>(`[data-side-panel-resize="${dir}"]`);

/** px of a `style` value, so geometry can be compared as numbers. */
const px = (value: string) => Number.parseFloat(value || '0');

/**
 * A pointer drag over a handle. jsdom has no PointerEvent, and the production code
 * reads only these fields, so a MouseEvent carrying a pointerId is a faithful stand-in.
 */
function pointerDrag(handle: HTMLElement, from: [number, number], to: [number, number]) {
  const make = (type: string, [x, y]: [number, number]) => {
    const ev = new MouseEvent(type, { bubbles: true, clientX: x, clientY: y, button: 0 });
    Object.defineProperty(ev, 'pointerId', { value: 3 });
    return ev;
  };
  act(() => { handle.dispatchEvent(make('pointerdown', from)); });
  act(() => { window.dispatchEvent(make('pointermove', to)); });
}

/** Releases where the drag ended by default, so a release is not itself a 400px move. */
function endDrag(at: [number, number] = [0, 0]) {
  const ev = new MouseEvent('pointerup', { bubbles: true, button: 0, clientX: at[0], clientY: at[1] });
  Object.defineProperty(ev, 'pointerId', { value: 3 });
  act(() => { window.dispatchEvent(ev); });
}
const detachButton = () => screen.queryByTestId('side-panel-detach');
const titleBar = () => document.querySelector('[data-side-panel-titlebar]');
const grips = () => document.querySelectorAll('[data-side-panel-resize]');

describe('SidePanel - detach', () => {
  it('docked right: no floating chrome, and the edge resize handle is the one on offer', () => {
    renderPanel();
    expect(panelBox().getAttribute('data-side-panel-floating')).toBeNull();
    expect(panelBox().classList.contains('border-l')).toBe(true);
    expect(titleBar()).toBeNull();
    expect(grips()).toHaveLength(0);
    expect(screen.queryByTestId('edge-resize-handle')).toBeTruthy();
  });

  it('detaches into a fixed card with its own geometry, keeping the SAME container element', () => {
    renderPanel();
    const before = panelBox();
    act(() => { fireEvent.click(detachButton()!); });
    const after = panelBox();

    // The identity check is the point: a detach that swapped the element would
    // remount every keepMounted tab under it and lose the live state they hold.
    expect(after).toBe(before);
    expect(after.getAttribute('data-side-panel-floating')).toBe('true');
    expect(after.classList.contains('fixed')).toBe(true);
    expect(after.classList.contains('border-l')).toBe(false);
    // A full rect, not an edge-derived single axis.
    expect(after.style.left).not.toBe('');
    expect(after.style.top).not.toBe('');
    expect(after.style.width).not.toBe('');
    expect(after.style.height).not.toBe('');
  });

  it('swaps the edge handle for a title bar and the eight window grips', () => {
    renderPanel();
    act(() => { fireEvent.click(detachButton()!); });

    expect(titleBar()).toBeTruthy();
    // Every edge and every corner, so the window resizes from wherever the user
    // grabs it - the corners pulling both axes at once.
    expect([...grips()].map((g) => g.getAttribute('data-side-panel-resize')).sort())
      .toEqual(['e', 'n', 'ne', 'nw', 's', 'se', 'sw', 'w']);
    // The edge handle is anchored to a viewport edge the detached panel no longer
    // touches, so it must be gone rather than left dangling on the left of the screen.
    expect(screen.queryByTestId('edge-resize-handle')).toBeNull();
  });

  it('persists the detach, so the panel comes back detached', () => {
    renderPanel();
    act(() => { fireEvent.click(detachButton()!); });
    expect(window.localStorage.getItem(POSITION_KEY)).toBe('floating');
  });

  it('re-attaches to the dock the panel was on, not to the default', () => {
    // The user overrode the dock for this session before detaching.
    window.localStorage.setItem(POSITION_KEY, 'bottom');
    renderPanel();
    expect(panelBox().classList.contains('border-t')).toBe(true);

    act(() => { fireEvent.click(detachButton()!); });
    expect(panelBox().getAttribute('data-side-panel-floating')).toBe('true');

    act(() => { fireEvent.click(detachButton()!); });
    expect(panelBox().getAttribute('data-side-panel-floating')).toBeNull();
    expect(panelBox().classList.contains('border-t')).toBe(true);
    expect(window.localStorage.getItem(POSITION_KEY)).toBe('bottom');
  });

  it('does not render the floating branch on the FIRST render, which is what makes it SSR-safe', () => {
    // The floating geometry is derived from `window.innerWidth/innerHeight`, so
    // server and client cannot agree on it. That is only safe because the dock
    // position starts at the static default and the stored one arrives in an
    // effect, so the first render is never floating. Pinned here, because the
    // reasoning otherwise lives in a comment and the next person to make the dock
    // position hydrate synchronously would get a mismatch with nothing failing.
    window.localStorage.setItem(POSITION_KEY, 'floating');
    // A useState SEED captures the first render's value without any render-phase
    // side effect - the very thing this probe is here to rule out.
    const seen: string[] = [];
    function Probe() {
      const { position } = useSidePanelLayout();
      const [firstRender] = React.useState(position);
      React.useEffect(() => { seen.push(firstRender); }, [firstRender]);
      return null;
    }
    render(
      <SidePanelLayoutProvider>
        <SidePanelProvider>
          <Probe />
          <Opener />
          <SidePanel />
        </SidePanelProvider>
      </SidePanelLayoutProvider>,
    );

    expect(seen[0], 'the first render must not be floating').not.toBe('floating');
    // ...and the stored detach still applies, one commit later.
    expect(panelBox().getAttribute('data-side-panel-floating')).toBe('true');
  });

  it('is not offered on a phone, and a stored detach renders there as the usual overlay', () => {
    mobile.value = true;
    window.localStorage.setItem(POSITION_KEY, 'floating');
    renderPanel();

    expect(detachButton()).toBeNull();
    expect(panelBox().getAttribute('data-side-panel-floating')).toBeNull();
    expect(titleBar()).toBeNull();
    // The mobile full-height overlay, i.e. exactly what a right dock gives there.
    expect(panelBox().classList.contains('h-full')).toBe(true);
    expect(panelBox().style.height).toBe('');
  });

  it('is not offered in a shared conversation, where the panel is read-only chrome', () => {
    shared.value = { conversationId: 'c1' };
    renderPanel();
    expect(detachButton()).toBeNull();
  });
});

describe('SidePanel - the detached window is actually wired', () => {
  const detachAndReturnBar = () => { detach(); return titleBar() as HTMLElement; };

  /** Detach with a known rect, parked well clear of every viewport edge. */
  function detach() {
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 300, top: 200, width: 400, height: 300 }));
    renderPanel();
    act(() => { fireEvent.click(detachButton()!); });
    return panelBox();
  }

  it('moves the window when the title bar is dragged', () => {
    // Existence of the title bar proves nothing: a bar with no `onPointerDown`,
    // or one wired to a resize mode, renders identically.
    const box = detach();
    const before = { left: px(box.style.left), top: px(box.style.top), width: px(box.style.width) };

    pointerDrag(titleBar() as HTMLElement, [200, 200], [260, 240]);

    expect(px(box.style.left)).toBe(before.left + 60);
    expect(px(box.style.top)).toBe(before.top + 40);
    expect(px(box.style.width), 'a move must not resize').toBe(before.width);
    endDrag();
  });

  it('wires each grip to its own axis - east widens, south heightens, the corner does both', () => {
    const box = detach();
    const start = { width: px(box.style.width), height: px(box.style.height) };

    pointerDrag(grip('e')!, [500, 400], [560, 460]);
    expect(px(box.style.width)).toBe(start.width + 60);
    expect(px(box.style.height), 'the east grip must not touch height').toBe(start.height);
    endDrag();

    const afterEast = { width: px(box.style.width), height: px(box.style.height) };
    pointerDrag(grip('s')!, [500, 400], [560, 460]);
    expect(px(box.style.width), 'the south grip must not touch width').toBe(afterEast.width);
    expect(px(box.style.height)).toBe(afterEast.height + 60);
    endDrag();

    const afterSouth = { width: px(box.style.width), height: px(box.style.height) };
    pointerDrag(grip('se')!, [500, 400], [540, 430]);
    expect(px(box.style.width)).toBe(afterSouth.width + 40);
    expect(px(box.style.height)).toBe(afterSouth.height + 30);
    endDrag();
  });

  it('wires the ORIGIN-MOVING grips to their own direction too', () => {
    // The e/s/se test above cannot see these: north and west move the origin, and
    // wiring the north grip to 's' - or all four corners to 'se' - left every
    // existence, z-index, touch-none and cursor assertion in this file green.
    const box = detach();
    const start = {
      x: px(box.style.left), y: px(box.style.top),
      w: px(box.style.width), h: px(box.style.height),
    };

    pointerDrag(grip('w')!, [300, 350], [260, 350]);
    expect(px(box.style.left), 'west moves the origin').toBe(start.x - 40);
    expect(px(box.style.width)).toBe(start.w + 40);
    expect(px(box.style.left) + px(box.style.width), 'and holds the east edge').toBe(start.x + start.w);
    endDrag();

    const afterW = { x: px(box.style.left), w: px(box.style.width), h: px(box.style.height) };
    pointerDrag(grip('n')!, [500, 200], [500, 170]);
    expect(px(box.style.top), 'north moves the origin').toBe(start.y - 30);
    expect(px(box.style.height)).toBe(afterW.h + 30);
    expect(px(box.style.width), 'and does not touch width').toBe(afterW.w);
    endDrag();

    const afterN = { x: px(box.style.left), y: px(box.style.top), w: px(box.style.width), h: px(box.style.height) };
    pointerDrag(grip('nw')!, [260, 170], [240, 150]);
    expect(px(box.style.left), 'the corner pulls both').toBe(afterN.x - 20);
    expect(px(box.style.top)).toBe(afterN.y - 20);
    expect(px(box.style.width)).toBe(afterN.w + 20);
    expect(px(box.style.height)).toBe(afterN.h + 20);
    endDrag();

    // ne and sw are the mirror diagonals: each must pull ONE origin axis.
    const afterNW = { x: px(box.style.left), y: px(box.style.top) };
    pointerDrag(grip('ne')!, [800, 150], [820, 130]);
    expect(px(box.style.left), 'ne holds the west edge').toBe(afterNW.x);
    expect(px(box.style.top), 'and moves the north one').toBe(afterNW.y - 20);
    endDrag();

    const afterNE = { x: px(box.style.left), y: px(box.style.top) };
    pointerDrag(grip('sw')!, [240, 600], [220, 620]);
    expect(px(box.style.left), 'sw moves the west edge').toBe(afterNE.x - 20);
    expect(px(box.style.top), 'and holds the north one').toBe(afterNE.y);
    endDrag();
  });

  it('shows the cursor its own direction implies while idle', () => {
    // The drag overlay's cursor is tested on the hook's table; this is what the
    // user sees on HOVER, and it comes from the grip's own class.
    detach();
    for (const [dir, cursor] of [
      ['n', 'cursor-ns-resize'], ['s', 'cursor-ns-resize'],
      ['e', 'cursor-ew-resize'], ['w', 'cursor-ew-resize'],
      ['nw', 'cursor-nwse-resize'], ['se', 'cursor-nwse-resize'],
      ['ne', 'cursor-nesw-resize'], ['sw', 'cursor-nesw-resize'],
    ] as const) {
      expect(grip(dir)!.className, `${dir} grip`).toContain(cursor);
    }
  });

  it('moves and resizes from the keyboard, so the window is not pointer-only', () => {
    const box = detach();
    const bar = titleBar() as HTMLElement;
    expect(bar.getAttribute('tabindex'), 'the title bar must be focusable').toBe('0');

    const before = { left: px(box.style.left), top: px(box.style.top), width: px(box.style.width) };
    act(() => { fireEvent.keyDown(bar, { key: 'ArrowLeft' }); });
    expect(px(box.style.left)).toBe(before.left - 16);
    expect(px(box.style.width), 'a plain arrow moves, it does not resize').toBe(before.width);

    act(() => { fireEvent.keyDown(bar, { key: 'ArrowRight', shiftKey: true }); });
    expect(px(box.style.width), 'shift+arrow resizes').toBe(before.width + 16);
    expect(px(box.style.left), 'and resizing never moves the window').toBe(before.left - 16);
  });

  it('covers the page during a drag with an overlay carrying the cursor of THAT handle', () => {
    // The overlay exists to keep the gesture alive over an iframe, and it sits
    // above everything - so it owns the cursor for the whole drag. A single
    // "dragging" flag painted a closed hand over a corner resize.
    detach();
    expect(overlay(), 'no overlay while idle').toBeNull();

    pointerDrag(titleBar() as HTMLElement, [200, 200], [210, 210]);
    expect(overlay()!.style.cursor).toBe('grabbing');
    endDrag();
    expect(overlay(), 'the overlay goes away with the drag').toBeNull();

    pointerDrag(grip('se')!, [500, 400], [510, 410]);
    expect(overlay()!.style.cursor).toBe('nwse-resize');
    endDrag();

    pointerDrag(grip('e')!, [500, 400], [510, 400]);
    expect(overlay()!.style.cursor).toBe('ew-resize');
    endDrag();
  });

  it('reaches the north and west edges from the keyboard, not just the south-east', () => {
    // The request was every edge and every corner. The pointer path delivers eight
    // directions; a keyboard resize pinned to one anchor delivers two edges, and
    // moving the window instead is a different gesture - it does not hold the
    // opposite edge still. Ctrl (or Meta) picks the opposite anchor, the same pair
    // of combinations a window manager uses.
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 300, top: 200, width: 400, height: 300 }));
    renderPanel();
    act(() => { fireEvent.click(detachButton()!); });
    const box = panelBox();
    const bar = titleBar() as HTMLElement;

    act(() => { fireEvent.keyDown(bar, { key: 'ArrowLeft', shiftKey: true, ctrlKey: true }); });

    expect(px(box.style.left), 'the west edge moved out').toBe(284);
    expect(px(box.style.width), 'so the window grew').toBe(416);
    expect(px(box.style.left) + px(box.style.width), 'and the east edge held still').toBe(700);

    act(() => { fireEvent.keyDown(bar, { key: 'ArrowUp', shiftKey: true, ctrlKey: true }); });

    expect(px(box.style.top), 'the north edge moved out').toBe(184);
    expect(px(box.style.top) + px(box.style.height), 'and the south edge held still').toBe(500);
  });

  it('still resizes from the south-east on a plain Shift+arrow', () => {
    // The second anchor must not take over the first.
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 300, top: 200, width: 400, height: 300 }));
    renderPanel();
    act(() => { fireEvent.click(detachButton()!); });
    const box = panelBox();

    act(() => { fireEvent.keyDown(titleBar() as HTMLElement, { key: 'ArrowRight', shiftKey: true }); });

    expect(px(box.style.left), 'the west edge held still').toBe(300);
    expect(px(box.style.width), 'and the east edge moved out').toBe(416);
  });

  it('takes focus when it is grabbed, so the arrow keys work right after a drag', () => {
    // The pointerdown is preventDefault-ed to suppress the drag-selection it would
    // otherwise paint, and that also suppresses the default focus - so the keyboard
    // path was dead after any mouse use until the user tabbed back to the bar.
    const box = detach();
    const bar = titleBar() as HTMLElement;
    pointerDrag(bar, [200, 200], [210, 200]);
    endDrag();
    expect(document.activeElement).toBe(bar);

    const left = px(box.style.left);
    act(() => { fireEvent.keyDown(document.activeElement!, { key: 'ArrowRight' }); });
    expect(px(box.style.left)).toBe(left + 16);
  });

  it('drops to a 1px step with Alt, and ignores keys that are not arrows', () => {
    const box = detach();
    const bar = titleBar() as HTMLElement;
    const before = { left: px(box.style.left), top: px(box.style.top) };

    act(() => { fireEvent.keyDown(bar, { key: 'ArrowDown', altKey: true }); });
    expect(px(box.style.top)).toBe(before.top + 1);

    // A regression that nudged on every keystroke would move the window here.
    act(() => { fireEvent.keyDown(bar, { key: 'a' }); });
    act(() => { fireEvent.keyDown(bar, { key: 'Enter' }); });
    expect(px(box.style.left)).toBe(before.left);
    expect(px(box.style.top)).toBe(before.top + 1);
  });

  it('stops an arrow key from also reaching the window listeners behind it', () => {
    // The application carousel paginates on Left/Right from a WINDOW listener, so
    // one keypress would nudge the window AND turn its page.
    detach();
    const seen: string[] = [];
    const spy = (e: Event) => seen.push((e as KeyboardEvent).key);
    window.addEventListener('keydown', spy);
    try {
      act(() => { fireEvent.keyDown(titleBar() as HTMLElement, { key: 'ArrowLeft' }); });
      expect(seen).toEqual([]);
      // A key the panel does not claim still reaches them.
      act(() => { fireEvent.keyDown(titleBar() as HTMLElement, { key: 'k' }); });
      expect(seen).toEqual(['k']);
    } finally {
      window.removeEventListener('keydown', spy);
    }
  });

  it('rides in the tab bar and costs the window no height of its own', () => {
    // The whole point of moving the grip here. A strip of its own is honest about
    // being a title bar, but it spends vertical space above a 56px tab bar on a
    // window whose entire purpose is to stay small, and it read as padding. So the
    // FIRST thing the panel renders must be the tab bar itself: anything else in
    // that position is a row that pushed the window taller again.
    const box = detach();
    const bar = titleBar() as HTMLElement;
    const body = box.querySelector('.h-full.flex.flex-col') as HTMLElement;

    expect(body.firstElementChild!.contains(bar), 'a row was added above the tab bar').toBe(true);
    expect((bar.parentElement as HTMLElement).className, 'not in the 56px row').toContain('h-14');
    expect(bar.className, 'and it is still the drag surface').toContain('cursor-grab');
  });

  it('stays out of the scrollable tab area, so it cannot fight tab reorder or a touch scroll', () => {
    // The reason it is a fixed grip at the head of the row rather than "drag the
    // tab bar background": the tab area scrolls horizontally and its children are
    // draggable tabs. A drag surface inside it would swallow the touch scroll on a
    // tablet and compete with tab reorder on a mouse - the two things this window
    // is actually used with.
    detach();
    const bar = titleBar() as HTMLElement;
    expect(bar.closest('.overflow-x-auto'), 'inside the scrolling tab area').toBeNull();
    expect(bar.className, 'takes the row height rather than adding to it').toContain('self-stretch');
    expect(bar.className, 'narrow with a mouse').toContain('w-6');
    expect(bar.className, 'wider for a finger').toContain('pointer-coarse:w-7');
  });

  it('wears the same handle as the inspector panel: vertical grip, grab cursor, hover plate', () => {
    // Two windows that move the same way should not look like two different
    // controls. The glyph is VERTICAL because the handle is a tall narrow band at
    // the head of the row, not the wide flat strip it started as - and the 2px
    // pill that briefly stood in for it read as a decorative divider, which is
    // the opposite of what a drag surface has to say.
    detach();
    const bar = titleBar() as HTMLElement;
    expect(bar.querySelector('svg.lucide-grip-vertical'), 'not the inspector glyph').toBeTruthy();
    expect(bar.querySelector('svg.lucide-grip-horizontal'), 'still the flat strip glyph').toBeNull();
    expect(bar.className, 'no hand on hover').toContain('cursor-grab');
    expect(bar.className, 'no hand while dragging').toContain('active:cursor-grabbing');
    // An ARBITRARY-value hover, not `hover:bg-theme-secondary`: the theme classes
    // are hand-written in `@layer components`, so Tailwind generates no `hover:`
    // variant for them and that plate never painted (measured in a browser:
    // transparent on hover). The variant of a custom class is a silent no-op.
    expect(bar.className, 'no plate under the pointer').toContain('hover:bg-[var(--bg-secondary)]');
    expect(bar.className, 'a hover variant of a custom class never paints').not.toContain('hover:bg-theme-secondary');
  });

  it('shows the open hand rather than the pointing one, despite being focusable', () => {
    // The bug the grab cursor actually had, and the reason the inspector's handle
    // looked different: globals.css gives every interactive element a pointer via
    // `[tabindex]:not([tabindex="-1"])`, which is TWO attribute selectors and
    // outranks the single `.cursor-grab` class. The inspector's handle has no
    // tabindex and kept its hand; this one is keyboard-reachable and lost it. The
    // fix is a doubled class in globals.css, so it is the stylesheet that has to
    // be pinned - jsdom applies no stylesheet and would call this green either way.
    const css = readFileSync(resolve(__dirname, '../../../app/globals.css'), 'utf-8');
    expect(css, 'the blanket rule is back on top').toContain('.cursor-grab.cursor-grab');
    expect(css).toContain(String.raw`.active\:cursor-grabbing.active\:cursor-grabbing:active`);
    const bar = (() => { detach(); return titleBar() as HTMLElement; })();
    expect(bar.getAttribute('tabindex'), 'not focusable, so the rule cannot bite').toBe('0');
  });

  it('cancels the browser touch gesture on the bar and every grip, which is what makes a tablet drag work', () => {
    // `touch-action: none` is not styling: without it a finger on the title bar
    // scrolls the page and the window never moves. Tablets are half of what this
    // feature is for, and jsdom cannot notice the difference at runtime.
    detach();
    expect((titleBar() as HTMLElement).className).toContain('touch-none');
    for (const dir of ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw']) {
      expect(grip(dir)!.className, `${dir} grip`).toContain('touch-none');
    }
  });

  it('paints above the sidebar and never animates its own geometry', () => {
    const box = detach();
    // Above the sidebar's z-[60]: a window buried under an opaque sidebar cannot
    // be grabbed by its title bar.
    expect(box.className).toContain('z-[61]');
    for (const dir of ['n', 's', 'e', 'w']) {
      expect(grip(dir)!.className, `${dir} edge stays above the card`).toContain('z-[62]');
    }
    for (const dir of ['ne', 'nw', 'se', 'sw']) {
      // A notch higher than the edges: a corner overlaps the two bands that meet
      // there, and must win, or the corner target is only the sliver they leave.
      expect(grip(dir)!.className, `${dir} corner stays above the edges`).toContain('z-[63]');
    }
    // left/top are set every frame by the drag and cannot transition, so animating
    // only width/height would be half an animation - and would leave the grips,
    // which are positioned from the same rect, trailing the edges they sit on.
    expect(box.style.transition).toBe('none');
  });

  it('does not take focus on a right-click', () => {
    const bar = detachAndReturnBar();
    const ev = new MouseEvent('pointerdown', { bubbles: true, clientX: 200, clientY: 200, button: 2 });
    Object.defineProperty(ev, 'pointerId', { value: 4 });
    act(() => { bar.dispatchEvent(ev); });
    expect(document.activeElement, 'a context menu is not a grab').not.toBe(bar);
  });

  it('is a focusable grab handle, not a button that promises an action it has none of', () => {
    detach();
    const bar = titleBar() as HTMLElement;
    expect(bar.getAttribute('tabindex')).toBe('0');
    expect(bar.getAttribute('aria-keyshortcuts'), 'the arrow verbs are machine-readable')
      .toContain('Shift+ArrowRight');
    // Not `button`: that announces Enter/Space activation, and this control has no
    // action - its keyboard verbs are the arrow keys. But not role-less either:
    // aria-label is only reliably announced on a role that permits a name.
    expect(bar.getAttribute('role')).toBe('group');
    expect(bar.getAttribute('aria-label'), 'the label names the keyboard verbs').toBeTruthy();
  });

  it('keeps the corner overlap off the panel content, at the rounding and no further', () => {
    // The corners must overlap the card enough to be grabbable but not enough to
    // reach the content's own right-edge scrollbar. Widening the overlap from 6 to
    // 14px put them back on it - the exact thing rendering the grips outside the
    // card exists to avoid - with nothing failing.
    const box = detach();
    const right = px(box.style.left) + px(box.style.width);
    const bottom = px(box.style.top) + px(box.style.height);
    const left = px(box.style.left);
    const top = px(box.style.top);
    for (const dir of ['nw', 'ne', 'sw', 'se'] as const) {
      const g = grip(dir)!;
      const gl = px(g.style.left);
      const gt = px(g.style.top);
      const overlapX = dir.includes('w') ? gl + 20 - left : right - gl;
      const overlapY = dir.startsWith('n') ? gt + 20 - top : bottom - gt;
      expect(overlapX, `${dir} horizontal overlap`).toBeLessThanOrEqual(6);
      expect(overlapY, `${dir} vertical overlap`).toBeLessThanOrEqual(6);
    }
  });

  it('gives each edge band the hover accent of its own axis', () => {
    // A horizontal accent on a vertical band paints a hairline nobody sees.
    const box = detach();
    expect(box).toBeTruthy();
    for (const dir of ['n', 's']) {
      expect(grip(dir)!.firstElementChild!.className, `${dir} accent`).toContain('w-full');
    }
    for (const dir of ['e', 'w']) {
      expect(grip(dir)!.firstElementChild!.className, `${dir} accent`).toContain('h-full');
    }
  });

  it('leaves no gap between an edge band and the corner beside it', () => {
    // Existence and z-order say nothing about WHERE a band is. Insetting each band
    // by the corner size while placing the corners at a different offset left eight
    // strips, up to 10px wide, where the pointer hit nothing - invisible to every
    // other assertion in this file, and to a pointer drag that happens to land
    // somewhere else.
    const box = detach();
    const card = {
      left: px(box.style.left), top: px(box.style.top),
      right: px(box.style.left) + px(box.style.width),
      bottom: px(box.style.top) + px(box.style.height),
    };
    const rectOf = (dir: string) => {
      const g = grip(dir)!;
      return {
        left: px(g.style.left), top: px(g.style.top),
        right: px(g.style.left) + px(g.style.width),
        bottom: px(g.style.top) + px(g.style.height),
      };
    };
    const covers = (parts: ReadonlyArray<{ from: number; to: number }>, from: number, to: number) => {
      const sorted = [...parts].sort((a, b) => a.from - b.from);
      let reach = from;
      for (const p of sorted) {
        if (p.from > reach) return false; // a gap
        reach = Math.max(reach, p.to);
      }
      return reach >= to;
    };
    const xs = (dir: string) => ({ from: rectOf(dir).left, to: rectOf(dir).right });
    const ys = (dir: string) => ({ from: rectOf(dir).top, to: rectOf(dir).bottom });

    for (const [side, parts] of [
      ['north', [xs('nw'), xs('n'), xs('ne')]],
      ['south', [xs('sw'), xs('s'), xs('se')]],
    ] as const) {
      expect(covers(parts, card.left, card.right), `${side} edge has a dead zone`).toBe(true);
    }
    for (const [side, parts] of [
      ['west', [ys('nw'), ys('w'), ys('sw')]],
      ['east', [ys('ne'), ys('e'), ys('se')]],
    ] as const) {
      expect(covers(parts, card.top, card.bottom), `${side} edge has a dead zone`).toBe(true);
    }
  });

  it('narrows the north and west bands when the window is flush against the TOP-LEFT', () => {
    // The suite only ever parked it bottom-right, where `northH`/`westW` sit at
    // their full 9px - so the narrowing branch of the two new bands was dead code
    // as far as the tests were concerned, one drag away from a user.
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 0, top: 0, width: 400, height: 300 }));
    renderPanel();
    act(() => { fireEvent.click(detachButton()!); });

    for (const dir of ['n', 'w'] as const) {
      const g = grip(dir)!;
      expect(px(g.style.left), `${dir} stays on screen`).toBeGreaterThanOrEqual(0);
      expect(px(g.style.top)).toBeGreaterThanOrEqual(0);
      const thickness = dir === 'n' ? px(g.style.height) : px(g.style.width);
      // EXACTLY the floor, not a range. Flush at 0 there is no room outside the card
      // at all, so the branch has one right answer, and a range that admits both 4
      // and the un-narrowed 9 certifies nothing: 9 puts a full-width resize strip
      // over more than half the 16px drag bar, which is the thing being prevented.
      // (An earlier version asked for >= 1, which admitted a 1px ungrabbable band.)
      expect(thickness, `${dir} narrowed to exactly its floor`).toBe(4);
    }
    // And the corners have nowhere outside to go, so they slide in rather than off.
    for (const dir of ['nw', 'ne', 'sw'] as const) {
      expect(px(grip(dir)!.style.left), `${dir} on screen`).toBeGreaterThanOrEqual(0);
      expect(px(grip(dir)!.style.top)).toBeGreaterThanOrEqual(0);
    }
    // ...and NARROW while they do it. Staying on screen is not enough: a corner
    // clamped to 0 that keeps its full 20px is 20px of resize target sitting on the
    // card, and at the top-left that is the left third of the drag strip. The east
    // and south corners had this treatment; the north and west ones did not, so
    // this asserts the OVERLAP, which is what "on screen" cannot see.
    const nw = grip('nw')!;
    expect(px(nw.style.left), 'flush left').toBe(0);
    expect(px(nw.style.width), 'nw eats no more of the card than its siblings').toBeLessThanOrEqual(8);
    expect(px(nw.style.height), 'same on the vertical').toBeLessThanOrEqual(8);
    expect(px(nw.style.width), 'but is still grabbable').toBeGreaterThanOrEqual(8);
  });

  it('keeps the grips ON SCREEN when the window is parked flush against an edge', () => {
    // Which is what the drag clamp actively produces: it stops the window exactly
    // AT the edge. A grip band laid 1px inside from there hangs 8 of its 9px off
    // screen, leaving a 1px target - the one state in which the window could no
    // longer be resized with a pointer at all.
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 1200, top: 600, width: 400, height: 300 }));
    renderPanel();
    act(() => { fireEvent.click(detachButton()!); });
    const box = panelBox();
    expect(px(box.style.left) + px(box.style.width), 'flush right').toBe(1600);
    expect(px(box.style.top) + px(box.style.height), 'flush bottom').toBe(900);

    // Asserted against the CARD EDGE, not against zero: `Math.max(0, ...)` cannot
    // produce a negative coordinate for any formula, so "is it >= 0" passes for a
    // band parked over the tab bar or one 40px thick that swallows the drag strip.
    const cardTop = px(box.style.top);
    const cardLeft = px(box.style.left);
    const northBand = grip('n')!;
    expect(px(northBand.style.top) + px(northBand.style.height), 'the north band ends at the card top')
      .toBe(cardTop + 1);
    expect(px(northBand.style.height), 'and is a real band, not a swallowing slab')
      .toBeLessThanOrEqual(9);
    const westBand = grip('w')!;
    expect(px(westBand.style.left) + px(westBand.style.width), 'the west band ends at the card left')
      .toBe(cardLeft + 1);
    expect(px(westBand.style.width)).toBeLessThanOrEqual(9);
    const east = grip('e')!;
    const south = grip('s')!;
    const corner = grip('se')!;
    // Flush, there is no room OUTSIDE the card, so the band narrows to what is
    // left rather than either hanging off screen (a 1px target) or keeping its
    // full width by climbing 9px onto the content's scrollbar.
    expect(px(east.style.left) + px(east.style.width), 'east band fits on screen').toBeLessThanOrEqual(1600);
    expect(px(east.style.width), 'and stays a real target').toBeGreaterThanOrEqual(4);
    expect(1600 - px(east.style.left), 'while overlapping the card as little as possible').toBeLessThanOrEqual(4);
    expect(px(south.style.top) + px(south.style.height), 'south band fits on screen').toBeLessThanOrEqual(900);
    expect(px(south.style.height)).toBeGreaterThanOrEqual(4);
    expect(900 - px(south.style.top)).toBeLessThanOrEqual(4);
    expect(px(corner.style.left) + px(corner.style.width)).toBeLessThanOrEqual(1600);
    expect(px(corner.style.top) + px(corner.style.height)).toBeLessThanOrEqual(900);
  });

  it('keeps the grips clear of the scrollbar the panel content carries', () => {
    const box = detach();
    const right = px(box.style.left) + px(box.style.width);
    const bottom = px(box.style.top) + px(box.style.height);
    // Outside the card but for the 1px border they make grabbable. Overlapping
    // further would put the grip on top of the panel content's scrollbar, which
    // is the whole reason they are rendered outside the card in the first place.
    expect(px(grip('e')!.style.left)).toBe(right - 1);
    expect(px(grip('s')!.style.top)).toBe(bottom - 1);
  });
});

describe('SidePanel - the detach control itself', () => {
  it('names the dock it would go back to, and reads as pressed only while detached', () => {
    window.localStorage.setItem(POSITION_KEY, 'bottom');
    renderPanel();
    expect(detachButton()!.getAttribute('aria-pressed')).toBe('false');
    expect(detachButton()!.getAttribute('title')).toBe('detach');

    act(() => { fireEvent.click(detachButton()!); });

    expect(detachButton()!.getAttribute('aria-pressed')).toBe('true');
    // Not a generic "put it back": the icon and the tooltip say WHERE.
    expect(detachButton()!.getAttribute('title')).toBe('attach');
    expect(detachButton()!.querySelector('.lucide-panel-bottom'), 'it came from a bottom dock').toBeTruthy();
  });

  it('shows the right-dock glyph when that is where it would go back', () => {
    renderPanel(); // opens on the 'right' default
    act(() => { fireEvent.click(detachButton()!); });
    expect(detachButton()!.querySelector('.lucide-panel-right')).toBeTruthy();
  });
});

describe('SidePanel - a detached panel that is CLOSED', () => {
  it('collapses to nothing and drops its window chrome, keeping keepMounted content in the tree', () => {
    // Closed, the card is a 0x0 box: a border and a shadow on it paint a dot on
    // the page. What must NOT go away is the content - keepMounted tabs stay
    // mounted while closed in a dock, and detaching may not change that.
    render(
      <SidePanelLayoutProvider>
        <SidePanelProvider>
          <Opener keepMounted />
          <SidePanel />
        </SidePanelProvider>
      </SidePanelLayoutProvider>,
    );
    act(() => { fireEvent.click(detachButton()!); });
    expect(panelBox().classList.contains('shadow-2xl')).toBe(true);

    act(() => { fireEvent.click(screen.getByTitle('close')); });

    const box = panelBox();
    expect(box.getAttribute('data-side-panel-floating')).toBe('true');
    expect(box.style.width).toBe('0px');
    expect(box.style.height).toBe('0px');
    expect(box.classList.contains('border'), 'no border on a 0x0 box').toBe(false);
    expect(box.classList.contains('shadow-2xl'), 'no shadow on a 0x0 box').toBe(false);
    expect(titleBar(), 'no chrome on a closed window').toBeNull();
    expect(grips()).toHaveLength(0);
    expect(screen.getByText('body'), 'keepMounted content survives the close').toBeTruthy();
  });
});

describe('SidePanel - what a detach must never cost', () => {
  /**
   * The promise the whole design rests on: a detach is a mode flip, so everything
   * a keepMounted tab holds - a running workflow canvas, an open SSE run stream,
   * an interface iframe - survives it.
   *
   * Counted on the TAB CONTENT, not on the panel container: the container sits at
   * a fixed JSX position and is stable under any state change, so its identity
   * proves nothing about the subtree underneath it. A mount counter here is the
   * only instrument that sees a remount inside the panel.
   *
   * The OTHER half of the promise - that the app shell does not change branch and
   * tear this whole subtree down - is not visible from here, because this file
   * mounts the panel directly. It is guarded in AppShell.floating.test.tsx.
   */
  const mounts = { count: 0 };

  function LiveTabContent() {
    React.useEffect(() => { mounts.count += 1; }, []);
    return <div>live-content</div>;
  }

  function LiveOpener() {
    const sp = useSidePanel();
    const done = React.useRef(false);
    React.useEffect(() => {
      if (done.current) return;
      done.current = true;
      sp.openTab({
        id: 'workflow-1', label: 'WF', icon: <span />, content: <LiveTabContent />, keepMounted: true,
      } as SidePanelTab);
    }, [sp]);
    return null;
  }

  /** Drives the layout preferences the way Settings does, from inside the provider. */
  function Prefs() {
    const { setBottomMode } = useSidePanelLayout();
    return (
      <>
        <button type="button" onClick={() => setBottomMode('bottom')}>prefer-bottom</button>
      </>
    );
  }

  function renderLive(startAt: string) {
    mounts.count = 0;
    window.localStorage.setItem(POSITION_KEY, startAt);
    render(
      <SidePanelLayoutProvider>
        <SidePanelProvider>
          <Prefs />
          <LiveOpener />
          <SidePanel />
        </SidePanelProvider>
      </SidePanelLayoutProvider>,
    );
  }

  it.each(['right', 'bottom', 'bottom-full'])(
    'keeps live tab content mounted across a detach round trip from the %s dock',
    (dock) => {
      renderLive(dock);
      const baseline = mounts.count;

      act(() => { fireEvent.click(detachButton()!); });
      expect(panelBox().getAttribute('data-side-panel-floating')).toBe('true');
      expect(mounts.count, 'detaching remounted the tab content').toBe(baseline);

      act(() => { fireEvent.click(detachButton()!); });
      expect(panelBox().getAttribute('data-side-panel-floating')).toBeNull();
      expect(mounts.count, 're-attaching remounted the tab content').toBe(baseline);
      expect(screen.getByText('live-content')).toBeTruthy();
    },
  );

  it('re-attaches onto the bottom variant chosen while it was detached', () => {
    // The preference is still honoured - just at the moment the panel moves anyway.
    renderLive('bottom-full');
    act(() => { fireEvent.click(detachButton()!); });
    act(() => { fireEvent.click(screen.getByText('prefer-bottom')); });

    act(() => { fireEvent.click(detachButton()!); });

    expect(panelBox().getAttribute('data-side-panel-floating')).toBeNull();
    expect(window.localStorage.getItem(POSITION_KEY)).toBe('bottom');
  });
});

describe('SidePanel - the dock the panel comes back to', () => {
  /** Drag the dock's own edge handle by `delta` px along its axis. */
  function resizeDock(delta: number) {
    const before = panelBox();
    const axis = before.style.height ? 'y' : 'x';
    act(() => { fireEvent.mouseDown(screen.getByTestId('edge-resize-handle')); });
    act(() => {
      window.dispatchEvent(new MouseEvent('mousemove', {
        clientX: axis === 'x' ? window.innerWidth - delta : 0,
        clientY: axis === 'y' ? window.innerHeight - delta : 0,
      }));
    });
    act(() => { window.dispatchEvent(new MouseEvent('mouseup')); });
  }

  it('keeps a manually sized BOTTOM dock through a detach round trip', () => {
    // The axis-reset effect drops any manual size when the dock's axis flips.
    // Detaching forces the "not bottom" reading whatever dock it came from, so
    // keying that effect on it threw the user's chosen height away on the way out
    // AND on the way back - handing them the 40%-of-viewport default instead.
    window.localStorage.setItem(POSITION_KEY, 'bottom');
    renderPanel();
    resizeDock(420);
    const sized = panelBox().style.height;
    expect(sized).toBe('420px');

    act(() => { fireEvent.click(detachButton()!); });
    act(() => { fireEvent.click(detachButton()!); });

    expect(panelBox().style.height, 'the manual dock height was reset by the round trip').toBe(sized);
  });

  it('still resets the size on a genuine right <-> bottom flip', () => {
    // The reset exists because the stored px size is axis-specific; suppressing it
    // for a detach must not suppress it for a real dock change.
    renderPanel();
    resizeDock(700);
    expect(panelBox().style.width).toBe('700px');

    act(() => { fireEvent.click(screen.getByTestId('dock-to-bottom')); });

    expect(panelBox().style.width, 'a width is meaningless on a bottom dock').toBe('');
    expect(panelBox().style.height).not.toBe('700px');
  });
});

describe('SidePanel - recentring the canvas behind the window', () => {
  /**
   * `workflowViewFitView` re-fits and ANIMATES a workflow canvas, throwing away
   * the pan and zoom the user had set. So it must fire when the canvas's own box
   * actually changed, and stay silent otherwise.
   *
   * A detached window is `position: fixed` and out of flow: detaching hands the
   * main column its width back, and resizing the window changes nothing outside
   * it - but MOVING it changes nothing at all, and moving is the most frequent
   * gesture of the whole feature.
   */
  const fits: Event[] = [];
  const listen = () => { window.addEventListener('workflowViewFitView', collect); };
  const collect = (e: Event) => { fits.push(e); };

  beforeEach(() => { fits.length = 0; });
  afterEach(() => { window.removeEventListener('workflowViewFitView', collect); });

  function detachListening() {
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 300, top: 200, width: 400, height: 300 }));
    renderPanel();
    listen();
    act(() => { fireEvent.click(detachButton()!); });
    return panelBox();
  }

  it('re-fits once on detach, when the main column reclaims the dock width', () => {
    detachListening();
    expect(fits).toHaveLength(1);
  });

  it('re-fits after a resize drag, but NOT after a move drag', () => {
    detachListening();
    fits.length = 0;

    pointerDrag(titleBar() as HTMLElement, [200, 200], [240, 240]);
    endDrag();
    expect(fits, 'a move changes no box anywhere').toHaveLength(0);

    pointerDrag(grip('se')!, [500, 400], [540, 430]);
    endDrag();
    expect(fits, 'a resize does').toHaveLength(1);

    pointerDrag(grip('e')!, [500, 400], [540, 400]);
    endDrag();
    expect(fits).toHaveLength(2);
  });

  it('never re-fits the canvas from the collapsed strip, whatever the modifier', () => {
    // The strip paints a fixed box: neither verb can change anything behind it, so
    // re-fitting would throw away the canvas pan and zoom for nothing.
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 300, top: 200, width: 400, height: 300 }));
    renderPanel();
    listen();
    act(() => { fireEvent.click(detachButton()!); });
    act(() => { fireEvent.click(screen.getByTestId('side-panel-collapse')); });
    fits.length = 0;
    const row = document.querySelector<HTMLElement>('[data-side-panel-expand]')!;

    act(() => { fireEvent.keyDown(row, { key: 'ArrowLeft' }); });
    act(() => { fireEvent.keyDown(row, { key: 'ArrowRight', shiftKey: true }); });

    expect(fits).toHaveLength(0);
  });

  it('follows the same rule from the keyboard', () => {
    detachListening();
    const bar = titleBar() as HTMLElement;
    fits.length = 0;

    act(() => { fireEvent.keyDown(bar, { key: 'ArrowLeft' }); });
    expect(fits, 'a plain arrow moves').toHaveLength(0);

    act(() => { fireEvent.keyDown(bar, { key: 'ArrowRight', shiftKey: true }); });
    expect(fits, 'shift+arrow resizes').toHaveLength(1);
  });
});

describe('SidePanel - collapsing the detached window', () => {
  const collapseBtn = () => screen.queryByTestId('side-panel-collapse');
  const collapsedRow = () => document.querySelector<HTMLElement>('[data-side-panel-collapsed-row]');
  const expandBtn = () => document.querySelector<HTMLElement>('[data-side-panel-expand]');
  const forwardProbe = () => screen.getByTestId('is-forward').textContent;

  /**
   * The panel behind a remountable slot, because AppShell mounts it at a different
   * tree position per dock branch: anything that flips that branch REMOUNTS it, and
   * a remount is not a dock change.
   */
  /** `isForward` off the real context: the stranded-flag defect never reaches the DOM. */
  function ForwardProbe() {
    const sp = useSidePanel();
    return <span data-testid="is-forward">{String(sp.isForward)}</span>;
  }

  function PanelSlot() {
    const [n, setN] = React.useState(0);
    const { position, lastDock } = useSidePanelLayout();
    // AppShell's own branching, verbatim: 'bottom-full' renders the panel at a
    // different point in the tree, so crossing that boundary REMOUNTS it. Keying on
    // it here is what lets a dock change and a remount happen in the same commit,
    // which is the case a reset written as a transition cannot see.
    const arrangement = position === 'floating' ? lastDock : position;
    return (
      <>
        <button type="button" data-testid="remount-panel" onClick={() => setN((v) => v + 1)} />
        <SidePanel key={`${arrangement}-${n}`} />
      </>
    );
  }

  function detachWithLiveContent() {
    mounts.count = 0;
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 300, top: 200, width: 520, height: 420 }));
    render(
      <SidePanelLayoutProvider>
        <SidePanelProvider>
          <DockToBottom />
          <LiveOpener />
          <ForwardProbe />
          <PanelSlot />
        </SidePanelProvider>
      </SidePanelLayoutProvider>,
    );
    act(() => { fireEvent.click(detachButton()!); });
    return panelBox();
  }

  /** The panel open with no tab at all - the collapsed row then has no label. */
  function renderPanelWithoutTab() {
    render(
      <SidePanelLayoutProvider>
        <SidePanelProvider>
          <OpenEmpty />
          <SidePanel />
        </SidePanelProvider>
      </SidePanelLayoutProvider>,
    );
  }
  function OpenEmpty() {
    const sp = useSidePanel();
    const done = React.useRef(false);
    React.useEffect(() => { if (!done.current) { done.current = true; sp.open(); } }, [sp]);
    return null;
  }

  const mounts = { count: 0 };
  function LiveTabContent() {
    React.useEffect(() => { mounts.count += 1; }, []);
    return <div>live-content</div>;
  }
  function LiveOpener() {
    const sp = useSidePanel();
    const done = React.useRef(false);
    const open = React.useCallback(() => {
      sp.openTab({
        id: 'workflow-1', label: 'WF', icon: <span />, content: <LiveTabContent />, keepMounted: true,
      } as SidePanelTab);
    }, [sp]);
    React.useEffect(() => {
      if (done.current) return;
      done.current = true;
      open();
    }, [open]);
    // Stand-ins for the two shapes a surface uses to bring the panel forward: a
    // fresh openTab, and re-activating a tab that is already there.
    return (
      <>
        <button type="button" data-testid="reopen" onClick={open} />
        <button
          type="button"
          data-testid="reactivate"
          onClick={() => { sp.setActiveTab('workflow-1'); sp.open(); }}
        />
        {/* The shape a live run uses: the panel is already open, so it only
            re-activates the tab and returns. */}
        {/* The bare open() three header toggles use on an already-open panel. */}
        <button type="button" data-testid="open-only" onClick={() => sp.open()} />
        <button
          type="button"
          data-testid="activate-only"
          onClick={() => { if (sp.isOpen) sp.setActiveTab('workflow-1'); }}
        />
      </>
    );
  }

  it('lifts the shade when the window is re-docked, and only then', () => {
    // A dock cannot render shaded, so leaving the floating position has to clear it.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(collapsedRow(), 'shaded to begin with').toBeTruthy();

    act(() => { fireEvent.click(screen.getByTestId('dock-to-bottom')); });
    act(() => { fireEvent.click(screen.getByTestId('dock-to-floating')); });

    expect(collapsedRow(), 're-docking left the shade behind').toBeNull();
  });

  it('does NOT expand a shaded window on a plain remount', () => {
    // The reset watches the floating -> docked TRANSITION, not the mount. AppShell
    // mounts the panel at a different tree position per dock branch, so an effect
    // that fired on mount would expand a window the user collapsed whenever
    // something unrelated flipped that branch - a state change from an event the
    // user cannot see.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(collapsedRow()).toBeTruthy();

    // Re-mount the panel while STILL detached, which is what a remount looks like.
    act(() => { fireEvent.click(screen.getByTestId('remount-panel')); });

    expect(collapsedRow(), 'the shade survived a remount, as the user left it').toBeTruthy();
  });

  it('never leaves a DOCKED panel shaded, even when re-docking remounts it', () => {
    // AppShell renders the panel in a different branch per dock, so `bottom` to
    // `bottom-full` REMOUNTS it. A reset written as a floating->docked transition
    // needs a ref seeded at mount; seeded `false` on a docked mount it never fires,
    // and `collapsed` is stranded true for the session. Nothing shows it - the
    // render masks the flag while docked - but `isForward` is then false app-wide:
    // the header button's first press does nothing visible, the preview cards lose
    // their "click to close" state, and the empty-canvas composer comes back on top
    // of the panel's own chat. Hence the assertion is on the CONTEXT, not the DOM.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(forwardProbe(), 'shaded: open but showing nothing').toBe('false');

    // Re-dock ACROSS the AppShell branch, so the dock change and the remount land in
    // the same commit and no instance ever observes the flip.
    act(() => { fireEvent.click(screen.getByTestId('dock-to-bottom-full')); });

    expect(forwardProbe(), 'a docked panel is stranded not-forward').toBe('true');
    expect(collapsedRow(), 'and nothing is shaded on screen either').toBeNull();
  });

  it('shrinks to one small row, and the row says which tab it is', () => {
    const box = detachWithLiveContent();
    expect(px(box.style.height), 'sized by the user to begin with').toBe(420);

    act(() => { fireEvent.click(collapseBtn()!); });

    // Two-sided: a strip too small to read or hit is as wrong as one too big.
    // The lower bound is 140, not the 200 it started at: at 260 the strip read as
    // a window that had not really collapsed, and narrowing it was the point. 140
    // still clears the chevron (14px), the close button (28px) and the row padding
    // with about 80px left for the name - a truncated label, which is what the
    // tooltip and the expanded window are for.
    expect(px(box.style.height), 'a single row').toBe(COLLAPSED_H);
    expect(px(box.style.width), 'no wider than a label needs').toBe(COLLAPSED_W);
    expect(COLLAPSED_W, 'but wide enough for one').toBeGreaterThanOrEqual(140);
    expect(COLLAPSED_H, 'and tall enough to hit').toBeGreaterThanOrEqual(32);
    expect(collapsedRow()).toBeTruthy();
    expect(collapsedRow()!.textContent, 'a nameless strip is unidentifiable').toContain('WF');
  });

  it('plays a cue on the row, and replays it on every collapse', () => {
    // A 36px strip at the edge of the screen is easy to lose - the reason the cue
    // exists at all. It is a CSS animation on mount, which is why the second half
    // of this test matters: the collapsed row must be a genuinely NEW element each
    // time, or the cue fires once per session and the second collapse is silent.
    // Folding the row into the tab-bar branch (the tempting simplification) would
    // let React reuse the node and break exactly that, with nothing else to show
    // for it.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const first = collapsedRow() as HTMLElement;
    expect(first.className, 'no cue on the strip').toContain('side-panel-collapse-hint');
    expect(
      first.querySelector('.side-panel-collapse-hint-chevron'),
      'the chevron carries the verb, so it moves too',
    ).toBeTruthy();

    act(() => { fireEvent.click(expandBtn() as HTMLElement); });
    act(() => { fireEvent.click(collapseBtn()!); });

    expect(collapsedRow(), 'row survived the expand, so the cue cannot replay').not.toBe(first);
    expect((collapsedRow() as HTMLElement).className).toContain('side-panel-collapse-hint');
  });

  it('defines the cue with a reduced-motion variant that still says where the strip is', () => {
    // jsdom runs no animation, so the stylesheet is the only place this can be
    // checked - and the reduced-motion half is the one that rots silently. It is
    // deliberately not `animation: none`: someone who asked for less motion has
    // the same trouble finding a 36px strip, so they get a flat ring held for the
    // same window instead of a beating one.
    const css = readFileSync(resolve(__dirname, '../../../app/globals.css'), 'utf-8');
    expect(css).toContain('@keyframes side-panel-collapse-hint');
    expect(css).toContain('@keyframes side-panel-collapse-hint-chevron');
    const reduced = css.slice(css.indexOf('.side-panel-collapse-hint {'));
    const block = reduced.slice(reduced.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(block.slice(0, 400), 'reduced motion left with no cue at all').toContain('side-panel-collapse-hint-hold');
    expect(block.slice(0, 400), 'the chevron still bobs').toContain('animation: none');
  });

  it('comes back to the EXACT window that was sized, from a real tap on the row', () => {
    // A bare `fireEvent.click` here would carry `detail: 0` and take the KEYBOARD
    // branch, so the name would promise a pointer path the test never walks: the
    // whole pointerup registration could be deleted and this would stay green.
    const box = detachWithLiveContent();
    const before = { w: px(box.style.width), h: px(box.style.height), x: px(box.style.left), y: px(box.style.top) };

    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;
    const press = new MouseEvent('pointerdown', { bubbles: true, clientX: 420, clientY: 260, button: 0 });
    Object.defineProperty(press, 'pointerId', { value: 3 });
    act(() => { row.dispatchEvent(press); });
    endDrag([421, 261]);

    // Exact, not approximate: collapsing renders a fixed compact box and leaves
    // the stored rect alone, so there is nothing to recompute on the way back.
    expect(px(box.style.width)).toBe(before.w);
    expect(px(box.style.height)).toBe(before.h);
    expect(px(box.style.left)).toBe(before.x);
    expect(px(box.style.top)).toBe(before.y);
  });

  it('hides the tab content without unmounting it, so a running tab survives', () => {
    // Collapsing is a render mode. If it unmounted the tab it would drop exactly
    // what detaching is built to preserve: a running canvas, an SSE stream, an iframe.
    detachWithLiveContent();
    const baseline = mounts.count;

    const content = screen.getByText('live-content').closest('[class*="flex-1"]')!.parentElement!;

    act(() => { fireEvent.click(collapseBtn()!); });
    expect(mounts.count, 'collapsing remounted the tab').toBe(baseline);
    expect(screen.getByText('live-content')).toBeTruthy();
    // HIDDEN, not merely still mounted: left visible it shares the 36px card with
    // the collapsed row, squashing the row to a sliver and painting a slice of the
    // tab underneath it. Every other assertion here is blind to that.
    expect(content.style.display).toBe('none');

    act(() => { fireEvent.click(expandBtn()!); });
    expect(mounts.count, 'expanding remounted the tab').toBe(baseline);
    expect(content.style.display).toBe('');
  });

  it('can be dragged into a corner - it clamps against the strip, not the window it will become', () => {
    // The move clamps `rect`, which still holds the EXPANDED size. Clamping against
    // that reserves room for a window that is not on screen, and the strip stops
    // short of the right and bottom edges by its own expanded size: it cannot be
    // parked in a corner, which is the whole reason to collapse one.
    const box = detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;

    // Far past both edges, so the clamp is what stops it and the assertion
    // discriminates between clamping on the strip and clamping on the window.
    pointerDrag(row, [400, 250], [4000, 4000]);
    endDrag();

    expect(px(box.style.left) + px(box.style.width), 'reaches the right edge').toBe(1600);
    expect(px(box.style.top) + px(box.style.height), 'and the bottom edge').toBe(900);
  });

  it('expands from a tap even when no click event is ever dispatched', () => {
    // A click needs press and release on the SAME element, and pressing the row
    // mounts a full-viewport drag overlay on the spot. Pointer capture normally
    // retargets the release back to the row, but capture is best-effort - refused,
    // the release lands on the overlay, no click is dispatched, and the strip could
    // not be expanded by tapping at all. That is the only gesture it has, so the
    // decision is taken on the window's pointerup instead.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;

    const press = new MouseEvent('pointerdown', { bubbles: true, clientX: 400, clientY: 250, button: 0 });
    Object.defineProperty(press, 'pointerId', { value: 3 });
    act(() => { row.dispatchEvent(press); });
    const release = new MouseEvent('pointerup', { bubbles: true, clientX: 402, clientY: 251, button: 0 });
    Object.defineProperty(release, 'pointerId', { value: 3 });
    act(() => { window.dispatchEvent(release); });
    // Deliberately NO click event: that is the whole point of the case.

    expect(collapsedRow(), 'the tap did nothing').toBeNull();
  });

  it('drops a press the window loses focus during, instead of arming it forever', () => {
    // The drag tears itself down on blur and delivers no pointerup, so a listener
    // that only removes itself from inside its own handler outlives the gesture.
    // The next unrelated release near the forgotten press point then expands the
    // window out of nowhere - and every such press leaks a pair for the session.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;

    const press = new MouseEvent('pointerdown', { bubbles: true, clientX: 400, clientY: 250, button: 0 });
    Object.defineProperty(press, 'pointerId', { value: 3 });
    act(() => { row.dispatchEvent(press); });
    act(() => { window.dispatchEvent(new Event('blur')); });

    // An unrelated release, later, right where the abandoned press was.
    const stray = new MouseEvent('pointerup', { bubbles: true, clientX: 401, clientY: 250, button: 0 });
    Object.defineProperty(stray, 'pointerId', { value: 3 });
    act(() => { window.dispatchEvent(stray); });

    expect(collapsedRow(), 'a stray release expanded the window').toBeTruthy();
  });

  it('is not disarmed by a second finger lifting first', () => {
    // Tablets are half of what this window is for. A stray pointer releasing early
    // must not consume the real press: the id test has to come before the teardown.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;

    const press = new MouseEvent('pointerdown', { bubbles: true, clientX: 400, clientY: 250, button: 0 });
    Object.defineProperty(press, 'pointerId', { value: 3 });
    act(() => { row.dispatchEvent(press); });
    const otherFinger = new MouseEvent('pointerup', { bubbles: true, clientX: 900, clientY: 900, button: 0 });
    Object.defineProperty(otherFinger, 'pointerId', { value: 9 });
    act(() => { window.dispatchEvent(otherFinger); });

    const realRelease = new MouseEvent('pointerup', { bubbles: true, clientX: 401, clientY: 251, button: 0 });
    Object.defineProperty(realRelease, 'pointerId', { value: 3 });
    act(() => { window.dispatchEvent(realRelease); });

    expect(collapsedRow(), 'the real tap was swallowed by another finger').toBeNull();
  });

  it('ignores a second finger landing on the strip mid-drag', () => {
    // The second press cannot start a drag (the first one holds it), so if it armed
    // its own tap it would release almost where it landed, pass the 4px test, and
    // expand the window while finger one is still moving it - and disarm finger one
    // on the way in.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;

    const first = new MouseEvent('pointerdown', { bubbles: true, clientX: 400, clientY: 250, button: 0 });
    Object.defineProperty(first, 'pointerId', { value: 3 });
    act(() => { row.dispatchEvent(first); });
    const second = new MouseEvent('pointerdown', { bubbles: true, clientX: 500, clientY: 260, button: 0 });
    Object.defineProperty(second, 'pointerId', { value: 9 });
    act(() => { row.dispatchEvent(second); });
    const secondUp = new MouseEvent('pointerup', { bubbles: true, clientX: 501, clientY: 261, button: 0 });
    Object.defineProperty(secondUp, 'pointerId', { value: 9 });
    act(() => { window.dispatchEvent(secondUp); });

    expect(collapsedRow(), 'a second finger expanded the window mid-drag').toBeTruthy();

    // ...and finger one's own tap still works.
    const firstUp = new MouseEvent('pointerup', { bubbles: true, clientX: 401, clientY: 251, button: 0 });
    Object.defineProperty(firstUp, 'pointerId', { value: 3 });
    act(() => { window.dispatchEvent(firstUp); });
    expect(collapsedRow(), 'the finger that was actually pressing lost its tap').toBeNull();
  });

  it('treats a cancelled pointer as no tap at all', () => {
    // A system gesture taking the pointer over is not the user asking for anything.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;

    const press = new MouseEvent('pointerdown', { bubbles: true, clientX: 400, clientY: 250, button: 0 });
    Object.defineProperty(press, 'pointerId', { value: 3 });
    act(() => { row.dispatchEvent(press); });
    const cancel = new MouseEvent('pointercancel', { bubbles: true, button: 0 });
    Object.defineProperty(cancel, 'pointerId', { value: 3 });
    act(() => { window.dispatchEvent(cancel); });

    expect(collapsedRow(), 'a cancelled gesture expanded the window').toBeTruthy();
  });

  it('does not expand when the row was DRAGGED rather than clicked', () => {
    // The row is both the drag surface and the expand control, so a move must not
    // also reopen the window under the cursor.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;

    pointerDrag(row, [400, 250], [500, 300]);
    // Released WHERE the drag ended, not at (0,0): a release at the origin reads as
    // a 471px move whatever the drag did, so the test would still pass with the
    // 4px threshold raised to 200 and would not be measuring its own scenario.
    endDrag([500, 300]);
    // `detail: 1` = a real pointer click, which is what a browser sends after a
    // drag. A synthetic click with detail 0 is a keyboard activation, and those
    // deliberately bypass the movement guard.
    act(() => { fireEvent.click(row, { clientX: 500, clientY: 300, detail: 1 }); });

    expect(collapsedRow(), 'still collapsed').toBeTruthy();
  });

  it('expands from a keyboard activation even after a drag that never produced a click', () => {
    // A drag aborted by the window losing focus delivers no pointerup and no click,
    // so the press it recorded survives. A keyboard Enter then arrives at (0,0),
    // reads as a 400px move, and would be silently swallowed.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;
    pointerDrag(row, [400, 250], [500, 300]);
    act(() => { window.dispatchEvent(new Event('blur')); });

    act(() => { fireEvent.click(row); }); // detail 0 = keyboard

    expect(collapsedRow(), 'the keyboard activation was dropped').toBeNull();
  });

  it('lifts the shade when something in the app opens a tab in the panel', () => {
    // THE failure this mode can produce: the user collapses the window, then clicks
    // a workflow card. The panel is already open, so nothing about `isOpen` changes;
    // the tab is already active, so nothing about the tab id changes either. The
    // click only relabelled a 36px strip while the content it opened sat hidden
    // underneath, and nothing on screen said anything had happened.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(collapsedRow()).toBeTruthy();

    act(() => { fireEvent.click(screen.getByTestId('reopen')); });

    expect(collapsedRow(), 'the panel stayed a strip').toBeNull();
    expect(screen.getByTestId('side-panel-tab')).toBeTruthy();
    expect(screen.getByText('live-content')).toBeTruthy();
  });

  it('lifts the shade when a surface re-activates a tab the panel already shows', () => {
    // The node Configuration / Conversation buttons take this shape: the tab
    // exists, so they updateTab + setActiveTab(sameId) + open(). The panel is
    // already open and the active tab does not change, so ONLY the open-request
    // counter moves - and without `open()` bumping it too, that click did nothing
    // visible against a collapsed window.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(collapsedRow()).toBeTruthy();

    act(() => { fireEvent.click(screen.getByTestId('reactivate')); });

    expect(collapsedRow(), 'the click did nothing visible').toBeNull();
  });

  it('moves the collapsed strip from the keyboard, and refuses to resize it', () => {
    // The strip advertises arrow keys and paints a FIXED box: a move must place it,
    // and a resize must be refused rather than silently shrinking the window the
    // user cannot currently see.
    const box = detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;
    const before = { x: px(box.style.left), y: px(box.style.top) };

    act(() => { fireEvent.keyDown(row, { key: 'ArrowLeft' }); });
    expect(px(box.style.left), 'the strip moves').toBe(before.x - 16);

    act(() => { fireEvent.keyDown(row, { key: 'ArrowRight', shiftKey: true }); });
    act(() => { fireEvent.click(expandBtn()!); });

    expect(px(box.style.width), 'the window kept the size it was given').toBe(520);
    expect(px(box.style.height)).toBe(420);
  });

  it('lifts the shade when a surface brings a tab forward with setActiveTab ALONE', () => {
    // The route a counter watched from the panel kept missing. A live run pausing
    // on an interface node, a trigger card, the application tab: all of them take
    // this branch precisely BECAUSE the panel is already open, which is exactly the
    // state a shaded window is in. Neither `isOpen` nor - for the tab already shown
    // - the active tab id moves, so the strip just sat there while the interface
    // waiting for the user's input stayed behind `display: none`.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });

    act(() => { fireEvent.click(screen.getByTestId('activate-only')); });

    expect(collapsedRow(), 'the run asked for attention and got a strip').toBeNull();
    expect(screen.getByText('live-content')).toBeTruthy();
  });

  it('lifts the shade on a bare open() of an already-open panel', () => {
    // Three header toggles do exactly this. `setIsOpen(true)` is a no-op there, so
    // without `open()` lifting the shade at the source those buttons would do
    // nothing at all against a collapsed window.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });

    act(() => { fireEvent.click(screen.getByTestId('open-only')); });

    expect(collapsedRow()).toBeNull();
  });

  it('hands focus over when the shade is lifted from OUTSIDE the panel', () => {
    // Collapsing focuses the row, and a live run pausing on an interface node
    // un-shades the window without touching either local control - so the focused
    // row unmounted under the user and dropped them on <body>, on the very route
    // the feature is built around. They then have to tab in from the top of the
    // document to reach the panel that just asked for their attention.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(document.activeElement, 'collapsing focuses the row').toBe(expandBtn());

    act(() => { fireEvent.click(screen.getByTestId('activate-only')); });

    expect(document.activeElement, 'dropped on the body').not.toBe(document.body);
    expect(document.activeElement).toBe(collapseBtn());
  });

  it('does nothing on Shift+Arrow, which the collapsed row does not advertise', () => {
    // The row lists the four arrows and no modifier, and the hook refuses a resize
    // under a painted box - so letting Shift through would MOVE the strip, an
    // unadvertised gesture rather than the no-op the control promises.
    const box = detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;
    const before = { x: px(box.style.left), y: px(box.style.top) };

    act(() => { fireEvent.keyDown(row, { key: 'ArrowLeft', shiftKey: true }); });
    act(() => { fireEvent.keyDown(row, { key: 'ArrowUp', shiftKey: true }); });

    expect(px(box.style.left)).toBe(before.x);
    expect(px(box.style.top)).toBe(before.y);
    // ...and the plain arrow still works, so the guard is not a blanket block.
    act(() => { fireEvent.keyDown(row, { key: 'ArrowLeft' }); });
    expect(px(box.style.left)).toBe(before.x - 16);
  });

  it('lifts the shade when the workspace re-hydrates a stored detach', () => {
    // The route the panel's own toggle never sees: switching workspace re-reads the
    // stored position, so a shade set in one workspace would follow the user into
    // another. Driven through the layout context, never through the detach button.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });

    act(() => { fireEvent.click(screen.getByTestId('dock-to-bottom')); });
    act(() => { fireEvent.click(screen.getByTestId('dock-to-floating')); });

    expect(panelBox().getAttribute('data-side-panel-floating')).toBe('true');
    expect(collapsedRow(), 'came back shaded from a flag set elsewhere').toBeNull();
  });

  it('is not offered in a shared conversation, like every other window control', () => {
    shared.value = { conversationId: 'c1' };
    renderPanel();
    expect(collapseBtn()).toBeNull();
  });

  it('keeps the strip on screen when the viewport shrinks under it', () => {
    const box = detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    pointerDrag(expandBtn() as HTMLElement, [400, 250], [4000, 4000]);
    endDrag();

    act(() => { setViewport(800, 500); window.dispatchEvent(new Event('resize')); });

    expect(px(box.style.left) + COLLAPSED_W, 'still reachable').toBeLessThanOrEqual(800);
    expect(px(box.style.top) + COLLAPSED_H).toBeLessThanOrEqual(500);
  });

  it('does not come back shaded after being closed from the collapsed row', () => {
    // Closing from the row is the natural way to dismiss a shaded window, and the
    // next open comes from anywhere in the app - a workflow card, an agent card,
    // the tab picker - none of which asked for a strip.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    act(() => { fireEvent.click(screen.getByTitle('close')); });

    act(() => { fireEvent.click(screen.getByTestId('reopen')); });

    expect(collapsedRow(), 'reopened shaded').toBeNull();
    expect(screen.getByTestId('side-panel-tab')).toBeTruthy();
  });

  it('announces the collapsed state as a disclosure pair, both controls naming the same region', () => {
    // Two buttons that hide and reveal the same thing. Without aria-expanded a
    // screen-reader user is not told the panel HAS a collapsed state; without
    // aria-controls the two are unrelated buttons that happen to swap places.
    detachWithLiveContent();
    expect(collapseBtn()!.getAttribute('aria-expanded')).toBe('true');
    const body = collapseBtn()!.getAttribute('aria-controls');
    expect(body).toBeTruthy();
    expect(document.getElementById(body!), 'it names a region that exists').toBeTruthy();

    act(() => { fireEvent.click(collapseBtn()!); });

    expect(expandBtn()!.getAttribute('aria-expanded')).toBe('false');
    expect(expandBtn()!.getAttribute('aria-controls')).toBe(body);
    // The row's only text is the TAB's label, and text content wins over `title`
    // for the accessible name - so without an explicit label a screen reader
    // announces "WF, button" and never says what the control DOES. The label
    // COMPOSES both: naming only the action would say "expand the panel" without
    // ever saying which panel, which the sighted user reads right there on the row.
    expect(expandBtn()!.getAttribute('aria-label'), 'the action').toContain('expandWindow');
    expect(expandBtn()!.getAttribute('aria-label'), 'and what it holds').toContain('WF');
    expect(expandBtn()!.textContent, 'while the tab it holds stays visible').toContain('WF');
    // Only the verbs it actually has: a resize cannot change a fixed-size strip.
    expect(expandBtn()!.getAttribute('aria-keyshortcuts')).not.toContain('Shift+');
    // The app clears every default outline, and this control is focused
    // programmatically on collapse - while shaded it IS the whole window, so it
    // has to bring its own indicator.
    expect(expandBtn()!.className, 'no visible focus state').toContain('focus-visible:ring-2');
    // The description's CONTENT is asserted separately: `useTranslations` is mocked
    // to return the key here, so nothing in this file can see the message itself.
  });

  it('takes focus when the collapsed row is grabbed, so its arrow keys stay live', () => {
    // Same trap as the title bar: the drag preventDefaults the pointerdown, which
    // suppresses the default focus, and the row wires the same keyboard handler.
    detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });
    const row = expandBtn() as HTMLElement;
    // Blurred first: collapsing already hands focus here, so without this the
    // assertion is satisfied by that handoff and the grab is never tested.
    act(() => { (document.activeElement as HTMLElement | null)?.blur(); });
    expect(document.activeElement).not.toBe(row);

    pointerDrag(row, [400, 250], [420, 250]);
    endDrag();

    expect(document.activeElement).toBe(row);
  });

  it('hands focus to the counterpart control, so a keyboard user is not dropped on the body', () => {
    detachWithLiveContent();
    collapseBtn()!.focus();
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(document.activeElement, 'the control that was activated is gone').toBe(expandBtn());

    act(() => { fireEvent.click(expandBtn()!); });
    expect(document.activeElement).toBe(collapseBtn());
  });

  it('stays usable when there is no tab to name', () => {
    // A panel opened with nothing in it: the row has no label, so the chevron and
    // the close control are all there is - and it must still expand.
    window.localStorage.setItem(RECT_KEY, JSON.stringify({ left: 300, top: 200, width: 520, height: 420 }));
    renderPanelWithoutTab();
    act(() => { fireEvent.click(detachButton()!); });
    act(() => { fireEvent.click(collapseBtn()!); });

    expect(collapsedRow()).toBeTruthy();
    expect(expandBtn()!.textContent, 'no invented label').toBe('');
    // ...and with no tab to name, the label is the bare action, not "action: ".
    expect(expandBtn()!.getAttribute('aria-label'), 'nothing appended').toBe('expandWindow');
    act(() => { fireEvent.click(expandBtn()!); });
    expect(collapsedRow()).toBeNull();
  });

  it('keeps a way to close the window while it is collapsed', () => {
    // The tab bar that normally carries close is not rendered here.
    const box = detachWithLiveContent();
    act(() => { fireEvent.click(collapseBtn()!); });

    act(() => { fireEvent.click(screen.getByTitle('close')); });

    expect(px(box.style.width), 'the panel closed').toBe(0);
  });

  it('drops every resize grip while collapsed - there is nothing to resize', () => {
    detachWithLiveContent();
    expect(grips()).toHaveLength(8);
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(grips()).toHaveLength(0);
    // The strip is not rendered at this size either - the ROW is the drag surface
    // there, which is both less chrome and a bigger target than a 12px band.
    expect(titleBar()).toBeNull();
    expect(expandBtn()!.className).toContain('cursor-grab');
  });

  it('is not offered on a docked panel, and a dock never renders shaded', () => {
    // A dock has no window state to shade, so the collapsed state is DERIVED from
    // being detached rather than standing on its own.
    renderPanel();
    expect(collapseBtn(), 'docked').toBeNull();

    act(() => { fireEvent.click(detachButton()!); });
    act(() => { fireEvent.click(collapseBtn()!); });
    expect(collapsedRow()).toBeTruthy();

    act(() => { fireEvent.click(screen.getByTestId('dock-to-bottom')); });

    expect(panelBox().getAttribute('data-side-panel-floating')).toBeNull();
    expect(collapsedRow(), 'a docked panel must not render shaded').toBeNull();
    expect(screen.getByTestId('side-panel-tab'), 'the tab bar is back').toBeTruthy();
  });

  it('does not come back shaded on the next detach', () => {
    // Docking through a header dock button does not go through the panel's own
    // toggle, so the flag would survive and the next detach would open as a strip
    // the user never asked to collapse.
    renderPanel();
    act(() => { fireEvent.click(detachButton()!); });
    act(() => { fireEvent.click(collapseBtn()!); });
    act(() => { fireEvent.click(screen.getByTestId('dock-to-bottom')); });

    act(() => { fireEvent.click(detachButton()!); });

    expect(panelBox().getAttribute('data-side-panel-floating')).toBe('true');
    expect(collapsedRow()).toBeNull();
    expect(grips(), 'a full window, grips and all').toHaveLength(8);
  });
});

describe('the collapsed window advertises only the verbs it has', () => {
  // `title` becomes the accessible DESCRIPTION once `aria-label` is present, so a
  // stale hint actively tells a screen-reader user about a keystroke that does
  // nothing. The collapsed strip paints a fixed box, so Shift+Arrow is refused
  // there by the hook - while the expanded title bar still resizes.
  const messages = ['en', 'fr', 'de', 'es', 'pt', 'zh'] as const;

  it.each(messages)('does not promise a resize on the collapsed row in %s', async (locale) => {
    const m = (await import(`@/messages/${locale}.json`)).default as {
      sidePanel: { expandWindowHint: string; moveWindowHint: string };
    };
    expect(m.sidePanel.expandWindowHint, `${locale} expand hint`).not.toMatch(/shift|maj|umschalt|mayús/i);
    // ...while the expanded strip, which CAN resize, still says so.
    expect(m.sidePanel.moveWindowHint, `${locale} move hint`).toMatch(/shift|maj|umschalt|mayús/i);
  });
});
