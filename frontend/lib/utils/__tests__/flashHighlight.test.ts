/**
 * @vitest-environment jsdom
 *
 * Two properties make this helper worth sharing rather than inlining, and both
 * are silent when broken: a browser does not restart an animation already on an
 * element (so a second highlight plays NOTHING without the remove/reflow/add
 * dance), and the class has to come off on `animationend` or the cue never ends.
 *
 * jsdom has no layout and fires no animation events, so what is verifiable here
 * is the DOM bookkeeping: the class is removed before being re-added, and the
 * `animationend` listener that removes it is registered exactly once.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { scrollToAndFlash } from '@/lib/utils/flashHighlight';

const CLASS = 'x-flash';

function makeTarget(): HTMLElement {
  const el = document.createElement('div');
  document.body.appendChild(el);
  return el;
}

afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); });

describe('scrollToAndFlash', () => {
  it('scrolls the element into view and puts the class on it', () => {
    const el = makeTarget();
    const scroll = vi.fn();
    (el as unknown as { scrollIntoView: unknown }).scrollIntoView = scroll;

    scrollToAndFlash(el, CLASS);

    expect(scroll).toHaveBeenCalledWith({ block: 'nearest' });
    expect(el.classList.contains(CLASS)).toBe(true);
  });

  // The reflow itself (`void target.offsetWidth`) is what makes the browser
  // commit the removal before the re-add; jsdom has no layout, so only the
  // observable half - that the class is genuinely removed first - is pinned here.
  it('takes the class OFF before re-adding it, so a repeat can replay', () => {
    const el = makeTarget();
    const removed: string[] = [];
    const realRemove = el.classList.remove.bind(el.classList);
    el.classList.remove = ((...names: string[]) => { removed.push(...names); return realRemove(...names); }) as never;

    scrollToAndFlash(el, CLASS);
    scrollToAndFlash(el, CLASS);

    // Twice: once per call. Re-adding a class that is already there is a no-op
    // for the animation, which is the bug this guards.
    expect(removed.filter(n => n === CLASS).length).toBe(2);
    expect(el.classList.contains(CLASS)).toBe(true);
  });

  it('removes the class when the animation ends, and only once', () => {
    const el = makeTarget();
    scrollToAndFlash(el, CLASS);

    el.dispatchEvent(new Event('animationend'));
    expect(el.classList.contains(CLASS)).toBe(false);

    // The listener was registered `once`, so a stray later event is harmless.
    el.classList.add(CLASS);
    el.dispatchEvent(new Event('animationend'));
    expect(el.classList.contains(CLASS)).toBe(true);
  });

  it('can skip scrolling, for a caller that scrolls a different element', () => {
    const el = makeTarget();
    const scroll = vi.fn();
    (el as unknown as { scrollIntoView: unknown }).scrollIntoView = scroll;

    scrollToAndFlash(el, CLASS, { scroll: false });

    expect(scroll).not.toHaveBeenCalled();
    expect(el.classList.contains(CLASS)).toBe(true);
  });

  it('does nothing at all without a target', () => {
    expect(() => scrollToAndFlash(null, CLASS)).not.toThrow();
    expect(() => scrollToAndFlash(undefined, CLASS)).not.toThrow();
  });

  it('survives an environment with no scrollIntoView', () => {
    const el = makeTarget();
    (el as unknown as { scrollIntoView?: unknown }).scrollIntoView = undefined;

    expect(() => scrollToAndFlash(el, CLASS)).not.toThrow();
    expect(el.classList.contains(CLASS)).toBe(true);
  });
});
