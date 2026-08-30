// @vitest-environment jsdom
/**
 * The controls a reader gets over the page they are looking at.
 *
 * The rule worth pinning: a control is only offered when the page can honour it. A speaker over
 * a silent page is a promise nothing can keep, and the toolbar takes itself off screen entirely
 * rather than opening onto an empty row - which is also what keeps the header's button honest,
 * since that button exists to open this.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { InterfaceViewerControls } from '../InterfaceViewerControls';

afterEach(() => cleanup());

function renderControls(props: Partial<React.ComponentProps<typeof InterfaceViewerControls>> = {}) {
  const onToggleSound = props.onToggleSound ?? vi.fn();
  const onClose = props.onClose ?? vi.fn();
  render(
    <InterfaceViewerControls
      soundOn={props.soundOn ?? false}
      onToggleSound={onToggleSound}
      hasAudio={props.hasAudio ?? true}
      onClose={onClose}
    />,
  );
  return { onToggleSound, onClose };
}

describe('InterfaceViewerControls', () => {
  it('offers the sound control when the page has audio', () => {
    renderControls({ hasAudio: true });

    expect(screen.getByTestId('interface-sound-toggle')).toBeInTheDocument();
  });

  it('offers nothing at all over a silent page, rather than an empty row', () => {
    renderControls({ hasAudio: false });

    expect(screen.queryByTestId('interface-sound-toggle')).toBeNull();
    // And no toolbar either: the shared one WOULD draw itself for its close button alone, so
    // this component is what has to stand down.
    expect(screen.queryByTestId('interface-toolbar')).toBeNull();
  });

  it('says the sound is off while it is off', () => {
    renderControls({ soundOn: false });

    expect(screen.getByTestId('interface-sound-toggle')).toHaveAttribute('aria-pressed', 'false');
    // The label offers the action, not the state: pressing it turns the sound ON.
    expect(screen.getByTestId('interface-sound-toggle')).toHaveAttribute('aria-label', 'unmuteSound');
  });

  it('says the sound is on while it is on', () => {
    renderControls({ soundOn: true });

    expect(screen.getByTestId('interface-sound-toggle')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('interface-sound-toggle')).toHaveAttribute('aria-label', 'muteSound');
  });

  it('hands the volume back to the page that owns it', () => {
    const { onToggleSound } = renderControls();

    fireEvent.click(screen.getByTestId('interface-sound-toggle'));

    expect(onToggleSound).toHaveBeenCalledTimes(1);
  });

  it('can be dismissed', () => {
    const { onClose } = renderControls();
    // The shared toolbar renders the close button; find it among the toolbar's buttons.
    const buttons = screen.getAllByRole('button');

    fireEvent.click(buttons[buttons.length - 1]);

    expect(onClose).toHaveBeenCalled();
  });

  it('draws no pagination of its own, which the viewer already owns', () => {
    renderControls();

    // A "1 / 1" counter beside the volume would be a second, disagreeing pager.
    expect(screen.queryByText('1 / 1')).toBeNull();
  });
});
