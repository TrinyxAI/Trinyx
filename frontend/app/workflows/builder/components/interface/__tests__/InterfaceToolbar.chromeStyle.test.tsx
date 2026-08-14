// @vitest-environment jsdom
/**
 * The application's floating toolbar was the last pill-shaped island left once the
 * canvas chrome moved onto the square Button system: a `rounded-full` card with
 * `rounded-full` icon buttons and a hardcoded `bg-white / dark:bg-gray-800` that
 * ignored the palette entirely.
 *
 * What has to hold now:
 *  - the card IS the shared `canvasChromeSurfaceClass`, not a local copy of it, so it
 *    cannot drift from the other chrome surfaces;
 *  - its controls take the Button radius (`rounded-xl`), not the pill;
 *  - nothing round is left among the CONTROLS - while the things that are round for a
 *    reason (status dots, progress bars) are none of this component's business.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

import { canvasChromeSurfaceClass } from '@/components/ui/canvas-chrome';
import { InterfaceToolbar } from '../InterfaceToolbar';

function renderToolbar(props: Partial<React.ComponentProps<typeof InterfaceToolbar>> = {}) {
  return render(
    <InterfaceToolbar
      currentPage={0}
      totalPages={3}
      onPrevious={() => undefined}
      onNext={() => undefined}
      onFullscreen={() => undefined}
      onClose={() => undefined}
      pageBadge="Re-execution 2"
      {...props}
    />,
  );
}

describe('InterfaceToolbar - square chrome', () => {
  it('builds the card from the shared chrome surface rather than restating it', () => {
    renderToolbar();
    const card = screen.getByTestId('interface-toolbar');

    // Asserted against the exported class, so a change to the shared surface travels
    // here instead of leaving this toolbar behind on a stale copy.
    for (const cls of canvasChromeSurfaceClass.split(/\s+/)) {
      expect(card.className).toContain(cls);
    }
  });

  it('leaves no pill-shaped control behind', () => {
    renderToolbar();
    const card = screen.getByTestId('interface-toolbar');

    const round = Array.from(card.querySelectorAll('button')).filter((b) =>
      b.className.includes('rounded-full'));
    expect(round, 'every toolbar control is on the Button radius').toHaveLength(0);
  });

  it('puts the pagination controls on the Button radius', () => {
    renderToolbar();
    const prev = screen.getByLabelText('previous');

    expect(prev.className).toContain('rounded-xl');
  });

  it('drops the re-run badge one radius step below the controls, as a chip', () => {
    renderToolbar();
    const badge = screen.getByTestId('interface-pagination-rerun-badge');

    // A chip is not a control: too small for the Button radius, so it takes the step
    // below rather than staying circular.
    expect(badge.className).toContain('rounded-md');
    expect(badge.className).not.toContain('rounded-full');
  });

  it('keeps the dark fullscreen variant square too', () => {
    renderToolbar({ variant: 'dark' });
    const card = screen.getByTestId('interface-toolbar');

    expect(card.className).toContain('rounded-2xl');
    expect(card.className).not.toContain('rounded-full');
    const round = Array.from(card.querySelectorAll('button')).filter((b) =>
      b.className.includes('rounded-full'));
    expect(round).toHaveLength(0);
  });
});
