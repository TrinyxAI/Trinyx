// @vitest-environment jsdom
/**
 * The gateway refuses a deactivated account with the SAME 429 it uses for quota
 * ({@code {"error":"Quota exceeded","message":"Inactive account"}}), so apiClient's retry
 * ladder used to treat it as a rate limit: every blocked call was retried with exponential
 * backoff before failing, even though the answer can never change until the person
 * reactivates. These tests pin the two halves of the fix, and that a REAL rate limit is
 * still retried, since telling the two apart rests only on the message.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ApiClient, ApiError, ACCOUNT_INACTIVE_EVENT } from '../api-client';

function errorJsonResponse(status: number, body: any = {}) {
  return {
    ok: false,
    status,
    statusText: `HTTP ${status}`,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

function okResponse(body: any = { ok: true }) {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: new Headers({ 'content-type': 'application/json' }),
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

const INACTIVE_BODY = { error: 'Quota exceeded', message: 'Inactive account' };
const RATE_LIMIT_BODY = { error: 'Quota exceeded', message: 'Too many requests' };

describe('ApiClient, inactive account handling', () => {
  let client: ApiClient;
  let fetchMock: ReturnType<typeof vi.fn>;
  let events: Event[];
  const collect = (e: Event) => events.push(e);

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fetchMock = vi.fn();
    global.fetch = fetchMock as unknown as typeof fetch;
    client = new ApiClient({ baseUrl: '/api/proxy', timeout: 5000, retries: 3 });
    events = [];
    window.addEventListener(ACCOUNT_INACTIVE_EVENT, collect);
  });

  afterEach(() => {
    window.removeEventListener(ACCOUNT_INACTIVE_EVENT, collect);
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('gives up on the first response instead of walking the retry ladder', async () => {
    fetchMock.mockResolvedValue(errorJsonResponse(429, INACTIVE_BODY));

    await expect(client.get('/anything', { skipAuth: true })).rejects.toBeInstanceOf(ApiError);

    // Retrying a blocked account only delays the interstitial: same answer every time.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('announces the blocked account so the restore interstitial can open', async () => {
    fetchMock.mockResolvedValue(errorJsonResponse(429, INACTIVE_BODY));

    await expect(client.get('/anything', { skipAuth: true })).rejects.toBeInstanceOf(ApiError);

    expect(events).toHaveLength(1);
    expect(events[0].type).toBe(ACCOUNT_INACTIVE_EVENT);
  });

  it('keeps the original message on the error so callers can tell the two 429s apart', async () => {
    fetchMock.mockResolvedValue(errorJsonResponse(429, INACTIVE_BODY));

    const error = await client
      .get<never>('/anything', { skipAuth: true })
      .catch((e) => e as ApiError);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(429);
    expect(error.message).toBe('Inactive account');
  });

  it('still retries a genuine rate limit, which a blanket 429 bail-out would have broken', async () => {
    fetchMock
      .mockResolvedValueOnce(errorJsonResponse(429, RATE_LIMIT_BODY))
      .mockResolvedValueOnce(okResponse({ value: 42 }));

    const result = await client.get<{ value: number }>('/anything', { skipAuth: true });

    expect(result).toEqual({ value: 42 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(events).toHaveLength(0);
  });

  it('does not mistake a non-429 carrying the same message for a blocked account', async () => {
    fetchMock.mockResolvedValue(errorJsonResponse(403, { message: 'Inactive account' }));

    await expect(client.get('/anything', { skipAuth: true })).rejects.toBeInstanceOf(ApiError);

    expect(events).toHaveLength(0);
  });
});
