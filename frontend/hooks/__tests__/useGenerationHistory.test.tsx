// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React from 'react';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * The read side of the generation history, as the components consume it.
 *
 * <p>Three properties, each of which has a visible consequence: a body that is not a page must
 * degrade to an empty history rather than crash the screen during render; a closed panel must cost
 * no request; and a finished generation must invalidate EVERY page, because the new asset belongs
 * at the top and shifts all the others.
 */

const api = vi.hoisted(() => ({
  getGenerationHistory: vi.fn(),
  getGenerationProvenance: vi.fn(),
}));
vi.mock('@/lib/api/storage-api', () => ({ storageApi: api }));

import {
  GENERATION_HISTORY_PAGE_SIZE,
  useGenerationHistory,
  useGenerationProvenance,
  useInvalidateGenerationHistory,
} from '../useGenerationHistory';

function wrapperWith(client: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function newClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

const PAGE = {
  content: [{ id: 'a1', provenance: { model: 'flux-1.1-pro' } }],
  // A slice, not a counted page: `last` is the whole pager contract.
  last: false,
  number: 0,
  size: 12,
};

beforeEach(() => {
  api.getGenerationHistory.mockReset();
  api.getGenerationProvenance.mockReset();
});

describe('useGenerationHistory', () => {
  it('asks for the requested page and format, at the shared page size', async () => {
    api.getGenerationHistory.mockResolvedValue(PAGE);

    const { result } = renderHook(() => useGenerationHistory(1, 'image'), { wrapper: wrapperWith(newClient()) });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(api.getGenerationHistory).toHaveBeenCalledWith({
      page: 1, size: GENERATION_HISTORY_PAGE_SIZE, kind: 'image',
    });
    expect(result.current.entries).toHaveLength(1);
    expect(result.current.hasMore).toBe(true);
  });

  it('says there is no next page when the server said this one is the last', async () => {
    api.getGenerationHistory.mockResolvedValue({ ...PAGE, last: true });

    const { result } = renderHook(() => useGenerationHistory(), { wrapper: wrapperWith(newClient()) });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.hasMore).toBe(false);
  });

  it('costs nothing while the panel it lives in is closed', async () => {
    const { result } = renderHook(() => useGenerationHistory(0, undefined, false), {
      wrapper: wrapperWith(newClient()),
    });

    expect(api.getGenerationHistory).not.toHaveBeenCalled();
    expect(result.current.entries).toEqual([]);
  });

  it('degrades to an empty history on a body that is not a page', async () => {
    // Callers map over these entries during render. A 200 carrying something else - a proxy, a
    // gateway, a test double - would throw inside the component and take the page into the error
    // boundary, which is a much worse outcome than showing nothing.
    api.getGenerationHistory.mockResolvedValue({ nope: true } as never);

    const { result } = renderHook(() => useGenerationHistory(), { wrapper: wrapperWith(newClient()) });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.entries).toEqual([]);
    // An unreadable body must never claim a next page: offering one that does not exist strands
    // the reader on an empty screen.
    expect(result.current.hasMore).toBe(false);
  });

  it('reports a failure as a failure, not as an empty history', async () => {
    // The list draws two different sentences from these, and telling someone their assets do not
    // exist invites them to generate everything again.
    api.getGenerationHistory.mockRejectedValue(new Error('boom'));

    const { result } = renderHook(() => useGenerationHistory(), { wrapper: wrapperWith(newClient()) });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.entries).toEqual([]);
  });
});

describe('useInvalidateGenerationHistory', () => {
  it('drops every cached page, not just the one in view', async () => {
    // The new asset belongs at the top of page 0, which shifts every page after it: invalidating
    // one would leave the rest describing an order that no longer exists.
    api.getGenerationHistory.mockResolvedValue(PAGE);
    const client = newClient();
    const wrapper = wrapperWith(client);

    const page0 = renderHook(() => useGenerationHistory(0), { wrapper });
    const page1 = renderHook(() => useGenerationHistory(1), { wrapper });
    await waitFor(() => expect(page0.result.current.isLoading).toBe(false));
    await waitFor(() => expect(page1.result.current.isLoading).toBe(false));

    const { result } = renderHook(() => useInvalidateGenerationHistory(), { wrapper });
    act(() => result.current());

    await waitFor(() => expect(api.getGenerationHistory.mock.calls.length).toBeGreaterThanOrEqual(4));
  });
});

describe('useGenerationProvenance', () => {
  it('asks nothing without a file to ask about', () => {
    renderHook(() => useGenerationProvenance(null), { wrapper: wrapperWith(newClient()) });

    expect(api.getGenerationProvenance).not.toHaveBeenCalled();
  });

  it('asks nothing where the answer could not be acted on', () => {
    // The viewer passes false when it offers no Regenerate control: a request whose answer nothing
    // can use is a request per file opened, for nothing.
    renderHook(() => useGenerationProvenance('f1', false), { wrapper: wrapperWith(newClient()) });

    expect(api.getGenerationProvenance).not.toHaveBeenCalled();
  });

  it('hands back the recipe of a generated asset', async () => {
    api.getGenerationProvenance.mockResolvedValue({ model: 'flux-1.1-pro' });

    const { result } = renderHook(() => useGenerationProvenance('f1'), { wrapper: wrapperWith(newClient()) });

    await waitFor(() => expect(result.current.provenance).toEqual({ model: 'flux-1.1-pro' }));
  });

  it('reports null for a file that was not generated here', async () => {
    api.getGenerationProvenance.mockResolvedValue(null);

    const { result } = renderHook(() => useGenerationProvenance('f1'), { wrapper: wrapperWith(newClient()) });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.provenance).toBeNull();
  });

  it('asks once per file: a recipe is written when the asset is made and never changes', async () => {
    api.getGenerationProvenance.mockResolvedValue({ model: 'flux-1.1-pro' });
    const wrapper = wrapperWith(newClient());

    const first = renderHook(() => useGenerationProvenance('f1'), { wrapper });
    await waitFor(() => expect(first.result.current.provenance).not.toBeNull());
    renderHook(() => useGenerationProvenance('f1'), { wrapper });

    expect(api.getGenerationProvenance).toHaveBeenCalledTimes(1);
  });
});
