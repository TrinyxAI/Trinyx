// @vitest-environment jsdom
/**
 * The bubble a row of node icons draws is square-rounded, on the SAME ladder as
 * the tile it wraps.
 *
 * The bug this pins: the three bubbles are 24 / 28 / 40px, and their rung was
 * hand-picked. The 28px one ("compact", used by the template previews and the
 * workflow board) had been given `rounded-xl` - 12px of corner on 14px of
 * half-height, which draws a CIRCLE. So the template gallery showed round icons
 * standing in a UI made of square tiles, while the 40px bubble two screens away
 * carried the same class and looked correctly square.
 *
 * Hence the rule tested here: the rung comes from the bubble's own height
 * (`nodeIconBoxRadiusClass`), never from an eye-picked class, and the corner
 * stays small enough relative to the box that it cannot read as a capsule.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

vi.mock('@/app/workflows/builder/components/nodes/shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/app/workflows/builder/components/nodes/shared')>();
  // Only the tile is stubbed: this file is about the bubble AROUND it, and the
  // tile's own shape is pinned by nodeIconShape.test.tsx. The radius helper is
  // the real one, so a change to the ladder reaches this test.
  return { ...actual, NodeIcon: () => <span data-testid="tile" /> };
});

import { WorkflowNodeIcons } from '../WorkflowNodeIcons';
import { nodeIconBoxRadiusClass } from '@/app/workflows/builder/components/nodes/shared';

/** px value of each rung, to check the corner against the box it sits on. */
const RUNG_PX: Record<string, number> = {
  'rounded-md': 6,
  'rounded-lg': 8,
  'rounded-xl': 12,
  'rounded-2xl': 16,
};

const SIZES = [
  { size: 'inline', heightPx: 24 },
  { size: 'compact', heightPx: 28 },
  { size: 'default', heightPx: 40 },
] as const;

const ICONS = [
  { nodeId: 'mcp-slack', nodeKind: 'tool' },
  { nodeId: 'core-decision', nodeKind: 'core' },
];

function bubbles(size: (typeof SIZES)[number]['size']): HTMLElement[] {
  // totalNodeCount forces the "+N" chip, which shares the row and must match.
  render(<WorkflowNodeIcons nodeIcons={ICONS} totalNodeCount={5} size={size} />);
  return Array.from(screen.getAllByTestId('tile')[0].closest('div')!.children) as HTMLElement[];
}

function radiiOf(element: HTMLElement): string[] {
  return element.className.split(/\s+/).filter((c) => c.startsWith('rounded'));
}

afterEach(cleanup);

describe('WorkflowNodeIcons - the bubble is square-rounded', () => {
  it('is never a capsule, at any of the three sizes', () => {
    for (const { size } of SIZES) {
      for (const bubble of bubbles(size)) {
        expect(radiiOf(bubble), size).not.toContain('rounded-full');
      }
      cleanup();
    }
  });

  it('takes the rung its own height maps to, and only one rung', () => {
    for (const { size, heightPx } of SIZES) {
      for (const bubble of bubbles(size)) {
        const radii = radiiOf(bubble);
        expect(radii, size).toHaveLength(1);
        expect(radii[0], size).toBe(nodeIconBoxRadiusClass(heightPx));
      }
      cleanup();
    }
  });

  it('keeps the corner under a third of the box, so no bubble reads as a circle', () => {
    // The regression in one number: 12px of corner on the 28px bubble was 43%
    // of its height, against 25-30% everywhere else on the ladder.
    for (const { size, heightPx } of SIZES) {
      const radiusPx = RUNG_PX[nodeIconBoxRadiusClass(heightPx)];
      expect(radiusPx, size).toBeDefined();
      expect(radiusPx / heightPx, size).toBeLessThanOrEqual(1 / 3);
      cleanup();
    }
  });

  it('gives the "+N" overflow chip the same corner as the icons it follows', () => {
    // It sits in the same row, so a different rung there is just as visible as
    // a round icon among square ones.
    for (const { size } of SIZES) {
      const row = bubbles(size);
      const overflow = row[row.length - 1];
      expect(overflow.textContent, size).toMatch(/^\+\d+$/);
      expect(radiiOf(overflow), size).toEqual(radiiOf(row[0]));
      cleanup();
    }
  });
});
