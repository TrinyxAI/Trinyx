// @vitest-environment jsdom
/**
 * What it takes to pick a card up.
 *
 * Two defects the card grids shipped with, both from a single pointer sensor with a distance
 * constraint: a finger could not drag at all (the browser claimed the gesture as a scroll
 * within those pixels and fired `pointercancel`, cancelling the drag), and once the mouse got
 * its own sensor, every button but the right one armed a drag where only the primary used to.
 */
import { describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';

const registered = vi.hoisted(() => ({ calls: [] as Array<{ sensor: string; options: unknown }> }));

vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>();
  return {
    ...actual,
    useSensor: (sensor: { name: string }, options: unknown) => {
      registered.calls.push({ sensor: sensor.name, options });
      return { sensor, options };
    },
    useSensors: (...sensors: unknown[]) => sensors,
  };
});

import { useDragSensors } from '../useDragSensors';

function sensorsFor() {
  registered.calls = [];
  renderHook(() => useDragSensors());
  return registered.calls;
}

describe('useDragSensors', () => {
  it('asks a MOUSE for a few pixels of travel, so a click still opens the card', () => {
    const mouse = sensorsFor().find((s) => s.sensor === 'PrimaryMouseSensor');

    expect(mouse?.options).toEqual({ activationConstraint: { distance: 6 } });
  });

  it('asks a FINGER for a hold, which is the only gesture the browser will not take first', () => {
    const touch = sensorsFor().find((s) => s.sensor === 'TouchSensor');

    // A distance constraint on touch is the bug: the page claims the gesture as a scroll
    // within those pixels and cancels the drag. `toEqual` already excludes one.
    expect(touch?.options).toEqual({ activationConstraint: { delay: 250, tolerance: 6 } });
  });

  it('installs both, so a grid works with a mouse AND a finger', () => {
    expect(sensorsFor().map((s) => s.sensor).sort()).toEqual(['PrimaryMouseSensor', 'TouchSensor']);
  });

  it('uses the primary-button mouse sensor, not the stock one that accepts a middle-click', () => {
    expect(sensorsFor().map((s) => s.sensor)).not.toContain('MouseSensor');
  });
});
