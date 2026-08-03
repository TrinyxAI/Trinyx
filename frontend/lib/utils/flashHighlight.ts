/**
 * One-shot "here is what you were looking at" highlight.
 *
 * Scrolls an element into view and (re)plays a CSS animation on it. Two details
 * make it a shared helper rather than three lines inlined per surface:
 *
 *  - the class is REMOVED and re-added around a forced reflow, because a browser
 *    does not restart an animation that is already on an element - without it,
 *    highlighting the same element twice plays nothing the second time;
 *  - it is removed again on `animationend`, so the cue ends with the animation
 *    and nothing has to mirror its duration in JS.
 *
 * The animation the class names must therefore always END. A `prefers-reduced-motion`
 * override that sets `animation: none` would never fire `animationend` and would
 * leave the highlight on screen for good: give it a non-animating keyframe
 * (`step-end`) instead.
 */
export interface FlashHighlightOptions {
  /** How to bring the element into view. `false` skips scrolling entirely. */
  scroll?: false | ScrollIntoViewOptions;
}

export function scrollToAndFlash(
  target: HTMLElement | null | undefined,
  className: string,
  { scroll = { block: 'nearest' } }: FlashHighlightOptions = {},
): void {
  if (!target) return;
  // Optional-called: jsdom does not implement scrollIntoView.
  if (scroll !== false) target.scrollIntoView?.(scroll);
  target.classList.remove(className);
  void target.offsetWidth; // force reflow so re-adding restarts the animation
  target.classList.add(className);
  target.addEventListener(
    'animationend',
    () => target.classList.remove(className),
    { once: true },
  );
}
