// @vitest-environment jsdom
/**
 * The props that keep a control on a draggable card from picking the card up.
 *
 * The defect they exist for: which press starts a drag depends on the SENSORS the surrounding
 * list uses, and a card cannot see those. A guard written for `pointerdown` went dead the day
 * a list swapped `PointerSensor` for `MouseSensor` + `TouchSensor`, and pressing Rename began
 * dragging the folder away instead of renaming it.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { DRAG_GUARD_PROPS } from '../dragGuards';

afterEach(() => cleanup());

function renderGuarded(onCardPress: () => void, onClick = vi.fn()) {
  render(
    <div onPointerDown={onCardPress} onMouseDown={onCardPress} onTouchStart={onCardPress}>
      <button type="button" {...DRAG_GUARD_PROPS} onClick={onClick}>
        Rename
      </button>
    </div>,
  );
  return screen.getByRole('button', { name: 'Rename' });
}

describe('DRAG_GUARD_PROPS', () => {
  it.each(['pointerDown', 'mouseDown', 'touchStart'] as const)(
    'stops %s from reaching the card underneath, whichever sensor the list uses',
    (event) => {
      const onCardPress = vi.fn();
      const button = renderGuarded(onCardPress);

      fireEvent[event](button);

      expect(onCardPress).not.toHaveBeenCalled();
    },
  );

  it('leaves the control itself working: it guards propagation, not the press', () => {
    const onClick = vi.fn();
    const button = renderGuarded(vi.fn(), onClick);

    fireEvent.click(button);

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('never prevents the default, so a focus or a native control still behaves', () => {
    const button = renderGuarded(vi.fn());

    const prevented = !fireEvent.mouseDown(button);

    expect(prevented).toBe(false);
  });
});

describe('DRAG_GUARD_PROPS - what it costs', () => {
  it('also keeps the press from reaching listeners bound on the document', () => {
    // React dispatches from the root container, so stopping propagation here stops the native
    // event before `document` sees it. That is the price of guarding `mousedown`, which the
    // mouse sensor activates on: a menu that dismisses on an outside bubble-phase press will
    // not close when one of these controls is pressed. Pinned so the trade is visible rather
    // than rediscovered.
    const onOutsidePress = vi.fn();
    document.addEventListener('mousedown', onOutsidePress);
    try {
      const button = renderGuarded(vi.fn());

      fireEvent.mouseDown(button);

      expect(onOutsidePress).not.toHaveBeenCalled();
    } finally {
      document.removeEventListener('mousedown', onOutsidePress);
    }
  });

  it('leaves a CAPTURE-phase dismisser working, which is the way round it', () => {
    const onOutsidePress = vi.fn();
    document.addEventListener('mousedown', onOutsidePress, true);
    try {
      const button = renderGuarded(vi.fn());

      fireEvent.mouseDown(button);

      expect(onOutsidePress).toHaveBeenCalled();
    } finally {
      document.removeEventListener('mousedown', onOutsidePress, true);
    }
  });
});
