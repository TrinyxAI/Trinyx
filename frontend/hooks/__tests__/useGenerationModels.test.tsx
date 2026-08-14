// @vitest-environment jsdom
/**
 * The generation catalogue is read by a surface that decides whether to OFFER a
 * generation, and by the dialog that runs one. This pins the two things that
 * decision needs and that a plain query does not give it:
 *
 * <ol>
 *   <li>a 404 means the install does not serve generation at all, and that is
 *       NOT the same answer as a catalogue that came back empty. The first
 *       cannot be fixed from the app; the second is a seed away;</li>
 *   <li>everything else - in flight, a 5xx, a transport that never delivered -
 *       stays UNKNOWN. A caller must not be able to read a hiccup as absence,
 *       because the two are indistinguishable at the moment the page renders
 *       and only one of them justifies removing a feature.</li>
 * </ol>
 *
 * <p>And one request per page: the entry point and the dialog behind it read
 * the same key, so a shared cache is what keeps the check free.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React from 'react';
import { renderHook, waitFor, render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const api = vi.hoisted(() => ({ getModels: vi.fn() }));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { getModels: api.getModels },
}));

import { ApiError } from '@/lib/api/api-client';
import { useGenerationModels, GENERATION_MODELS_QUERY_KEY } from '../useGenerationModels';

function wrapper() {
  // retry: false - this is about what one answer means, not about retrying.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { Wrapper, client };
}

function catalogue(models: unknown[]) {
  return { models, count: models.length, kinds: ['video'] };
}

beforeEach(() => {
  api.getModels.mockReset();
});

describe('useGenerationModels', () => {
  it('reports the surface ready when it answers with at least one model', async () => {
    api.getModels.mockResolvedValue(catalogue([{ model: 'seedance-2.0', kind: 'video' }]));
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.availability).toBe('ready'));
    expect(result.current.models).toHaveLength(1);
  });

  it('separates an empty catalogue from a missing surface', async () => {
    // The feature is installed and answered; it has nothing to offer YET, which
    // is an administrator's problem and not a reason to pretend it is absent.
    api.getModels.mockResolvedValue(catalogue([]));
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.availability).toBe('empty'));
    expect(result.current.models).toHaveLength(0);
  });

  it('reports absent only on a 404, which is what a config-gated surface answers', async () => {
    api.getModels.mockRejectedValue(new ApiError('Not Found', 404));
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.availability).toBe('absent'));
  });

  it('stays unknown on a 5xx, which does not say the feature is missing', async () => {
    api.getModels.mockRejectedValue(new ApiError('Bad Gateway', 502));
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });

    await waitFor(() => expect(api.getModels).toHaveBeenCalled());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.availability).toBe('unknown');
  });

  it('stays unknown when the request never reached a server', async () => {
    // A transport failure is not an ApiError and carries no status at all, so
    // it cannot be classified as anything.
    api.getModels.mockRejectedValue(new TypeError('Failed to fetch'));
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.availability).toBe('unknown');
  });

  it('stays unknown while the answer is still outstanding', () => {
    api.getModels.mockReturnValue(new Promise(() => {}));
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });

    expect(result.current.availability).toBe('unknown');
    expect(result.current.isLoading).toBe(true);
  });

  it('lets a 404 override a catalogue it already had, because the surface is gone', async () => {
    const { Wrapper, client } = wrapper();
    // Seed the cache the way a page that already asked would leave it.
    client.setQueryData(GENERATION_MODELS_QUERY_KEY, catalogue([{ model: 'seedance-2.0' }]));
    api.getModels.mockRejectedValue(new ApiError('Not Found', 404));

    const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });
    expect(result.current.availability).toBe('ready');

    // Generation is turned off on the install. The models still in hand
    // describe a surface that no longer answers, so the 404 has to win.
    await client.refetchQueries({ queryKey: GENERATION_MODELS_QUERY_KEY });

    await waitFor(() => expect(result.current.availability).toBe('absent'));
  });

  it('asks once for two readers, so a page and the dialog it opens share one answer', async () => {
    api.getModels.mockResolvedValue(catalogue([{ model: 'seedance-2.0' }]));
    const { Wrapper } = wrapper();

    function Reader() {
      const { availability } = useGenerationModels();
      return <span>{availability}</span>;
    }
    render(<Wrapper><Reader /><Reader /></Wrapper>);

    await waitFor(() => expect(api.getModels).toHaveBeenCalledTimes(1));
  });

  it('does not ask at all for a reader who cannot act on the answer', async () => {
    api.getModels.mockResolvedValue(catalogue([{ model: 'seedance-2.0' }]));
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useGenerationModels(false), { wrapper: Wrapper });

    expect(api.getModels).not.toHaveBeenCalled();
    expect(result.current.availability).toBe('unknown');
  });
});
