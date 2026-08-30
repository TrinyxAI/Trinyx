import type React from 'react';

/**
 * Props for a control that sits ON a draggable card: they keep a press from arming the drag
 * underneath it, so the rename button renames instead of picking the card up.
 *
 * <p>It names every event a dnd-kit sensor can start on, because which one matters depends on
 * the sensors the surrounding list happens to use, and that is not visible from here.
 * {@code PointerSensor} activates on `pointerdown`, {@code MouseSensor} on `mousedown` and
 * {@code TouchSensor} on `touchstart` - so a guard written for one of them silently disarms
 * the day a list swaps its sensors, and the symptom is a button that drags the card away
 * instead of doing its job. Spreading this instead of writing one handler cannot go stale.
 *
 * <p>It stops PROPAGATION only, never the default: the control's own click must still fire.
 *
 * <p>React dispatches from the root container, so stopping propagation here also keeps the
 * press from reaching listeners bound on `document`. That is worth knowing before putting this
 * on a control that sits inside something dismissed by an outside-press handler: the press
 * would no longer dismiss it.
 *
 * <pre>{@code <button {...DRAG_GUARD_PROPS} onClick={rename}>}</pre>
 */
export const DRAG_GUARD_PROPS = {
  onPointerDown: (event: React.SyntheticEvent) => event.stopPropagation(),
  onMouseDown: (event: React.SyntheticEvent) => event.stopPropagation(),
  onTouchStart: (event: React.SyntheticEvent) => event.stopPropagation(),
} as const;
