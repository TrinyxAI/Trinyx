// @vitest-environment jsdom
/**
 * The sound entry in the application settings cog.
 *
 * An application is live HTML, so one carrying an <audio>/<video> would start
 * making noise the moment its page opens - on a shared link, before the visitor
 * has even decided to be there. The page therefore starts it muted, and the cog
 * is where it gets turned on.
 *
 * That makes the cog's own existence conditional on a DISJUNCTION: it already
 * appeared for "create an editable copy", and it must now also appear for an app
 * that has sound and nothing else to offer, because otherwise the sound could
 * never be turned on at all. An app with neither still gets no cog: an empty
 * popover is a dead affordance.
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: (ns?: string) => (k: string) => `${ns}.${k}` }));
// Radix Popover needs pointer APIs jsdom lacks - render trigger + content inline.
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: any) => <div>{children}</div>,
  PopoverTrigger: ({ children }: any) => <>{children}</>,
  PopoverContent: ({ children }: any) => <div>{children}</div>,
}));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ href, children, ...rest }: any) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { createEditableWorkflowCopy: vi.fn() },
}));

import { ApplicationSettingsMenu } from '../ApplicationSettingsMenu';

const soundEntry = () => screen.queryByTestId('application-settings-sound');
const cog = () => screen.queryByTestId('application-settings-trigger');

function renderMenu(props: Partial<React.ComponentProps<typeof ApplicationSettingsMenu>> = {}) {
  return render(<ApplicationSettingsMenu publicationId="pub-1" {...props} />);
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('ApplicationSettingsMenu - the cog exists for EITHER reason', () => {
  it('renders for an app that only has sound, which is the only way to turn it on', () => {
    renderMenu({ canCreateEditableCopy: false, soundMuted: true, onToggleSound: vi.fn() });

    expect(cog()).toBeInTheDocument();
    expect(soundEntry()).toBeInTheDocument();
    expect(screen.queryByTestId('application-settings-editable-copy')).not.toBeInTheDocument();
  });

  it('renders for an app that only offers the editable copy, unchanged', () => {
    renderMenu({ canCreateEditableCopy: true });

    expect(cog()).toBeInTheDocument();
    expect(screen.getByTestId('application-settings-editable-copy')).toBeInTheDocument();
    expect(soundEntry()).not.toBeInTheDocument();
  });

  it('renders both entries when the app offers both', () => {
    renderMenu({ canCreateEditableCopy: true, soundMuted: true, onToggleSound: vi.fn() });

    expect(soundEntry()).toBeInTheDocument();
    expect(screen.getByTestId('application-settings-editable-copy')).toBeInTheDocument();
  });

  it('renders NO cog when the app has neither - an empty popover is a dead affordance', () => {
    renderMenu({ canCreateEditableCopy: false });

    expect(cog()).not.toBeInTheDocument();
  });
});

describe('ApplicationSettingsMenu - the sound entry', () => {
  it('is absent when the app has no media at all', () => {
    // `soundMuted` undefined is the signal for "nothing to hear" - distinct from
    // `false`, which means "there is sound and it is currently on".
    renderMenu({ canCreateEditableCopy: true, soundMuted: undefined, onToggleSound: vi.fn() });

    expect(soundEntry()).not.toBeInTheDocument();
  });

  it('is absent without a handler, rather than offering a control that does nothing', () => {
    renderMenu({ canCreateEditableCopy: true, soundMuted: true });

    expect(soundEntry()).not.toBeInTheDocument();
  });

  it('offers to turn the sound ON while the app is muted', () => {
    renderMenu({ soundMuted: true, onToggleSound: vi.fn() });

    expect(soundEntry()).toHaveTextContent('applications.unmuteSound');
    expect(soundEntry()).toHaveAttribute('aria-pressed', 'false');
  });

  it('offers to turn it OFF once it is on', () => {
    renderMenu({ soundMuted: false, onToggleSound: vi.fn() });

    expect(soundEntry()).toHaveTextContent('applications.muteSound');
    expect(soundEntry()).toHaveAttribute('aria-pressed', 'true');
  });

  it('hands the toggle to the page, which owns the state the frame is told about', () => {
    const onToggleSound = vi.fn();
    renderMenu({ soundMuted: true, onToggleSound });

    fireEvent.click(soundEntry()!);

    expect(onToggleSound).toHaveBeenCalledTimes(1);
  });

  it('stays reachable after the editable copy has been created', async () => {
    // The "copy created" state replaces the copy entry entirely; the sound entry
    // sits above it and must survive that swap, or turning the sound on would
    // become impossible for the rest of the visit.
    const { publicationService } = await import('@/lib/api/orchestrator/publication.service');
    (publicationService.createEditableWorkflowCopy as any).mockResolvedValue({
      workflowId: 'wf-1', created: true,
    });
    renderMenu({ canCreateEditableCopy: true, soundMuted: true, onToggleSound: vi.fn() });

    fireEvent.click(screen.getByTestId('application-settings-editable-copy'));

    expect(await screen.findByText('marketplace.editableCopy.created')).toBeInTheDocument();
    expect(soundEntry()).toBeInTheDocument();
  });
});
