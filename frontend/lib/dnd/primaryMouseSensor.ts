import { MouseSensor } from '@dnd-kit/core';

/**
 * dnd-kit's mouse sensor, restricted to the PRIMARY button.
 *
 * <p>The stock {@code MouseSensor} starts a drag on any button but the right one, so a
 * middle-click (paste on Linux, autoscroll on Windows) or a mouse's back/forward button picks
 * a card up. The lists used {@code PointerSensor} before, which required
 * {@code isPrimary && button === 0}; splitting mouse and touch apart so a finger could get a
 * hold-to-drag sensor of its own would otherwise have widened what arms a drag as a side
 * effect nobody asked for.
 *
 * <p>The activator is the library's own, with the button test tightened and
 * {@code onActivation} still reported, so nothing else about starting a drag changes.
 *
 * <p>It EXTENDS a library class, so a test that replaces `@dnd-kit/core` wholesale
 * ({@code vi.mock('@dnd-kit/core', () => ({ ... }))}) must still name {@code MouseSensor}, or
 * this module throws "Class extends value undefined" the moment it is imported - far from the
 * test that caused it. Spreading {@code importOriginal()} avoids the question entirely.
 */
export class PrimaryMouseSensor extends MouseSensor {
  // The base class types its own activators narrowly; matching that type keeps `useSensor`
  // inferring the same options as the sensor this replaces.
  static activators: typeof MouseSensor.activators = [
    {
      eventName: 'onMouseDown',
      handler: ({ nativeEvent: event }, { onActivation }) => {
        if (event.button !== 0) return false;
        onActivation?.({ event });
        return true;
      },
    },
  ];
}
