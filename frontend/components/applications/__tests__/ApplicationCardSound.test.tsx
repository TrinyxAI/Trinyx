// @vitest-environment jsdom
/**
 * Sound on an application card.
 *
 * The card's thumbnail is not a picture, it is the application running. So an app
 * with an `<audio>`/`<video>` starts making noise as soon as the page mounts, and
 * this page mounts a whole GRID of them. The card therefore starts its preview
 * muted and puts a speaker next to the favorite star for the visitor to turn it
 * on, one card at a time.
 *
 * Two things have to hold, and both are about not lying to the visitor: a card
 * that cannot make a sound must not offer a sound control, and the control must
 * actually reach the frame (only the frame can mute a sandboxed document).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

/**
 * Stand-in for the preview. Records the mute state it was handed and exposes the
 * presence callback, so a test can play the part of an interface that turns out
 * to contain media.
 */
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
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/ui/VisibilityBadge', () => ({ VisibilityBadge: () => null }));
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  DialogContent: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  DialogHeader: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  DialogTitle: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));

import { ApplicationCard } from '../ApplicationCard';

const PUBLICATION = {
  id: 'pub-1',
  title: 'Noisy App',
  displayMode: 'APPLICATION',
  publicationType: 'WORKFLOW',
  status: 'ACTIVE',
  visibility: 'PUBLIC',
  showcaseRunId: 'run-1',
  showcaseInterfaceId: 'iface-1',
  creditsPerUse: 0,
} as never;

function renderCard(onToggleFavorite?: (id: string) => void) {
  return render(
    <ApplicationCard
      publication={PUBLICATION}
      source="published"
      isSelected={false}
      onToggleSelect={() => {}}
      isFavorite={false}
      onToggleFavorite={onToggleFavorite}
    />,
  );
}

/** Play the part of the frame reporting that it does contain media. */
function reportAudioPresent() {
  act(() => previewState.announcePresence?.(true));
}

beforeEach(() => {
  cleanup();
  vi.clearAllMocks();
  previewState.mediaMuted = undefined;
  previewState.announcePresence = null;
});

describe('ApplicationCard - sound control', () => {
  it('starts the preview muted, before anything is known about its content', () => {
    // Muting on FIRST render is the whole protection: waiting for the frame to
    // report back would mean the sound had already been playing while it did.
    renderCard();

    expect(previewState.mediaMuted).toBe(true);
  });

  it('shows no sound control on an app that has no media', () => {
    renderCard();

    // A speaker on a silent card promises a sound that does not exist.
    expect(screen.queryByTestId('application-sound-toggle')).not.toBeInTheDocument();
  });

  it('reveals the control once the frame reports it has media', () => {
    renderCard();

    reportAudioPresent();

    expect(screen.getByTestId('application-sound-toggle')).toBeInTheDocument();
  });

  it('unmutes the preview when the control is pressed, and mutes it again on a second press', () => {
    renderCard();
    reportAudioPresent();

    fireEvent.click(screen.getByTestId('application-sound-toggle'));
    expect(previewState.mediaMuted).toBe(false);

    fireEvent.click(screen.getByTestId('application-sound-toggle'));
    expect(previewState.mediaMuted).toBe(true);
  });

  it('reports its state to assistive tech, since the icon is the only other cue', () => {
    renderCard();
    reportAudioPresent();

    const button = screen.getByTestId('application-sound-toggle');
    expect(button).toHaveAttribute('aria-pressed', 'false');
    expect(button).toHaveAttribute('aria-label', 'unmuteSound');

    fireEvent.click(button);

    expect(button).toHaveAttribute('aria-pressed', 'true');
    expect(button).toHaveAttribute('aria-label', 'muteSound');
  });

  it('sits beside the favorite star rather than somewhere else on the card', () => {
    const onToggleFavorite = vi.fn();
    renderCard(onToggleFavorite);
    reportAudioPresent();

    const sound = screen.getByTestId('application-sound-toggle');
    const star = screen.getByRole('button', { name: 'favorite' });
    expect(sound.parentElement).toBe(star.parentElement);
  });

  it('does not open the application when the control is pressed', () => {
    // The whole card navigates on click; a control painted on top of it has to
    // stop that, or turning the sound on would leave the page instead.
    const onCardClick = vi.fn();
    render(
      <ApplicationCard
        publication={PUBLICATION}
        source="published"
        isSelected={false}
        onToggleSelect={() => {}}
        onCardClick={onCardClick}
        isFavorite={false}
        onToggleFavorite={() => {}}
      />,
    );
    reportAudioPresent();

    fireEvent.click(screen.getByTestId('application-sound-toggle'));

    expect(onCardClick).not.toHaveBeenCalled();
  });

  it('still shows the sound control on a card with no favorite store', () => {
    // The two are independent: an anonymous / favorites-less context renders no
    // star, and the sound control must not disappear with it.
    renderCard(undefined);
    reportAudioPresent();

    expect(screen.queryByRole('button', { name: 'favorite' })).not.toBeInTheDocument();
    expect(screen.getByTestId('application-sound-toggle')).toBeInTheDocument();
  });
});
