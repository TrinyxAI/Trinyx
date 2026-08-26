// @vitest-environment jsdom
/**
 * Sound on a marketplace card.
 *
 * A card's thumbnail is not a picture, it is the publication running - so one
 * carrying an <audio>/<video> starts making noise as soon as the grid mounts, and
 * a marketplace grid mounts a couple of dozen at once. The preview therefore
 * starts muted and the visitor turns it on, one card at a time.
 *
 * Two things have to hold, and both are about not lying to the visitor: a card
 * that cannot make a sound must not offer a sound control, and pressing the
 * control must not navigate - the whole card is a Link, so an unguarded click
 * would leave the marketplace instead of unmuting.
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (k: string) => `${ns}.${k}`,
}));
const navigate = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: navigate, replace: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: ({ href, children, ...rest }: any) => (
    // Records a navigation the way the real Link would, so a control that fails
    // to stop the click is caught rather than silently passing.
    <a href={href} onClick={() => navigate(href)} {...rest}>{children}</a>
  ),
}));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

/** Stand-in for the preview: records the mute state, exposes the presence callback. */
const previewState = vi.hoisted(() => ({
  mediaMuted: undefined as boolean | undefined,
  announcePresence: null as null | ((hasAudio: boolean) => void),
}));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: {
    mediaMuted?: boolean;
    onMediaAudioPresence?: (hasAudio: boolean) => void;
  }) => {
    previewState.mediaMuted = props.mediaMuted;
    previewState.announcePresence = props.onMediaAudioPresence ?? null;
    return <div data-testid="showcase-preview" />;
  },
}));
vi.mock('@/components/marketplace/InterfacePreview', () => ({
  InterfacePreview: () => <div data-testid="interface-preview" />,
}));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/marketplace/CeExclusiveBadge', () => ({
  CeExclusiveBadge: () => null,
  isCeExclusiveBlocked: () => false,
}));
vi.mock('@/components/ui/VisibilityBadge', () => ({ VisibilityBadge: () => null }));
vi.mock('@/components/profile/UserActionMenu', () => ({
  UserActionMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getLandingSnapshot: vi.fn().mockResolvedValue({ landing: null }) },
}));

import { PublicationCard } from '../PublicationCard';

const PUBLICATION = {
  id: 'pub-1',
  title: 'Noisy App',
  // APPLICATION, not WORKFLOW: only a non-WORKFLOW display mode renders the live
  // preview at all (a WORKFLOW card shows the static node-icon tile), so a
  // WORKFLOW fixture would test the sound control against a card that has none.
  displayMode: 'APPLICATION',
  publicationType: 'WORKFLOW',
  status: 'ACTIVE',
  visibility: 'PUBLIC',
  showcaseRunId: 'run-1',
  showcaseInterfaceId: 'iface-1',
  creditsPerUse: 0,
  publisherId: '9',
} as never;

const soundToggle = () => screen.queryByTestId('publication-sound-toggle');

function renderCard(extra: Record<string, unknown> = {}) {
  return render(<PublicationCard publication={PUBLICATION} {...extra} />);
}

/** Play the part of the frame reporting that it does contain media. */
function reportAudioPresent() {
  act(() => previewState.announcePresence?.(true));
}

beforeEach(() => {
  vi.clearAllMocks();
  previewState.mediaMuted = undefined;
  previewState.announcePresence = null;
});
afterEach(cleanup);

describe('PublicationCard - sound control', () => {
  it('starts the preview muted, before anything is known about its content', () => {
    // Muting on FIRST render is the whole protection: waiting for the frame to
    // report back would mean the sound had already been playing while it did.
    renderCard();

    expect(previewState.mediaMuted).toBe(true);
  });

  it('shows no control on a publication with no media', () => {
    renderCard();

    expect(soundToggle()).not.toBeInTheDocument();
  });

  it('reveals the control once the frame reports it has media', () => {
    renderCard();

    reportAudioPresent();

    expect(soundToggle()).toBeInTheDocument();
  });

  it('unmutes on press and mutes again on a second press', () => {
    renderCard();
    reportAudioPresent();

    fireEvent.click(soundToggle()!);
    expect(previewState.mediaMuted).toBe(false);

    fireEvent.click(soundToggle()!);
    expect(previewState.mediaMuted).toBe(true);
  });

  it('does NOT open the publication when pressed - the whole card is a Link', () => {
    renderCard();
    reportAudioPresent();

    fireEvent.click(soundToggle()!);

    expect(navigate).not.toHaveBeenCalled();
  });

  it('reports its state to assistive tech, since the icon is the only other cue', () => {
    renderCard();
    reportAudioPresent();

    expect(soundToggle()).toHaveAttribute('aria-pressed', 'false');
    expect(soundToggle()).toHaveAttribute('aria-label', 'applications.unmuteSound');

    fireEvent.click(soundToggle()!);

    expect(soundToggle()).toHaveAttribute('aria-pressed', 'true');
    expect(soundToggle()).toHaveAttribute('aria-label', 'applications.muteSound');
  });

  it('is withheld while an install runs, where the progress bar owns the bottom strip', () => {
    renderCard({ installProgress: 42 });
    reportAudioPresent();

    expect(soundToggle()).not.toBeInTheDocument();
    // The greyed-out preview is still muted - the install does not un-manage it.
    expect(previewState.mediaMuted).toBe(true);
  });
});
