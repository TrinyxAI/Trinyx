import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ApiClient } from '../api-client';

/**
 * `getAuthToken` is the public door onto the token resolution every apiClient request already
 * performs, wait included. It exists because raw `fetch` call sites (media blobs, uploads,
 * streaming) were reaching for `getTokenProvider()` instead, and the provider is installed inside
 * an async bootstrap: during that window they sent no `Authorization` at all. In prod over the 7
 * days to 2026-08-25 that was the gateway's most frequent error, 98 x 401 on
 * `GET /api/files/by-id/<id>/raw`, rejected in 0 ms with "Authentication required".
 *
 * These tests pin the WAIT, since the wait is the entire point of preferring this over the
 * provider - and the last one pins that the wait still terminates for a signed-out caller.
 */
describe('ApiClient.getAuthToken', () => {
  let client: ApiClient;

  beforeEach(() => {
    client = new ApiClient();
    vi.useFakeTimers();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('returns the token straight away when the provider is already installed', async () => {
    client.setTokenProvider(() => Promise.resolve('jwt-ready'));
    await expect(client.getAuthToken()).resolves.toBe('jwt-ready');
  });

  it('waits for a provider installed after the caller already asked', async () => {
    // The race itself: a component mounts and asks for a token before smart-providers.tsx has
    // finished installing one. Reading the provider directly returns undefined here, which is
    // exactly how an anonymous request reached the gateway.
    const pending = client.getAuthToken();

    await vi.advanceTimersByTimeAsync(250);
    client.setTokenProvider(() => Promise.resolve('jwt-late'));
    await vi.advanceTimersByTimeAsync(200);

    await expect(pending).resolves.toBe('jwt-late');
  });

  it('retries a provider that answers null before the session is ready', async () => {
    let calls = 0;
    client.setTokenProvider(() => Promise.resolve(++calls === 1 ? null : 'jwt-second'));

    const pending = client.getAuthToken();
    await vi.advanceTimersByTimeAsync(400);

    await expect(pending).resolves.toBe('jwt-second');
  });

  it('never rejects: a provider that throws resolves to null', async () => {
    // Load-bearing, not a convenience. authenticatedFetch dropped its try/catch on this
    // guarantee, so nothing else would catch a regression here. It has to be pinned with a REAL
    // client whose PROVIDER throws: mocking getAuthToken itself to reject would assert a shape
    // the real object cannot produce, which is why the test it replaces was removed.
    client.setTokenProvider(() => { throw new Error('OIDC silent refresh blew up'); });

    const pending = client.getAuthToken();
    await vi.advanceTimersByTimeAsync(2000);

    await expect(pending).resolves.toBeNull();
  });

  it('never rejects: a provider returning a rejected promise resolves to null', async () => {
    client.setTokenProvider(() => Promise.reject(new Error('network down')));

    const pending = client.getAuthToken();
    await vi.advanceTimersByTimeAsync(2000);

    await expect(pending).resolves.toBeNull();
  });

  it('gives up and returns null so a signed-out caller is answered rather than hung', async () => {
    const pending = client.getAuthToken();

    // 10 polls of 100 ms is the whole wait; past it there is genuinely no session.
    await vi.advanceTimersByTimeAsync(2000);

    await expect(pending).resolves.toBeNull();
  });
});
