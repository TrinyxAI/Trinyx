/**
 * @vitest-environment jsdom
 *
 * Who owns the volume of a page rendered by the shared InterfacePreview.
 *
 * The rule the in-frame controller enforces (see MEDIA_AUDIO_SCRIPT): an UNDEFINED mute flag
 * means "report whether there is media and otherwise leave the page exactly as authored", while
 * a boolean claims the volume. So the preview must forward `undefined` untouched - every surface
 * that offers no sound control depends on that - and forward a boolean when a caller does offer
 * one. It renders through two different branches depending on whether the page declares a
 * format, and a prop forwarded in only one of them is a page that plays out loud on half the app.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import * as React from 'react';

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver = ResizeObserverStub;

// jsdom computes no layout: give every element a real box so the letterboxed branch renders.
vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
  () => ({ width: 400, height: 600, top: 0, left: 0, right: 400, bottom: 600, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect,
);

const shadowProps: Record<string, unknown>[] = [];
vi.mock('@/app/workflows/builder/components/interface/InterfaceShadowPreview', () => ({
  InterfaceShadowPreview: (props: Record<string, unknown>) => {
    shadowProps.push(props);
    return <div data-testid="shadow-stub" />;
  },
}));

import { InterfacePreview } from '../InterfacePreview';

const HTML = '<div>page</div>';
/** The two render branches: a page that declares a shape, and one that does not. */
const BRANCHES: Array<[string, string | undefined]> = [
  ['a page with a declared format', 'vertical'],
  ['a page with no format', undefined],
];

afterEach(() => {
  cleanup();
  shadowProps.length = 0;
});

describe('InterfacePreview - who owns the volume', () => {
  it.each(BRANCHES)('leaves %s playing as authored when no caller claims the volume', (_label, format) => {
    render(<InterfacePreview htmlTemplate={HTML} format={format} autoFit={false} />);

    // Undefined, not false: false would SILENCE every surface that never asked to.
    expect(shadowProps[0].mediaMuted).toBeUndefined();
  });

  it.each(BRANCHES)('silences %s when the caller claims the volume', (_label, format) => {
    render(<InterfacePreview htmlTemplate={HTML} format={format} autoFit={false} mediaMuted />);

    expect(shadowProps[0].mediaMuted).toBe(true);
  });

  it.each(BRANCHES)('gives %s its sound back on the same prop', (_label, format) => {
    render(<InterfacePreview htmlTemplate={HTML} format={format} autoFit={false} mediaMuted={false} />);

    expect(shadowProps[0].mediaMuted).toBe(false);
  });

  it.each(BRANCHES)('passes on what %s reports about having any audio', (_label, format) => {
    const onMediaAudioPresence = vi.fn();

    render(
      <InterfacePreview
        htmlTemplate={HTML}
        format={format}
        autoFit={false}
        onMediaAudioPresence={onMediaAudioPresence}
      />,
    );

    expect(shadowProps[0].onMediaAudioPresence).toBe(onMediaAudioPresence);
  });
});
