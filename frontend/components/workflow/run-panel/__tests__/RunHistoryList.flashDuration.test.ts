/**
 * The run-history focus cue is applied imperatively and removed on `animationend`
 * (see `scrollToAndFlash`), so the CSS is what ends it. That makes one stylesheet
 * property load-bearing and invisible from the DOM: the class MUST always name an
 * animation that terminates. An `animation: none` under `prefers-reduced-motion` -
 * the obvious way to honour that preference - would never fire `animationend` and
 * would leave a blue ring on the row for the rest of the session, for exactly the
 * users who asked for less motion.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// From the leaf module on purpose: this test needs a class name, not a React tree.
import { RUN_ROW_FLASH_CLASS } from '@/components/workflow/run-panel/runFormatting';

const css = readFileSync(join(process.cwd(), 'app', 'globals.css'), 'utf8');

describe('run-row focus flash - the stylesheet is what ends the cue', () => {
  it('animates the class the component applies', () => {
    const rule = css.match(new RegExp(`\\.${RUN_ROW_FLASH_CLASS}\\s*\\{([^}]*)\\}`));
    expect(rule, `no rule for .${RUN_ROW_FLASH_CLASS} in globals.css`).toBeTruthy();
    expect(rule![1]).toMatch(/animation:\s*run-row-focus-flash\s+[\d.]+s/);
    expect(css).toContain('@keyframes run-row-focus-flash');
  });

  it('keeps the reduced-motion variant an animation, so animationend still fires', () => {
    const reduced = css.match(
      new RegExp(`@media\\s*\\(prefers-reduced-motion:\\s*reduce\\)\\s*\\{\\s*\\.${RUN_ROW_FLASH_CLASS}\\s*\\{([^}]*)\\}`),
    );
    expect(reduced, 'reduced-motion override not found').toBeTruthy();
    expect(reduced![1]).toMatch(/animation:\s*run-row-focus-hold\s+[\d.]+s/);
    expect(css).toContain('@keyframes run-row-focus-hold');
  });

  it('never leaves the ring painted at the end of either animation', () => {
    // The class is pulled on `animationend`, so the last keyframe is the last
    // thing the user sees: it must already be transparent, or the ring snaps off
    // instead of fading.
    for (const name of ['run-row-focus-flash', 'run-row-focus-hold']) {
      const frames = css.match(new RegExp(`@keyframes ${name}\\s*\\{([\\s\\S]*?)\\n\\}`));
      expect(frames, `@keyframes ${name} not found`).toBeTruthy();
      expect(frames![1]).toMatch(/100%\s*\{[^}]*inset 0 0 0 0 rgba\(96, 165, 250, 0\)/);
    }
  });

  it('never persists the last frame past the animation', () => {
    // `forwards` would keep the final box-shadow applied AFTER animationend, and
    // the class removal that follows would then be what un-paints it - a visible
    // second step, on a cue whose whole point is to fade out by itself.
    for (const selector of [`\\.${RUN_ROW_FLASH_CLASS}`]) {
      for (const rule of css.matchAll(new RegExp(`${selector}\\s*\\{([^}]*)\\}`, 'g'))) {
        expect(rule[1]).not.toMatch(/animation-fill-mode/);
        expect(rule[1]).not.toMatch(/animation:[^;]*\b(forwards|both)\b/);
      }
    }
  });
});
