// @vitest-environment jsdom
/**
 * CanvasFileStripToggleButton - the toolbar control for whether file previews hang
 * open under the nodes.
 *
 * Pinned here: it stays on the toolbar of a RUN that produced no file (it used to
 * hide itself with no strip on screen, which made the preference unsettable exactly
 * when you wanted to set it ahead of time) and it disappears in EDIT mode, where
 * nothing it acts on can exist; it falls back to the STORED preference when there is
 * nothing to count; and its intent is derived from how many are open, so the first
 * click on a partially-expanded canvas always OPENS rather than closing the one
 * preview just opened. Its look is pinned too - it is the only chrome control
 * carrying two glyphs, and that is where a hand-rolled className would drift from
 * its siblings.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { CanvasFileStripToggleButton } from '../CanvasFileStripToggleButton';
import { canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
import {
  useFileStripExpansionSafe,
  type FileStripExpansionContextValue,
} from '@/contexts/FileStripExpansionContext';

vi.mock('@/contexts/FileStripExpansionContext', () => ({
  useFileStripExpansionSafe: vi.fn(),
}));

/**
 * The canvas mode. Everything below is about a RUN unless a test says otherwise:
 * that is the only mode where a file strip can exist, so it is the only mode where
 * the assertions about counts and intent mean anything.
 */
const mode = vi.hoisted(() => ({ isEditMode: false }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mode,
}));

const expandAll = vi.fn();
const collapseAll = vi.fn();

const ctx = (
  stripCount: number,
  expandedCount: number,
  defaultExpanded = false,
): FileStripExpansionContextValue => ({
  stripCount,
  expandedCount,
  defaultExpanded,
  registerStrip: vi.fn(),
  unregisterStrip: vi.fn(),
  isExpanded: () => false,
  setExpanded: vi.fn(),
  expandAll,
  collapseAll,
});

const useCtx = vi.mocked(useFileStripExpansionSafe);

beforeEach(() => {
  expandAll.mockClear();
  collapseAll.mockClear();
  useCtx.mockReset();
  mode.isEditMode = false;
});

describe('CanvasFileStripToggleButton - visibility', () => {
  it('STAYS on the toolbar of a run with no file strip on screen - that is when the preference is worth setting', () => {
    // It used to hide itself here, which meant the only moment you could state "I
    // want previews open" was a moment when they were already in front of you.
    useCtx.mockReturnValue(ctx(0, 0));
    const c = render(<CanvasFileStripToggleButton />);
    expect(c.queryByTestId('canvas-toggle-all-files')).not.toBeNull();
  });

  it('is NOT on the toolbar in edit mode, where nothing it acts on can exist', () => {
    // File strips hang under nodes in run mode only, so in edit mode this was a
    // permanently inert control describing a state the reader cannot see.
    mode.isEditMode = true;
    useCtx.mockReturnValue(ctx(0, 0));
    const c = render(<CanvasFileStripToggleButton />);
    expect(c.queryByTestId('canvas-toggle-all-files')).toBeNull();
  });

  it('stays away in edit mode even if strips somehow registered themselves', () => {
    // The mode decides, not the registry: a stale registration from the run you
    // just left must not put the control back on an editing toolbar.
    mode.isEditMode = true;
    useCtx.mockReturnValue(ctx(3, 2));
    const c = render(<CanvasFileStripToggleButton />);
    expect(c.queryByTestId('canvas-toggle-all-files')).toBeNull();
  });

  it('with nothing on screen, shows the STORED preference rather than a vacuous "all expanded"', () => {
    // 0 >= 0 is true, so deriving the state from the counts would render the toggle
    // pressed while the preference says folded.
    useCtx.mockReturnValue(ctx(0, 0, false));
    const folded = render(<CanvasFileStripToggleButton />);
    expect(folded.getByTestId('canvas-toggle-all-files').getAttribute('aria-pressed')).toBe('false');
    folded.unmount();

    useCtx.mockReturnValue(ctx(0, 0, true));
    const expanded = render(<CanvasFileStripToggleButton />);
    expect(expanded.getByTestId('canvas-toggle-all-files').getAttribute('aria-pressed')).toBe('true');
  });

  it('with nothing on screen, the click still reaches the bulk action that records the choice', () => {
    // What the provider then DOES with it (write it to storage, keep it across the
    // next epoch) is pinned in fileStripExpansionContext.test.tsx: the context is
    // mocked here, so this can only assert the delegation.
    useCtx.mockReturnValue(ctx(0, 0, false));
    const c = render(<CanvasFileStripToggleButton />);

    fireEvent.click(c.getByTestId('canvas-toggle-all-files'));

    expect(expandAll).toHaveBeenCalledTimes(1);
    expect(collapseAll).not.toHaveBeenCalled();
  });

  it('renders nothing outside the provider - surfaces that reuse the toolbar without a canvas registry', () => {
    useCtx.mockReturnValue(null);
    const c = render(<CanvasFileStripToggleButton />);
    expect(c.queryByTestId('canvas-toggle-all-files')).toBeNull();
  });

  it('appears as soon as ONE strip is on the canvas', () => {
    useCtx.mockReturnValue(ctx(1, 0));
    const c = render(<CanvasFileStripToggleButton />);
    expect(c.getByTestId('canvas-toggle-all-files')).not.toBeNull();
  });
});

