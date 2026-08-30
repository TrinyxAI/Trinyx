/**
 * The drag handle both floating inspector panels wear.
 *
 * It was `opacity-0` until the panel was hovered, which hides the only thing on
 * screen saying the panel can be moved: a user who never sweeps the pointer down
 * that edge never learns the feature exists, while the band swallowed presses
 * there the whole time. These pin the two properties that were traded away and
 * the one that must stay.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { panelDragHandleClass } from '../panel-drag-handle';

const source = (rel: string) => readFileSync(resolve(__dirname, '../../..', rel), 'utf-8');

const PANELS = [
  ['the workflow inspector', 'app/workflows/builder/components/inspector/InspectorPanelHeader.tsx'],
  ['the fleet inspector', 'components/agent-fleet/FleetInspectorPanel.tsx'],
] as const;

describe('panelDragHandleClass', () => {
  it('is visible without hovering anything', () => {
    expect(panelDragHandleClass, 'hidden until hover again').not.toContain('opacity-0');
    expect(panelDragHandleClass, 'gated on a hover group').not.toContain('group-hover');
  });

  it('still says it can be grabbed, and still gets out of the way on a phone', () => {
    // The cursor is the affordance now that nothing appears on hover, and the
    // handle has no keyboard route of its own - so losing it loses the hint.
    expect(panelDragHandleClass).toContain('cursor-grab');
    expect(panelDragHandleClass).toContain('active:cursor-grabbing');
    // Below lg neither panel floats, so a drag band there would sit over content
    // nobody can move.
    expect(panelDragHandleClass).toContain('hidden lg:flex');
  });

  it.each(PANELS)('%s wears it rather than its own copy', (_name, file) => {
    const src = source(file);
    expect(src, 'no longer uses the shared class').toContain('className={panelDragHandleClass}');
    // The two copies had already drifted in class order while meaning the same
    // thing; a hand-rolled handle is how the hover gate would come back on one
    // panel only.
    const handle = src.slice(src.indexOf('data-drag-handle'), src.indexOf('data-drag-handle') + 400);
    expect(handle, 'a hand-rolled hover gate is back').not.toContain('opacity-0');
  });
});
