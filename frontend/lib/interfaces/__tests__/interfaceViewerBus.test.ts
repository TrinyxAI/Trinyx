// @vitest-environment jsdom
/**
 * The channel between the app header and the page being viewed.
 *
 * It exists because the two halves of one button live apart: the header draws it, and only the
 * page knows whether pressing it would show anything. Get the direction wrong and the button is
 * either always there over pages with nothing to control, or never there at all.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { InterfaceViewerControlsState } from '../interfaceViewerBus';
import {
  emitInterfaceViewerControls,
  emitInterfaceViewerControlsToggle,
  onInterfaceViewerControls,
  onInterfaceViewerControlsToggle,
} from '../interfaceViewerBus';

/** A page with a sound to control, and one with nothing at all. */
const offering: InterfaceViewerControlsState = { available: true, open: true, soundOn: true };
const nothing: InterfaceViewerControlsState = { available: false, open: false, soundOn: false };

afterEach(() => vi.restoreAllMocks());

describe('the viewer telling the header what it can offer', () => {
  it('delivers what the page says it can control', () => {
    const heard: InterfaceViewerControlsState[] = [];
    const stop = onInterfaceViewerControls((state) => heard.push(state));

    emitInterfaceViewerControls(offering);

    expect(heard).toEqual([offering]);
    stop();
  });

  it('delivers the page saying it can offer nothing, which is what hides the button', () => {
    const heard: InterfaceViewerControlsState[] = [];
    const stop = onInterfaceViewerControls((state) => heard.push(state));

    emitInterfaceViewerControls(nothing);

    expect(heard).toEqual([nothing]);
    stop();
  });

  it('stops delivering once unsubscribed, so a header that left the page hears nothing', () => {
    const heard: unknown[] = [];
    const stop = onInterfaceViewerControls((state) => heard.push(state));
    stop();

    emitInterfaceViewerControls(offering);

    expect(heard).toEqual([]);
  });
});

describe('the header telling the viewer the reader pressed the button', () => {
  it('delivers the press', () => {
    const pressed = vi.fn();
    const stop = onInterfaceViewerControlsToggle(pressed);

    emitInterfaceViewerControlsToggle();

    expect(pressed).toHaveBeenCalledTimes(1);
    stop();
  });

  it('delivers every press, because the button opens AND closes', () => {
    const pressed = vi.fn();
    const stop = onInterfaceViewerControlsToggle(pressed);

    emitInterfaceViewerControlsToggle();
    emitInterfaceViewerControlsToggle();

    expect(pressed).toHaveBeenCalledTimes(2);
    stop();
  });

  it('stops delivering once unsubscribed', () => {
    const pressed = vi.fn();
    const stop = onInterfaceViewerControlsToggle(pressed);
    stop();

    emitInterfaceViewerControlsToggle();

    expect(pressed).not.toHaveBeenCalled();
  });

  it('keeps the two directions apart, so a press is not read as an availability change', () => {
    const availability = vi.fn();
    const pressed = vi.fn();
    const stopA = onInterfaceViewerControls(availability);
    const stopB = onInterfaceViewerControlsToggle(pressed);

    emitInterfaceViewerControlsToggle();

    expect(availability).not.toHaveBeenCalled();
    expect(pressed).toHaveBeenCalled();
    stopA();
    stopB();
  });
});

/**
 * The module is imported by a header and a page that both render on the server. Every entry
 * point has to be a no-op there rather than reaching for a `window` that does not exist.
 */
describe('on the server, where there is no window', () => {
  const withoutWindow = (run: () => void) => {
    vi.stubGlobal('window', undefined);
    try {
      run();
    } finally {
      vi.unstubAllGlobals();
    }
  };

  it('announces nothing instead of throwing', () => {
    withoutWindow(() => {
      expect(() => emitInterfaceViewerControls(offering)).not.toThrow();
    });
  });

  it('presses nothing instead of throwing', () => {
    withoutWindow(() => {
      expect(() => emitInterfaceViewerControlsToggle()).not.toThrow();
    });
  });

  it('still hands back an unsubscribe that can be called', () => {
    withoutWindow(() => {
      // A subscriber's cleanup runs whatever happened on the way in; returning nothing here
      // would throw on the way out.
      expect(() => onInterfaceViewerControls(() => {})()).not.toThrow();
      expect(() => onInterfaceViewerControlsToggle(() => {})()).not.toThrow();
    });
  });
});