describe('CanvasFileStripToggleButton - intent', () => {
  it('with everything collapsed it offers EXPAND and expands all', () => {
    useCtx.mockReturnValue(ctx(3, 0));
    const c = render(<CanvasFileStripToggleButton />);
    const btn = c.getByTestId('canvas-toggle-all-files');
    expect(btn.getAttribute('aria-label')).toBe('expandAllFiles');
    expect(btn.getAttribute('aria-pressed')).toBe('false');

    fireEvent.click(btn);

    expect(expandAll).toHaveBeenCalledTimes(1);
    expect(collapseAll).not.toHaveBeenCalled();
  });

  it('with everything expanded it offers COLLAPSE and collapses all', () => {
    useCtx.mockReturnValue(ctx(3, 3));
    const c = render(<CanvasFileStripToggleButton />);
    const btn = c.getByTestId('canvas-toggle-all-files');
    expect(btn.getAttribute('aria-label')).toBe('collapseAllFiles');
    expect(btn.getAttribute('aria-pressed')).toBe('true');

    fireEvent.click(btn);

    expect(collapseAll).toHaveBeenCalledTimes(1);
    expect(expandAll).not.toHaveBeenCalled();
  });

  it('a PARTIALLY expanded canvas still offers expand, so one click shows every preview', () => {
    useCtx.mockReturnValue(ctx(3, 1));
    const c = render(<CanvasFileStripToggleButton />);
    const btn = c.getByTestId('canvas-toggle-all-files');
    expect(btn.getAttribute('aria-label')).toBe('expandAllFiles');

    fireEvent.click(btn);

    // The opposite rule ("any expanded => collapse") would fold the preview the
    // user had just opened by hand.
    expect(expandAll).toHaveBeenCalledTimes(1);
  });

  it('what is ON SCREEN wins over the stored preference whenever there IS something to count', () => {
    // The case this feature is actually for: preference says "open", and the user
    // has folded strips by hand. The counts must decide, so the click re-opens them.
    // Without this case the whole state expression collapses to
    // `defaultExpanded || (stripCount > 0 && expandedCount >= stripCount)`, which
    // passes every other test here and yet offers COLLAPSE to this user.
    useCtx.mockReturnValue(ctx(3, 1, true));
    const c = render(<CanvasFileStripToggleButton />);
    const btn = c.getByTestId('canvas-toggle-all-files');

    expect(btn.getAttribute('aria-label')).toBe('expandAllFiles');
    expect(btn.getAttribute('aria-pressed')).toBe('false');

    fireEvent.click(btn);
    expect(expandAll).toHaveBeenCalledTimes(1);
    expect(collapseAll).not.toHaveBeenCalled();
  });

  it('and the mirror: everything EXPANDED on screen while the preference says folded', () => {
    useCtx.mockReturnValue(ctx(2, 2, false));
    const c = render(<CanvasFileStripToggleButton />);
    const btn = c.getByTestId('canvas-toggle-all-files');

    expect(btn.getAttribute('aria-label')).toBe('collapseAllFiles');
    expect(btn.getAttribute('aria-pressed')).toBe('true');
  });
});

