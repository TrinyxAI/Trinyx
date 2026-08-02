'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

export interface HorizontalScrollHint {
  /** Content is cut off on the left (the strip has been scrolled). */
  left: boolean;
  /** Content is cut off on the right. */
  right: boolean;
}

/**
 * Overflow affordance for a strip that scrolls sideways with its scrollbar
 * hidden.
 *
 * A hidden-scrollbar strip looks COMPLETE when it is actually truncated, and a
 * mouse-only user has no way to reach what is cut off. This reports which edge
 * still hides content (draw a fade + an arrow there) and scrolls the strip by
 * ~60% of its width on demand.
 *
 * Wire it as: `<div ref={scrollRef} onScroll={onScroll}>`.
 */
export function useHorizontalScrollHint<T extends HTMLElement = HTMLDivElement>() {
  const scrollRef = useRef<T>(null);
  const [hint, setHint] = useState<HorizontalScrollHint>({ left: false, right: false });

  const update = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    // 4px tolerance absorbs sub-pixel rounding on high-DPI screens.
    const left = el.scrollLeft > 4;
    const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 4;
    setHint(prev => (prev.left === left && prev.right === right) ? prev : { left, right });
  }, []);

  useEffect(() => {
    update();
    const el = scrollRef.current;
    if (!el) return;

    let resize: ResizeObserver | undefined;
    if (typeof ResizeObserver !== 'undefined') {
      resize = new ResizeObserver(update);
      resize.observe(el);
      // The strip itself keeps its width while its content grows, so watch the
      // content box too when it is a single wrapper.
      if (el.firstElementChild) resize.observe(el.firstElementChild);
    }

    // Items that appear, disappear or change text (a run status chip, a nav
    // entry unlocked by a role fetch) move scrollWidth without resizing
    // anything a ResizeObserver watches.
    let mutation: MutationObserver | undefined;
    if (typeof MutationObserver !== 'undefined') {
      mutation = new MutationObserver(update);
      mutation.observe(el, { childList: true, subtree: true, characterData: true });
    }

    return () => { resize?.disconnect(); mutation?.disconnect(); };
  }, [update]);

  const nudge = useCallback((direction: 1 | -1) => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollBy({ left: direction * Math.round(el.clientWidth * 0.6), behavior: 'smooth' });
  }, []);

  return { scrollRef, hint, onScroll: update, nudge };
}
