// @vitest-environment jsdom
/**
 * Which mouse button may start a drag.
 *
 * The defect this exists for: swapping `PointerSensor` for `MouseSensor` (so a finger could get
 * its own hold-to-drag sensor) also widened what arms a drag. `PointerSensor` required the
 * PRIMARY button; the stock `MouseSensor` accepts every button but the right one, so a
 * middle-click - paste on Linux, autoscroll on Windows - or a mouse's back/forward button
 * would pick a card up instead.
 */
import { describe, expect, it, vi } from 'vitest';
import { MouseSensor } from '@dnd-kit/core';
import { PrimaryMouseSensor } from '../primaryMouseSensor';

type Activator = {
  eventName: string;
  handler: (event: unknown, context: { onActivation?: (a: { event: unknown }) => void }) => boolean;
};

const activator = (sensor: { activators: unknown }) => (sensor.activators as Activator[])[0];
/** Arm the activator the way dnd-kit does: the press, plus the context it passes alongside. */
const arm = (sensor: { activators: unknown }, button: number, onActivation = vi.fn()) => ({
  started: activator(sensor).handler({ nativeEvent: { button } }, { onActivation }),
  onActivation,
});

describe('PrimaryMouseSensor', () => {
  it('starts a drag on the primary button', () => {
    expect(arm(PrimaryMouseSensor, 0).started).toBe(true);
  });

  it.each([
    [1, 'middle button'],
    [2, 'right button'],
    [3, 'back button'],
    [4, 'forward button'],
  ])('refuses button %i, the %s', (button) => {
    expect(arm(PrimaryMouseSensor, button).started).toBe(false);
  });

  it('is stricter than the sensor it replaces, which is the whole point', () => {
    // Pinning the contrast: the stock sensor accepts a middle-click, and that is what this
    // subclass exists to take back.
    expect(arm(MouseSensor, 1).started).toBe(true);
    expect(arm(PrimaryMouseSensor, 1).started).toBe(false);
  });

  it('still reports the activation, so nothing else about starting a drag changes', () => {
    const { onActivation } = arm(PrimaryMouseSensor, 0);

    expect(onActivation).toHaveBeenCalledWith({ event: { button: 0 } });
  });

  it('reports nothing when it refuses the press', () => {
    const { onActivation } = arm(PrimaryMouseSensor, 1);

    expect(onActivation).not.toHaveBeenCalled();
  });

  it('still listens on mousedown, so it stays a mouse sensor', () => {
    expect(activator(PrimaryMouseSensor).eventName).toBe('onMouseDown');
  });
});
