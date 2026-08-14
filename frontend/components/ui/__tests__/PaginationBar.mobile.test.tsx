// @vitest-environment jsdom
/**
 * The pagination strip is the widest fixed thing on every list page: a page-size
 * group and a Previous / "Page 1 of 2" / Next group, all `whitespace-nowrap`,
 * held in one row that could neither wrap nor shrink. On a 375px viewport that
 * row was 72px too wide, and since the routed content sits in an `overflow-y`
 * container - which promotes the horizontal axis to `auto` - the reader got a
 * sideways scrollbar with the Next button hanging off the edge. That is the
 * scroll reported on the Workflows list.
 *
 * <p>Two things fix it and both are pinned here: the two groups may now wrap
 * onto separate lines, and each pager button drops its WORD below `sm` while
 * keeping its arrow. The word survives as the accessible name.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join('/')}` : key,
}));

import { PaginationBar } from '../PaginationBar';

function renderBar() {
  return render(
    <PaginationBar
      page={0}
      pageSize={25}
      totalCount={60}
      visibleCount={25}
      onPageChange={vi.fn()}
      onPageSizeChange={vi.fn()}
    />,
  );
}

afterEach(() => cleanup());

describe('PaginationBar on a phone', () => {
  it.each(['prev', 'next'])('keeps "%s" reachable by name once its word is hidden', (label) => {
    renderBar();

    expect(screen.getByRole('button', { name: label })).toBeDefined();
  });

  it.each(['prev', 'next'])('hides the "%s" word below the sm breakpoint', (label) => {
    renderBar();

    const button = screen.getByRole('button', { name: label });
    const word = Array.from(button.querySelectorAll('span')).find((s) => s.textContent === label);

    expect(word, `the "${label}" word is not in its own element, so it cannot be dropped on a phone`)
      .toBeDefined();
    expect(word!.className).toContain('hidden');
    expect(word!.className).toContain('sm:inline');
  });

  it('lets the page-size group and the pager wrap onto separate lines', () => {
    renderBar();

    // The pager's grandparent is the row holding both groups: without wrapping
    // it is the two of them side by side that exceeds a phone, whatever each
    // one costs on its own.
    const row = screen.getByRole('button', { name: 'next' }).parentElement!.parentElement!;

    expect(row.className).toContain('flex-wrap');
  });

  it('keeps the page indicator on one line', () => {
    // Left to wrap, "Page 1 of 2" became three stacked lines in the space the
    // squeezed row left it, which is how the bar looked broken even where it
    // technically fitted.
    renderBar();

    const indicator = screen.getByText(/^pageInfo:/);

    expect(indicator.className).toContain('whitespace-nowrap');
  });
});
