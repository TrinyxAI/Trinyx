/**
 * "The fleet Edit button should look like the add-node button" is a property of
 * TWO files, so no test that renders only one of them can hold it. What made them
 * diverge in the first place was each writing its own class: a 36px square on the
 * workflow canvas, a 44px circle on the fleet canvas.
 *
 * This is the source-level half of the guard - it pins that both corner buttons
 * still go through `canvasChromePrimaryButtonClass`, which is what makes them one
 * shape. The shape ITSELF is asserted in canvas-chrome.test.ts, and the rendered
 * result of the workflow one in BuilderCanvas.toolboxOverlay.test.tsx.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const CALL_SITES = [
  {
    what: 'the workflow canvas add-node button',
    file: join(__dirname, '../../../app/workflows/builder/components/BuilderCanvas.tsx'),
  },
  {
    what: 'the agent-fleet canvas Edit button (fleet page AND agent side panel)',
    file: join(__dirname, '../../agent-fleet/AgentFleetCanvas.tsx'),
  },
];

describe('the accent corner button on a canvas', () => {
  for (const { what, file } of CALL_SITES) {
    const source = readFileSync(file, 'utf8');

    it(`${what} takes its shape from the shared helper`, () => {
      expect(source).toContain('canvasChromePrimaryButtonClass');
      expect(source).toContain("from '@/components/ui/canvas-chrome'");
    });

    it(`${what} no longer hand-writes the round FAB it used to be`, () => {
      // The two shapes that were actually there before, in the exact spellings
      // used: `w-9 h-9 rounded-xl p-0` and `w-11 h-11 rounded-full p-0`.
      expect(source).not.toMatch(/w-11 h-11 rounded-full/);
      expect(source).not.toMatch(/w-9 h-9 rounded-xl p-0/);
    });
  }
});
