// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * Reading the values only a provider can name.
 *
 * <p>What this pins is not the fetching but WHEN and WITH WHAT. Each ask costs
 * a call to the provider, so a parameter the catalogue already describes must
 * not trigger one; and the answer belongs to one key, so a list read on the
 * platform's key must not be reused for the reader's own.
 */

const mocks = vi.hoisted(() => ({ getModelOptions: vi.fn() }));

vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { getModelOptions: mocks.getModelOptions },
}));

import { dynamicParamsOf, useGenerationOptions } from '../useGenerationOptions';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

function model(over: Partial<GenerationModel> = {}): GenerationModel {
  return {
    model: 'tts-fast',
    kind: 'voice',
    label: 'TTS Fast',
    provider: 'Vendor',
    iconSlug: null,
    apiToolId: 'tool-1',
    integrationName: 'vendor',
    accepts: ['prompt', 'voice'],
    required: ['prompt'],
    limits: { voice: { optionsAvailable: true } },
    billedOn: null,
    measuredUnit: 'character',
    defaultQuantity: null,
    price: { unit: 'character', baseCredits: '0', unitCredits: '1' },
    async: false,
    ...over,
  };
}

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getModelOptions.mockResolvedValue({
    success: true,
    options: [{ value: '21m00', label: 'Rachel' }],
    count: 1,
  });
});

describe('dynamicParamsOf', () => {
  it('names only the parameters the model marked, so nothing else is ever asked about', () => {
    expect(dynamicParamsOf(model())).toEqual(['voice']);
  });

  it('a documented list is NOT one of them: it is already in hand and costs nothing', () => {
    expect(dynamicParamsOf(model({ limits: { voice: { allowed: ['a', 'b'] } } }))).toEqual([]);
  });

  it('no model means nothing to ask, rather than asking about undefined', () => {
    expect(dynamicParamsOf(null)).toEqual([]);
  });
});

describe('useGenerationOptions', () => {
  it('asks once per marked parameter and returns its values against that name', async () => {
    const { result } = renderHook(
      () => useGenerationOptions(model(), 'platform', null, true),
      { wrapper },
    );

    await waitFor(() => expect(result.current.voice?.options).toHaveLength(1));
    expect(result.current.voice.options[0]).toEqual({ value: '21m00', label: 'Rachel' });
    expect(mocks.getModelOptions).toHaveBeenCalledTimes(1);
    expect(mocks.getModelOptions).toHaveBeenCalledWith('tts-fast', 'voice', 'platform', null);
  });

  it('holds the request back while disabled: a field nobody has opened costs nothing', async () => {
    renderHook(() => useGenerationOptions(model(), 'platform', null, false), { wrapper });

    await waitFor(() => expect(mocks.getModelOptions).not.toHaveBeenCalled());
  });

  it('sends the pinned key only when the reader is the payer', async () => {
    const { result } = renderHook(
      () => useGenerationOptions(model(), 'user', 42, true),
      { wrapper },
    );

    await waitFor(() => expect(result.current.voice?.options).toHaveLength(1));
    expect(mocks.getModelOptions).toHaveBeenCalledWith('tts-fast', 'voice', 'user', 42);
  });

  it('drops the pinned key on the platform payer, where it decides nothing', async () => {
    const { result } = renderHook(
      () => useGenerationOptions(model(), 'platform', 42, true),
      { wrapper },
    );

    await waitFor(() => expect(result.current.voice?.options).toHaveLength(1));
    // Left in, it would split the cache on a value that changed no answer.
    expect(mocks.getModelOptions).toHaveBeenCalledWith('tts-fast', 'voice', 'platform', null);
  });

  it('carries the REASON of a refusal, which an empty list would misstate', async () => {
    mocks.getModelOptions.mockResolvedValue({ success: false, error: 'no key is connected' });

    const { result } = renderHook(
      () => useGenerationOptions(model(), 'platform', null, true),
      { wrapper },
    );

    await waitFor(() => expect(result.current.voice?.error).toBe('no key is connected'));
    expect(result.current.voice.options).toEqual([]);
  });

  it('a transport failure reads as a failure, not as an account with no voices', async () => {
    mocks.getModelOptions.mockRejectedValue(new Error('network down'));

    const { result } = renderHook(
      () => useGenerationOptions(model(), 'platform', null, true),
      { wrapper },
    );

    await waitFor(() => expect(result.current.voice?.error).toBe('network down'));
  });

  it('says a truncated list is one, with the real total', async () => {
    mocks.getModelOptions.mockResolvedValue({
      success: true,
      options: [{ value: 'a', label: 'A' }],
      truncated: true,
      total_count: 812,
    });

    const { result } = renderHook(
      () => useGenerationOptions(model(), 'platform', null, true),
      { wrapper },
    );

    await waitFor(() => expect(result.current.voice?.truncated).toBe(true));
    expect(result.current.voice.totalCount).toBe(812);
  });

  it('another KEY of the same account is another question', async () => {
    const { rerender, result } = renderHook(
      ({ id }: { id: number }) => useGenerationOptions(model(), 'user', id, true),
      { wrapper, initialProps: { id: 42 } },
    );
    await waitFor(() => expect(result.current.voice?.options).toHaveLength(1));

    rerender({ id: 43 });

    // Voices belong to the key, not to the account. Reusing the answer offers
    // ids key 43 has never had, and the run fails after it is billed.
    await waitFor(() => expect(mocks.getModelOptions)
      .toHaveBeenCalledWith('tts-fast', 'voice', 'user', 43));
    expect(mocks.getModelOptions).toHaveBeenCalledTimes(2);
  });
});
