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

  /**
   * A 200 that does not carry a catalogue.
   *
   * <p>Every case above hands the hook a well-formed body, and that is what let
   * a real one through: anything that answers this path without serving it (a
   * proxy, a gateway, a test double standing in for the whole API) replies 200
   * with a body that has no `models` at all. Reading `.length` off it threw
   * inside a memo DURING RENDER, so the throw did not land on generation, it
   * landed on whatever page mounted the entry point, and a chat composer that
   * never asked for a generation stopped rendering.
   *
   * <p>These pin the contract that makes that impossible: a body the hook
   * cannot read as a catalogue yields NO models and a verdict, never an
   * exception. They fail on the pre-fix hook with the TypeError it threw.
   */
  describe('a 200 whose body is not a catalogue', () => {
    it('reports empty instead of throwing when the body carries no models at all', async () => {
      // Exactly what a stand-in for the API sends: a 200, and nothing in it.
      api.getModels.mockResolvedValue({});
      const { Wrapper } = wrapper();

      const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));
      // 'empty' and not 'unknown': an answer DID arrive and it names no model,
      // which is what the reader faces. It keeps the entry point disabled with
      // its reason rather than enabled onto a dialog with nothing in it.
      expect(result.current.availability).toBe('empty');
      expect(result.current.models).toEqual([]);
    });

    it('reports empty when models is present but is not a list', async () => {
      // The callers map, filter and find over this. A non-array that survived
      // normalisation would reach those calls and throw there instead, which is
      // the same crash one stack frame later.
      api.getModels.mockResolvedValue({ models: {}, count: 0, kinds: [] });
      const { Wrapper } = wrapper();

      const { result } = renderHook(() => useGenerationModels(), { wrapper: Wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));
      expect(result.current.availability).toBe('empty');
      expect(Array.isArray(result.current.models)).toBe(true);
      expect(result.current.models).toEqual([]);
    });

    it('renders a reader of that answer instead of taking its page down', async () => {
      // The point of the fix, stated as the symptom it removes: the hook is
      // read during render, so a throw here is not a failed feature, it is a
      // blank page. Rendering at all is the assertion.
      api.getModels.mockResolvedValue({});
      const { Wrapper } = wrapper();

      function Reader() {
        const { availability, models } = useGenerationModels();
        return <span data-testid="verdict">{`${availability}:${models.length}`}</span>;
      }
      const { getByTestId } = render(<Wrapper><Reader /></Wrapper>);

      // The first render is legitimately 'unknown:0' (the answer is still out),
      // so settle before reading: the pre-fix hook threw on the render that
      // consumed the answer, which is the one that has to be asserted.
      await waitFor(() => expect(getByTestId('verdict').textContent).toBe('empty:0'));
    });
  });
});
