/**
 * Shared style for the drag handle on a floating panel: the band down the left
 * edge that the workflow inspector and the fleet inspector are moved by.
 *
 * One constant rather than the same string twice, because the two had already
 * drifted apart in class ORDER while meaning the same thing, and the next edit
 * to either would have landed on one of them only.
 *
 * It is deliberately NOT hover-gated. Both handles used to be `opacity-0` until
 * the panel was hovered, which hides the only affordance saying the panel can be
 * moved at all: a user who never happens to sweep the pointer along that edge
 * never learns the panel is movable, and the band swallowed presses there
 * regardless. Visible costs one 16px glyph; hidden costs the feature.
 *
 * Desktop only (`hidden lg:flex`): below that breakpoint the panels are not
 * free-floating, so there is nothing to drag.
 */
export const panelDragHandleClass =
  'hidden lg:flex absolute left-0 top-0 bottom-0 w-6 items-center justify-center rounded-l-[32px] '
  + 'cursor-grab active:cursor-grabbing transition-colors hover:bg-slate-100 dark:hover:bg-slate-800';
