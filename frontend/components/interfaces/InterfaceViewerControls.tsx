'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Volume2, VolumeX } from 'lucide-react';
import { InterfaceToolbar } from '@/app/workflows/builder/components/interface/InterfaceToolbar';

interface InterfaceViewerControlsProps {
  /** Whether the page's audio is currently playing. */
  soundOn: boolean;
  onToggleSound: () => void;
  /**
   * Whether the page has any audio to control. The speaker is absent otherwise: a control over
   * silence is a promise the page cannot keep.
   */
  hasAudio: boolean;
  /** Dismiss the controls. */
  onClose: () => void;
}

/**
 * What a reader can change about the page they are looking at, on the same floating toolbar an
 * application's controls use, so one surface is learned once.
 *
 * <p>Behind a button rather than always on screen, which is the opposite of the choice an
 * APPLICATION makes: an application already shows a controls row (pagination, epochs, fullscreen)
 * that a speaker can join as a peer, while a page being viewed shows nothing at all, and a
 * permanent bar over someone's page to hold one button that most pages cannot even use would
 * cost more than it gives.
 *
 * <p>Today it holds the volume, and it is the reason the viewer claims the volume at all: a page
 * plays as authored everywhere else, and is only silenced where the reader has a way to give the
 * sound back. Room for more is the point of the shape: another control joins the row, and this
 * component takes itself off screen when none of them applies - the shared toolbar would still
 * draw itself for its close button alone.
 */
export function InterfaceViewerControls({
  soundOn,
  onToggleSound,
  hasAudio,
  onClose,
}: InterfaceViewerControlsProps) {
  // The wording is the application namespace's: it is the same control saying the same thing,
  // and a second copy of "Turn sound on" in six locales would only be a copy to keep in step.
  const t = useTranslations('applications');

  const soundControl = hasAudio ? (
    <button
      type="button"
      onClick={onToggleSound}
      aria-pressed={soundOn}
      aria-label={soundOn ? t('muteSound') : t('unmuteSound')}
      title={soundOn ? t('muteSound') : t('unmuteSound')}
      data-testid="interface-sound-toggle"
      // The box, radius and hover of the toolbar's own buttons (and of the identical speaker in
      // an application's controls). A different shape inside one pill is what makes a control
      // read as bolted on rather than part of it.
      className="w-7 h-7 p-0 rounded-xl inline-flex items-center justify-center text-theme-secondary hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] transition-colors"
    >
      {soundOn ? <Volume2 className="h-3.5 w-3.5" /> : <VolumeX className="h-3.5 w-3.5" />}
    </button>
  ) : null;

  // Nothing to offer means nothing on screen. The shared toolbar would still draw itself for
  // the close button alone, which is a panel whose only content is the way to dismiss it.
  if (!soundControl) return null;

  return (
    <InterfaceToolbar
      // No pagination of its own: the viewer draws its own item pager, and this toolbar is
      // here for what can be CHANGED about the page rather than for which item is shown.
      currentPage={0}
      totalPages={1}
      onPrevious={() => {}}
      onNext={() => {}}
      extraControls={soundControl}
      onClose={onClose}
      variant="light"
    />
  );
}
