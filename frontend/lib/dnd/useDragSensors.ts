'use client';

import { TouchSensor, useSensor, useSensors } from '@dnd-kit/core';
import { PrimaryMouseSensor } from '@/lib/dnd/primaryMouseSensor';

/** How far a mouse travels before it means a drag rather than a click. */
export const MOUSE_DRAG_DISTANCE = 6;
/** How long a finger rests before it means a drag rather than a scroll. */
export const TOUCH_DRAG_DELAY_MS = 250;
/** How far that finger may wander while it waits. */
export const TOUCH_DRAG_TOLERANCE = 6;

/**
 * What it takes to pick a card up, on a grid of cards that are also clickable and scrollable.
 *
 * <p><b>Mouse:</b> a few pixels of travel, so a plain click still opens the card, and the
 * PRIMARY button only, so a middle-click or a mouse's back button does not pick anything up.
 *
 * <p><b>Touch:</b> a HOLD, not a distance. A finger cannot out-race the browser: within the
 * pixels a distance constraint waits for, the page has already claimed the gesture as a scroll
 * and fired `pointercancel`, which cancels the drag - so with one pointer sensor a card could
 * not be dragged with a finger at all. A quarter-second hold is unambiguous (a scroll starts
 * moving long before it), and the tolerance lets the finger wobble while it waits. Draggables
 * pair this with `touch-manipulation`, so the wait is not competing with a double-tap gesture
 * the browser might still claim.
 *
 * <p>Shared by every card grid that files things into folders, so the two surfaces cannot
 * drift into different ideas of what a drag is.
 */
export function useDragSensors() {
  return useSensors(
    useSensor(PrimaryMouseSensor, { activationConstraint: { distance: MOUSE_DRAG_DISTANCE } }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: TOUCH_DRAG_DELAY_MS, tolerance: TOUCH_DRAG_TOLERANCE },
    }),
  );
}
