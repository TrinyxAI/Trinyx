// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import type { AIModel } from '@/hooks/useModels';

/**
 * These components used to render a second, per-image price format for a model
 * whose `mode` is `image`: "$0.04 per image" in the row, "Per image" as the
 * popover heading, with the output, cache and batch prices suppressed. That
 * existed to serve the image-generation tab in AI Providers, which fed the same
 * picker.
 *
 * <p>That tab is gone, and with it the only way such a row could arrive: every
 * consumer of these components reads `/v3/chat/models`, whose response is
 * mode-filtered to chat-capable rows before it leaves the server.
 *
 * <p>So the fixture below is deliberately impossible. Handing these components a
 * `mode: 'image'` model is the only way to prove the branch is really deleted
 * rather than merely never reached, and it is what makes these tests fail on the
 * pre-change code instead of passing on both sides.
 */

beforeAll(() => {
  // Radix positioning (@floating-ui) needs ResizeObserver, absent from jsdom.
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
});

const { ModelOptionDisplay, ModelInfoPopover } = await import('../ModelInfo');

const imageModel: AIModel = {
  id: 'an-image-model',
  name: 'An Image Model',
  provider: 'openai',
  mode: 'image',
  pricing: { input: 3, output: 15 },
  priceCacheRead: 0.3,
};

afterEach(() => {
  cleanup();
});

const wrap = (ui: React.ReactElement) => render(
  <NextIntlClientProvider locale="en" messages={enMessages}>{ui}</NextIntlClientProvider>,
);

/** The default trigger is the (i) button; opening it renders the price block. */
const openPopover = () => fireEvent.click(screen.getByRole('button', { name: 'View model details' }));

describe('ModelInfo has no per-image price left', () => {
  it('prices a row per million tokens even when the model says mode=image', () => {
    wrap(<ModelOptionDisplay model={imageModel} />);

    expect(screen.getByText('$3/$15 per 1M')).toBeInTheDocument();
    expect(screen.queryByText(/per image/i)).not.toBeInTheDocument();
  });

  it('heads the popover price block per million, never per image', () => {
    wrap(<ModelInfoPopover model={imageModel} />);
    openPopover();

    expect(screen.getByText('per 1M tokens')).toBeInTheDocument();
    expect(screen.queryByText(/per image/i)).not.toBeInTheDocument();
  });

  it('keeps the output and cache prices an image row used to suppress', () => {
    // The deleted branch nulled priceOut, priceCacheRead and priceBatchIn for an
    // image row, so the popover showed the input price alone.
    wrap(<ModelInfoPopover model={imageModel} />);
    openPopover();

    expect(screen.getByText('$15')).toBeInTheDocument();
    expect(screen.getByText('$0.3')).toBeInTheDocument();
  });
});
