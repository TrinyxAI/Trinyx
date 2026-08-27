// @vitest-environment jsdom
/**
 * The two halves of "your credits cannot pay for this model".
 *
 * <p>The badge is a LABEL that marks the rows; the notice is the control that
 * does something about it. They are split because the badge lives inside
 * `Select` options, where ARIA strips a nested control, a trapped listbox never
 * reaches its tab stop, Radix re-renders the selected option inside the trigger
 * `<button>`, and a dialog opened from within paints under the still-open menu.
 * Every one of those is a way the earlier interactive-badge version failed, so
 * the tests below pin the split rather than just the wording.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k, useLocale: () => 'en' }));

vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string, children: React.ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

import { UpgradeRequiredBadge, UpgradeRequiredNotice } from '../UpgradeRequiredBadge';

afterEach(cleanup);

describe('UpgradeRequiredBadge - the label on a row', () => {
  it('marks a row the account cannot pay for', () => {
    render(<UpgradeRequiredBadge blocked />);

    expect(screen.getByText('label')).toBeInTheDocument();
  });

  it('renders nothing at all when the account can pay', () => {
    const { container } = render(<UpgradeRequiredBadge blocked={false} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('regression: is not a control, because an option cannot contain one', () => {
    // ARIA gives role="option" presentational children, so a nested button is
    // announced to nobody; a Radix listbox never reaches its tab stop either,
    // and Enter there selects the option instead. A control here is a promise
    // the host cannot keep.
    const hostClicked = vi.fn();
    render(
      <div onClick={hostClicked}>
        <UpgradeRequiredBadge blocked />
      </div>,
    );

    const label = screen.getByText('label');
    expect(label.closest('[role="button"]')).toBeNull();
    expect(label.closest('[tabindex]')).toBeNull();

    fireEvent.click(label);

    // It does not swallow the host's own click either: choosing the model it
    // sits on must keep working.
    expect(hostClicked).toHaveBeenCalledTimes(1);
  });

  it('keeps the word out of the row, but not out of the reading of it', () => {
    // The row already carries tier, capabilities, context and price. The word
    // stays for a screen reader, which reads the option's text, not its width.
    render(<UpgradeRequiredBadge blocked />);

    expect(screen.getByText('label')).toHaveClass('sr-only');
  });

  it('is the lock alone, with no pill behind it', () => {
    // The row already carries a tier badge and a provider badge, both filled
    // shapes. A third one read as another category rather than as a warning
    // about this one, so the colour lives on the icon and nothing else.
    const { container } = render(<UpgradeRequiredBadge blocked />);

    const wrapper = container.firstElementChild!;
    expect(wrapper.className).not.toMatch(/bg-/);
    expect(wrapper.className).not.toMatch(/border/);
    expect(wrapper.querySelector('svg')?.getAttribute('class')).toMatch(/text-amber-600/);
  });

  it('regression: is a span, because Radix re-renders it inside the trigger button', () => {
    // The selected option's content is portalled into the trigger `<button>`.
    // A `<div>` there (which the shared Badge renders) is invalid markup.
    render(<UpgradeRequiredBadge blocked />);

    expect(screen.getByText('label').closest('span')).not.toBeNull();
    expect(document.querySelector('div[title="title"]')).toBeNull();
  });
});

describe('UpgradeRequiredNotice - the way out, under the control', () => {
  it('states the reason and offers the plans', () => {
    render(<UpgradeRequiredNotice blocked />);

    // The explanation is the point: "upgrade" alone is a nag, the reason is a
    // fact the reader can act on.
    expect(screen.getByText(/explanation/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'cta' })).toBeInTheDocument();
  });

  it('regression: NAVIGATES to the plans instead of opening a dialog over itself', () => {
    // It lives inside floating hosts that paint above a centred dialog: the
    // composer menu at z-[10000] and the Options panel at z-[99999]. A dialog
    // opened from in here appeared underneath the very menu it came from. The
    // plans page carries both answers anyway, subscribe or top up.
    render(<UpgradeRequiredNotice blocked />);

    const cta = screen.getByRole('link', { name: 'cta' });
    expect(cta).toHaveAttribute('href', '/en/app/settings/pricing');
  });

  it('announces itself when it appears, since the balance lands after the paint', () => {
    render(<UpgradeRequiredNotice blocked />);

    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('renders nothing at all when the account can pay', () => {
    const { container } = render(<UpgradeRequiredNotice blocked={false} />);

    expect(container).toBeEmptyDOMElement();
  });
});
