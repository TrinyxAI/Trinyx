/**
 * @vitest-environment jsdom
 *
 * The epoch badge is an icon in a fixed-width slot. Two properties matter and neither
 * is visible in a snapshot: the slot exists for EVERY status (including none) so the
 * columns to its right never shift, and no status is allowed to render blank - a blank
 * slot is indistinguishable from "no status" while the tooltip beside it names one.
 */
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';

import { EpochStatusIcon } from '@/components/workflow/EpochStatusIcon';

function markOf(status: string | null | undefined, size?: 'xs' | 'sm'): { slot: Element; mark: string } {
  const { container } = render(<EpochStatusIcon status={status} size={size} />);
  const slot = container.firstElementChild!;
  const svg = slot.querySelector('svg');
  if (svg) return { slot, mark: svg.getAttribute('class') || '' };
  if (slot.querySelector('.animate-ping')) return { slot, mark: 'pulse' };
  return { slot, mark: slot.children.length ? 'dot' : '' };
}

describe('EpochStatusIcon', () => {
  afterEach(cleanup);

  it('gives each outcome its own colour', () => {
    expect(markOf('COMPLETED').mark).toContain('emerald');
    expect(markOf('FAILED').mark).toContain('red');
    expect(markOf('RUNNING').mark).toBe('pulse');
  });

  it('greys the statuses that mean the epoch was abandoned', () => {
    // Same family, same colour: killed mid-flight, not a verdict on the work.
    for (const status of ['CANCELLED', 'STOPPED', 'TIMEOUT']) {
      expect(markOf(status).mark, status).toContain('gray');
    }
  });

  it('never renders blank for a status it does not know', () => {
    // A status the backend adds later must still show something, or the row silently
    // claims the epoch has none.
    expect(markOf('SOME_NEW_STATUS').mark).toBe('dot');
  });

  it('renders the empty slot when there is genuinely no status', () => {
    const { slot, mark } = markOf(null);
    expect(mark).toBe('');
    // The slot itself stays, so nothing shifts when the status arrives.
    expect(slot.className).toContain('shrink-0');
    expect(slot.className).toMatch(/w-3/);
  });

  it('follows the row type scale it is placed in', () => {
    // AGENTS.md pairs h-3.5 with text-sm (the run panel) and h-3 with text-xs (the
    // application toolbar dropdown).
    expect(markOf('COMPLETED', 'sm').mark).toContain('h-3.5');
    expect(markOf('COMPLETED', 'xs').mark).toContain('h-3 ');
  });
});
