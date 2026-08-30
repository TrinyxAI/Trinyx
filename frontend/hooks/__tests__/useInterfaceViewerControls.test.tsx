// @vitest-environment jsdom
/**
 * Where the controls button's existence is actually decided.
 *
 * The header draws the button and the page knows what there is to control, so this is the seam
 * between them - and a seam is exactly the kind of thing that both sides' tests pass over. The
 * component test proves a button appears when handed a handler; the page test proves the page
 * announces itself. Neither notices if this stops joining them.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';
import { emitInterfaceViewerControls } from '@/lib/interfaces/interfaceViewerBus';
import { useInterfaceViewerControls } from '../useInterfaceViewerControls';

const announce = (state: { available: boolean; open?: boolean; soundOn?: boolean }) =>
  act(() => {
    emitInterfaceViewerControls({ open: false, soundOn: false, ...state });
  });

afterEach(() => cleanup());

describe('useInterfaceViewerControls - whether the button exists at all', () => {
  it('offers nothing before the page has said anything', () => {
    const { result } = renderHook(() => useInterfaceViewerControls(true));

    expect(result.current.onToggle).toBeUndefined();
  });

  it('offers the button once the page says it has something to control', () => {
    const { result } = renderHook(() => useInterfaceViewerControls(true));

    announce({ available: true });

    expect(result.current.onToggle).toBeInstanceOf(Function);
  });

  it('takes it away again when the page says it has nothing', () => {
    const { result } = renderHook(() => useInterfaceViewerControls(true));
    announce({ available: true });

    announce({ available: false });

    expect(result.current.onToggle).toBeUndefined();
  });

  it('offers nothing at all when an interface is not what is on screen', () => {
    const { result } = renderHook(() => useInterfaceViewerControls(false));

    announce({ available: true });

    // A page elsewhere in the app must not inherit a button from an interface the reader left.
    expect(result.current.onToggle).toBeUndefined();
  });

  it('drops what the viewer said the moment the reader leaves the page', () => {
    const { result, rerender } = renderHook(
      ({ onInterface }) => useInterfaceViewerControls(onInterface),
      { initialProps: { onInterface: true } },
    );
    announce({ available: true, soundOn: true });
    expect(result.current.onToggle).toBeInstanceOf(Function);

    rerender({ onInterface: false });

    expect(result.current.onToggle).toBeUndefined();
    expect(result.current.soundOn).toBe(false);
  });

  it('stops listening on unmount, so a stale page cannot speak for the header', () => {
    const { result, unmount } = renderHook(() => useInterfaceViewerControls(true));
    unmount();

    // No throw, and nothing the header could act on.
    announce({ available: true });

    expect(result.current.onToggle).toBeUndefined();
  });
});

describe('useInterfaceViewerControls - what the button says', () => {
  it('reports the panel closed until the page says otherwise', () => {
    const { result } = renderHook(() => useInterfaceViewerControls(true));
    announce({ available: true });

    expect(result.current.open).toBe(false);
  });

  it('reports the panel open, so the button can say what pressing it does', () => {
    const { result } = renderHook(() => useInterfaceViewerControls(true));

    announce({ available: true, open: true });

    expect(result.current.open).toBe(true);
  });

  it('reports the page making a sound, so that is visible with the panel dismissed', () => {
    const { result } = renderHook(() => useInterfaceViewerControls(true));

    announce({ available: true, open: false, soundOn: true });

    expect(result.current.soundOn).toBe(true);
  });

  it('reports neither once there is nothing to control, whatever the page last said', () => {
    const { result } = renderHook(() => useInterfaceViewerControls(true));
    announce({ available: true, open: true, soundOn: true });

    announce({ available: false, open: true, soundOn: true });

    expect(result.current.open).toBe(false);
    expect(result.current.soundOn).toBe(false);
  });
});
