// @vitest-environment jsdom
/**
 * CanvasFileStripToggleButton - the toolbar control that folds/unfolds every
 * file preview of a run at once.
 *
 * Pinned here: it only exists while the canvas actually carries file strips (the
 * user's requirement: "when there is at least 1 fileref"), and its intent is
 * derived from how many are open, so the first click on a partially-expanded
 * canvas always OPENS rather than closing the one preview just opened.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { CanvasFileStripToggleButton } from '../CanvasFileStripToggleButton';
import {
  useFileStripExpansionSafe,
  type FileStripExpansionContextValue,
} from '@/contexts/FileStripExpansionContext';

vi.mock('@/contexts/FileStripExpansionContext', () => ({
  useFileStripExpansionSafe: vi.fn(),
}));

const expandAll = vi.fn();
const collapseAll = vi.fn();

const ctx = (stripCount: number, expandedCount: number): FileStripExpansionContextValue => ({
  stripCount,
  expandedCount,
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
});

describe('CanvasFileStripToggleButton - visibility', () => {
  it('renders nothing when the canvas carries no file strip (edit mode, or a run with no file)', () => {
    useCtx.mockReturnValue(ctx(0, 0));
    const c = render(<CanvasFileStripToggleButton />);
    expect(c.queryByTestId('canvas-toggle-all-files')).toBeNull();
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
});