describe('CanvasFileStripToggleButton - what the click is CALLED', () => {
  it('names the bulk action while previews are on screen', () => {
    useCtx.mockReturnValue(ctx(3, 0));
    const c = render(<CanvasFileStripToggleButton />);
    expect(c.getByTestId('canvas-toggle-all-files').getAttribute('aria-label')).toBe('expandAllFiles');
  });

  it('names the standing PREFERENCE when there is nothing to act on', () => {
    // "Expand all file previews" with zero previews is a promise the click cannot
    // keep, and a screen-reader user has no empty canvas to see for themselves.
    useCtx.mockReturnValue(ctx(0, 0, false));
    const folded = render(<CanvasFileStripToggleButton />);
    const foldedBtn = folded.getByTestId('canvas-toggle-all-files');
    expect(foldedBtn.getAttribute('aria-label')).toBe('expandFilesByDefault');
    // title and accessible name must not disagree.
    expect(foldedBtn.getAttribute('title')).toBe('expandFilesByDefault');
    folded.unmount();

    useCtx.mockReturnValue(ctx(0, 0, true));
    const expanded = render(<CanvasFileStripToggleButton />);
    expect(expanded.getByTestId('canvas-toggle-all-files').getAttribute('aria-label')).toBe('collapseFilesByDefault');
  });
});

describe('CanvasFileStripToggleButton - appearance', () => {
  /** The tokens that decide what a keyboard user sees when they tab onto a control. */
  const focusTokens = (className: string) =>
    className.split(/\s+/).filter((c) => c.startsWith('focus-visible:') || c.startsWith('focus:'));

  it('takes its focus ring from the shared chrome class, like every sibling control', () => {
    // The requirement is "same style as the other buttons". Zoom, fit-view, lock and
    // settings all render `canvasChromeCompactButtonClass(...)` verbatim, so the test
    // is that this one still resolves to the SAME focus treatment - restating the
    // tokens by hand here would only pin the copy, not the parity.
    useCtx.mockReturnValue(ctx(0, 0));
    const c = render(<CanvasFileStripToggleButton />);
    const btn = c.getByTestId('canvas-toggle-all-files');

    const sibling = focusTokens(canvasChromeCompactButtonClass());
    expect(sibling.length).toBeGreaterThan(0);
    expect(focusTokens(btn.className)).toEqual(sibling);
  });

  it('keeps the shared 28px height while widening for the second glyph', () => {
    useCtx.mockReturnValue(ctx(0, 0));
    const c = render(<CanvasFileStripToggleButton />);
    const classes = c.getByTestId('canvas-toggle-all-files').className.split(/\s+/);

    // Height is what keeps the toolbar card on its 36px line; the square width is
    // the only part this control is allowed to give up.
    expect(classes).toContain('h-7');
    expect(classes).toContain('w-auto');
    expect(classes).not.toContain('w-7');
  });

  it('carries a MEDIA glyph AND a direction glyph, and only the direction one flips', () => {
    // One glyph could not say both things: a lone chevron pair on a canvas toolbar
    // reads as "collapse the graph", not "fold the file previews". lucide stamps
    // each icon with its own class, so the identity can be asserted rather than
    // just the count (which any two icons would satisfy).
    const glyphs = (c: ReturnType<typeof render>) =>
      Array.from(c.getByTestId('canvas-toggle-all-files').querySelectorAll('svg'))
        .map((svg) => Array.from(svg.classList).find((k) => k.startsWith('lucide-')));

    useCtx.mockReturnValue(ctx(3, 0));
    const collapsed = render(<CanvasFileStripToggleButton />);
    expect(glyphs(collapsed)).toEqual(['lucide-images', 'lucide-chevrons-up-down']);
    collapsed.unmount();

    useCtx.mockReturnValue(ctx(3, 3));
    const expanded = render(<CanvasFileStripToggleButton />);
    // The media glyph is the constant ("what this acts on"); only the second one
    // carries the state ("which way the click goes").
    expect(glyphs(expanded)).toEqual(['lucide-images', 'lucide-chevrons-down-up']);
  });

  it('keeps the media glyph at the sibling 16px, so the control does not read lighter than its row', () => {
    useCtx.mockReturnValue(ctx(3, 0));
    const c = render(<CanvasFileStripToggleButton />);
    const media = c.getByTestId('canvas-toggle-all-files').querySelector('svg.lucide-images');

    // Every other toolbar glyph is h-4 w-4.
    expect(media?.classList.contains('h-4')).toBe(true);
    expect(media?.classList.contains('w-4')).toBe(true);
  });

  it('hides both glyphs from the accessible name, which the aria-label already carries', () => {
    useCtx.mockReturnValue(ctx(3, 0));
    const c = render(<CanvasFileStripToggleButton />);
    const icons = c.getByTestId('canvas-toggle-all-files').querySelectorAll('svg');

    for (const icon of icons) {
      expect(icon.getAttribute('aria-hidden')).toBe('true');
    }
  });
});
