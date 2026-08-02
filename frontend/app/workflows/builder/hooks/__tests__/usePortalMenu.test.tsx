// @vitest-environment jsdom
/**
 * Shared plumbing for the canvas dropdown menus. The canvas is draggable and
 * lives inside clipping parents, so these menus are portaled and positioned from
 * the trigger's client rect - which makes the placement math and the dismissal
 * listeners the parts worth pinning.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';

import { usePortalMenu, type PortalMenuPlacement } from '../usePortalMenu';

type Menu = ReturnType<typeof usePortalMenu>;

const TRIGGER_RECT = { left: 100, top: 400, width: 40, height: 20, bottom: 420, right: 140 };

const renderMenu = (placement: PortalMenuPlacement) => {
  const menu: { current: Menu | null } = { current: null };
  function Harness() {
    const value = usePortalMenu(placement);
    menu.current = value;
    return (
      <>
        <button ref={value.triggerRef} onClick={value.toggle} data-testid="trigger">open</button>
        {value.isVisible && (
          <div ref={value.menuRef} role="menu" style={value.menuStyle} data-testid="menu">item</div>
        )}
      </>
    );
  }
  const utils = render(<Harness />);
  // jsdom has no layout: pin a rect so the anchor math has real numbers.
  utils.getByTestId('trigger').getBoundingClientRect = () => TRIGGER_RECT as DOMRect;
  return { menu, ...utils };
};

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('usePortalMenu', () => {
  it('hangs a "below" menu under the trigger, centred on it', () => {
    const { getByTestId } = renderMenu('below');
    fireEvent.click(getByTestId('trigger'));

    const style = getByTestId('menu').style;
    expect(style.top).toBe('426px'); // bottom + 6
    expect(style.left).toBe('120px'); // left + width / 2
    expect(style.transform).toBe('translateX(-50%)');
  });

  it('hangs an "above" menu over the trigger, pulled up by its own height', () => {
    // The run-mode toolbar sits at the bottom of the canvas, so its menu must
    // open upwards or it would render off-screen.
    const { getByTestId } = renderMenu('above');
    fireEvent.click(getByTestId('trigger'));

    const style = getByTestId('menu').style;
    expect(style.top).toBe('394px'); // top - 6
    expect(style.left).toBe('120px');
    expect(style.transform).toBe('translate(-50%, -100%)');
  });

  it('re-anchors on each open, following a trigger that moved with the canvas', () => {
    const { getByTestId } = renderMenu('below');
    fireEvent.click(getByTestId('trigger'));
    expect(getByTestId('menu').style.top).toBe('426px');

    fireEvent.click(getByTestId('trigger'));
    getByTestId('trigger').getBoundingClientRect = () =>
      ({ ...TRIGGER_RECT, top: 100, bottom: 120 }) as DOMRect;
    fireEvent.click(getByTestId('trigger'));

    expect(getByTestId('menu').style.top).toBe('126px');
  });

  it('closes on a scroll anywhere, so the menu never floats away from its anchor', () => {
    const { getByTestId } = renderMenu('below');
    fireEvent.click(getByTestId('trigger'));
    expect(screen.getByRole('menu')).toBeTruthy();

    // Capture-phase listener: scrolls inside nested containers count too.
    act(() => { window.dispatchEvent(new Event('scroll')); });
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('closes on a window resize', () => {
    const { getByTestId } = renderMenu('below');
    fireEvent.click(getByTestId('trigger'));

    act(() => { window.dispatchEvent(new Event('resize')); });
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('detaches its dismiss listeners when closed and when unmounted while open', () => {
    // Counting add/remove pairs rather than "does not throw": a leaked listener
    // only setStates on an unmounted tree, which React 18 tolerates silently.
    const add = vi.spyOn(document, 'addEventListener');
    const remove = vi.spyOn(document, 'removeEventListener');
    const countKeydown = (spy: typeof add) =>
      spy.mock.calls.filter(([type]) => type === 'keydown').length;

    const { getByTestId, unmount } = renderMenu('below');
    fireEvent.click(getByTestId('trigger'));
    expect(countKeydown(add)).toBe(1);

    fireEvent.click(getByTestId('trigger'));
    expect(countKeydown(remove)).toBe(1);

    // Unmounting while OPEN must detach too (the canvas can swap modes mid-menu).
    fireEvent.click(getByTestId('trigger'));
    expect(countKeydown(add)).toBe(2);
    unmount();
    expect(countKeydown(remove)).toBe(2);
  });

  it('exposes close() so a picked item can dismiss the menu itself', () => {
    const { menu, getByTestId } = renderMenu('below');
    fireEvent.click(getByTestId('trigger'));
    expect(menu.current?.open).toBe(true);

    act(() => menu.current!.close());
    expect(menu.current?.open).toBe(false);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
