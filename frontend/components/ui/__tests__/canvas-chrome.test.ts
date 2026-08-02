/**
 * The floating chrome on the workflow canvas - the edit/run mode toggle, the run
 * identity bar and the bottom toolbar - was the last place in the app still drawn
 * as pills with hand-rolled hover colors. `canvas-chrome` is what moved it onto
 * the Button system, so these tests pin the two properties that make that true
 * and would silently rot otherwise:
 *
 *  - it stays COMPOSED from `buttonVariants` (shape, height, motion, focus ring
 *    inherited, never restated);
 *  - it stays SQUARE and on the same surface ladder as the panel tabs, so the
 *    canvas and the side panel read as one system rather than two.
 */
import { describe, expect, it } from 'vitest';
import { buttonVariants } from '@/components/ui/button';
import { panelTabClass } from '@/components/ui/panel-tab';
import {
  CANVAS_CHROME_CARD_PADDING_PX,
  CANVAS_CHROME_TOP_ROW_HEIGHT_PX,
  canvasChromeButtonClass,
  canvasChromeChipRadiusClass,
  canvasChromeSurfaceClass,
  canvasChromeCompactButtonClass,
} from '@/components/ui/canvas-chrome';

/** Classes twMerge is expected to drop when the ghost hover is overridden. */
const GHOST_INVERTED_HOVER = ['hover:bg-[var(--text-primary)]', 'hover:text-[var(--bg-primary)]'];

/**
 * Whole-token membership, NOT substring. `toContain('bg-[var(--bg-hover)]')` is
 * also satisfied by `hover:bg-[var(--bg-hover)]`, so a control that lost its
 * resting fill and only paints on hover - i.e. no visible active state - would
 * pass a substring assertion.
 */
function tokens(className: string): string[] {
  return className.split(/\s+/).filter(Boolean);
}

describe('canvasChromeButtonClass', () => {
  it('inherits the Button base tokens, and fails if Button drops or renames one', () => {
    // Pins the REMOVAL direction too: if Button drops a token while the chrome
    // keeps it, the chrome has stopped composing and is a stale copy.
    const inherited = [
      'rounded-xl',
      'transition-colors',
      'duration-150',
      'focus-visible:outline-none',
      'focus-visible:ring-2',
      'focus-visible:ring-[var(--accent-primary)]',
      'focus-visible:ring-offset-1',
      'focus-visible:ring-offset-[var(--bg-primary)]',
      'disabled:pointer-events-none',
      'disabled:opacity-60',
    ];
    const base = buttonVariants();
    for (const token of inherited) {
      expect(base, `Button lost ${token} - canvas chrome inherits it`).toContain(token);
      expect(canvasChromeButtonClass(true)).toContain(token);
      expect(canvasChromeButtonClass(false)).toContain(token);
    }
  });

  it('is square - the whole point of the change - in both states', () => {
    for (const active of [true, false]) {
      expect(tokens(canvasChromeButtonClass(active))).not.toContain('rounded-full');
    }
  });

  it('keeps the single Button control height and a square footprint', () => {
    // h-9/w-9 is also what makes every canvas control a comfortable touch target
    // on a phone; the circles it replaced were 28-32px.
    for (const active of [true, false]) {
      const t = tokens(canvasChromeButtonClass(active));
      expect(t).toContain('h-9');
      expect(t).toContain('w-9');
      expect(t).toContain('p-0');
    }
  });

  it('never shrinks, so the toolbar scrolls instead of squashing its icons', () => {
    expect(tokens(canvasChromeButtonClass(false))).toContain('shrink-0');
  });

  it('active control has a RESTING fill, not just a hover one', () => {
    const active = tokens(canvasChromeButtonClass(true));
    expect(active).toContain('bg-[var(--bg-hover)]');
    expect(active).not.toContain('bg-transparent');
    expect(active).toContain('text-[var(--text-primary)]');
    // A solid accent fill is too heavy for a bar of canvas controls.
    expect(active).not.toContain('bg-[var(--accent-primary)]');
  });

  it('active control carries the hairline outline that reads in light mode', () => {
    // --bg-tertiary (#eceff3) vs --bg-hover (#e5e7eb) is about one perceptible
    // step in light mode, so the fill alone does not carry the state.
    expect(tokens(canvasChromeButtonClass(true))).toContain('border-[var(--border-color)]');
    expect(tokens(canvasChromeButtonClass(false))).not.toContain('border-[var(--border-color)]');
  });

  it('active control keeps its surface on hover instead of drifting', () => {
    const active = canvasChromeButtonClass(true);
    expect(active).toContain('hover:bg-[var(--bg-hover)]');
    expect(active).toContain('hover:text-[var(--text-primary)]');
  });

  it('inactive control starts from ghost but replaces its inverted hover', () => {
    const inactive = canvasChromeButtonClass(false);
    expect(inactive).toContain('bg-transparent');
    expect(inactive).toContain('text-[var(--text-secondary)]');
    expect(inactive).toContain('hover:bg-[var(--bg-tertiary)]');
    // The ghost variant's legacy dark-surface hover must NOT survive the merge:
    // it would flip an idle toolbar button to a near-black chip on hover.
    for (const token of GHOST_INVERTED_HOVER) {
      expect(buttonVariants({ variant: 'ghost' }), `ghost lost ${token}`).toContain(token);
      expect(inactive).not.toContain(token);
      expect(canvasChromeButtonClass(true), `active also overrides ${token}`).not.toContain(token);
    }
  });

  it('keeps the surface ladder ordered: an inactive control never wears the active surface', () => {
    expect(canvasChromeButtonClass(false)).not.toContain('bg-[var(--bg-hover)]');
  });

  it('is the SAME surface ladder as the panel tabs, so canvas and panel read as one system', () => {
    // Two helpers, one palette. If either side re-picks its fills, this fails -
    // which is the point: the drift is what made the app look like two apps.
    for (const token of ['bg-[var(--bg-hover)]', 'border-[var(--border-color)]', 'hover:bg-[var(--bg-hover)]']) {
      expect(tokens(panelTabClass(true))).toContain(token);
      expect(tokens(canvasChromeButtonClass(true))).toContain(token);
    }
    for (const token of ['bg-transparent', 'text-[var(--text-secondary)]', 'hover:bg-[var(--bg-tertiary)]']) {
      expect(tokens(panelTabClass(false))).toContain(token);
      expect(tokens(canvasChromeButtonClass(false))).toContain(token);
    }
  });

  it('lets a caller override the surface through the className slot', () => {
    // The standalone history chip floats on the canvas with no card under it, so
    // it has to paint its own surface. Passing it through the slot (rather than
    // concatenating) is what lets twMerge resolve the two background layers.
    const t = tokens(canvasChromeButtonClass(false, 'bg-[var(--bg-primary)]/95'));
    expect(t).toContain('bg-[var(--bg-primary)]/95');
    expect(t).not.toContain('bg-transparent');
    // The state's hover survives: it is a different twMerge group.
    expect(t).toContain('hover:bg-[var(--bg-tertiary)]');
  });
});

