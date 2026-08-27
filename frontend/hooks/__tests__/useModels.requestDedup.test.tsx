// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

/**
 * `getModelsOnce` de-duplicates concurrent callers by parking the in-flight promise in a module
 * variable, and it never let go of it: the release guard compared `modelsRequest` (the promise
 * returned by `.finally`) against `request` (the one before it), which are never the same object.
 * So after the first fetch of a page's life every later NON-FORCED call was handed that first
 * promise back, which made the 5-minute TTL above it dead code and `refresh()` the only way the
 * catalog could ever change.
 *
 * <p>It matters beyond staleness. `/v3/chat/models` answers PER TENANT (a CLOUD-linked CE must see
 * the cloud models), and a picker that mounts during the async auth bootstrap fetches it with no
 * token. That anonymous answer used to be frozen for the life of the page; with the TTL alive it
 * clears on the first fetch attempt made after the TTL, so a re-mount or a refresh recovers it
 * (a picker that stays mounted still holds it). The window itself is a known gap documented at the
 * call site, deliberately not closed here.
 */

const { get, getTokenProvider } = vi.hoisted(() => ({ get: vi.fn(), getTokenProvider: vi.fn() }));

// useModels imports apiClient from the '@/lib/api' BARREL, so that is the specifier to mock.
vi.mock('@/lib/api', () => ({ apiClient: { get, getTokenProvider } }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { get, getTokenProvider } }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => undefined }));

import { useModels, clearModelsCache } from '../useModels';

function provider(name: string) {
  return { name, defaultModel: '', supportsStreaming: true, supportsToolCalling: true, displayOrder: 1, models: [] };
}

beforeEach(() => {
  vi.resetAllMocks();
  clearModelsCache();
  getTokenProvider.mockReturnValue(async () => 'jwt-abc');
});

afterEach(() => {
  clearModelsCache();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('useModels in-flight de-duplication', () => {
  it('releases itself, so the TTL can actually expire', async () => {
    // Exercised with force=false on purpose: `refresh()` passes force=true, which bypasses the
    // de-dup guard entirely and would pass with the bug still in place.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    get.mockResolvedValueOnce({ providers: [provider('p-first')] });

    const first = renderHook(() => useModels());
    await waitFor(() => expect(first.result.current.providers[0]?.name).toBe('p-first'));

    await vi.advanceTimersByTimeAsync(6 * 60 * 1000);
    get.mockResolvedValueOnce({ providers: [provider('p-second')] });

    const second = renderHook(() => useModels());
    await vi.advanceTimersByTimeAsync(50);

    // Asserted BEFORE waiting on the rendered value: with the bug the second call never leaves,
    // so a waitFor would sit out its full 20 s and then report a timeout that names nothing. This
    // fails in milliseconds and says exactly what went wrong.
    expect(get, 'the second mount must reach the server once the TTL has expired')
      .toHaveBeenCalledTimes(2);
    await waitFor(() => expect(second.result.current.providers[0]?.name).toBe('p-second'));
  });

  it('still de-duplicates callers that arrive while a fetch is open', async () => {
    // The behaviour the guard exists for, and the reason the fix is "release it" rather than
    // "remove it": three pickers mounting together must cost one request, not three.
    let release: (v: unknown) => void = () => {};
    get.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));

    const a = renderHook(() => useModels());
    const b = renderHook(() => useModels());
    const c = renderHook(() => useModels());

    // Poll rather than sleep: a fixed 10 ms is enough locally and is exactly the margin a loaded
    // CI runner eats. waitFor retries until the three effects have run, then the assertion below
    // is about de-duplication rather than about timing.
    await waitFor(() => expect(get).toHaveBeenCalled());
    expect(get, 'three simultaneous mounts must share one request').toHaveBeenCalledTimes(1);

    release({ providers: [provider('p-shared')] });
    await waitFor(() => expect(a.result.current.providers[0]?.name).toBe('p-shared'));
    expect(b.result.current.providers[0]?.name).toBe('p-shared');
    expect(c.result.current.providers[0]?.name).toBe('p-shared');
  });

  it('serves a second mount inside the TTL from cache', async () => {
    get.mockResolvedValueOnce({ providers: [provider('p-cached')] });

    const first = renderHook(() => useModels());
    await waitFor(() => expect(first.result.current.providers).toHaveLength(1));

    const second = renderHook(() => useModels());
    await waitFor(() => expect(second.result.current.providers[0]?.name).toBe('p-cached'));

    // Releasing the de-dup must not turn every mount into a request.
    expect(get).toHaveBeenCalledTimes(1);
  });
});