describe('canvasChromeSurfaceClass', () => {
  it('is a square card, one radius step above the controls it holds', () => {
    const t = tokens(canvasChromeSurfaceClass);
    expect(t).not.toContain('rounded-full');
    expect(t).toContain('rounded-2xl');
    expect(tokens(canvasChromeButtonClass(false))).toContain('rounded-xl');
  });

  it('reads as a surface over a busy canvas: hairline border, translucent, blurred', () => {
    const t = tokens(canvasChromeSurfaceClass);
    expect(t).toContain('border');
    expect(t).toContain('border-[var(--border-color)]');
    expect(t).toContain('bg-[var(--bg-primary)]/95');
    expect(t).toContain('backdrop-blur');
  });

  it('uses theme tokens, not the hardcoded white/gray-800 pair it replaced', () => {
    // The old chrome was `bg-white dark:bg-gray-800`, which ignored the palette.
    expect(canvasChromeSurfaceClass).not.toContain('bg-white');
    expect(canvasChromeSurfaceClass).not.toContain('dark:bg-gray-800');
  });

  it('is flat - the hairline border separates it from the canvas, a shadow would make it a bubble', () => {
    expect(canvasChromeSurfaceClass).not.toContain('shadow');
  });
});

describe('canvasChromeCompactButtonClass', () => {
  it('is the same control, only smaller: one size token apart from the standard one', () => {
    // Composition, not a second style. Everything except the footprint has to
    // survive, or a nested control stops being a chrome control.
    for (const active of [true, false]) {
      const compact = tokens(canvasChromeCompactButtonClass(active));
      const standard = tokens(canvasChromeButtonClass(active));
      expect(compact).toContain('h-7');
      expect(compact).toContain('w-7');
      expect(compact).not.toContain('h-9');
      expect(compact).not.toContain('w-9');
      // Same state and shape tokens on both.
      for (const token of standard.filter(t => t !== 'h-9' && t !== 'w-9')) {
        expect(compact, `compact control lost ${token}`).toContain(token);
      }
    }
  });

  it('makes every card that wraps it land on the one shared chrome height', () => {
    // p-1 (4+4) + h-7 (28) = 36, which is also py-2 (8+8) + a text-xs chip (20).
    // At the standard h-9 the toggle was a 44px block and the toolbar a 48px slab.
    expect(CANVAS_CHROME_CARD_PADDING_PX + 28 + CANVAS_CHROME_CARD_PADDING_PX).toBe(CANVAS_CHROME_TOP_ROW_HEIGHT_PX);
    expect(8 + 20 + 8).toBe(CANVAS_CHROME_TOP_ROW_HEIGHT_PX);
  });
});

describe('canvasChromeChipRadiusClass', () => {
  it('is neither a pill nor the control radius - the info chips are smaller than a button', () => {
    expect(canvasChromeChipRadiusClass).toBe('rounded-md');
  });
});
