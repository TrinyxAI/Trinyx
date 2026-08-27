import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * Regression guard for the active-workspace header on data-table raw fetches.
 *
 * authenticatedFetch bypasses apiClient's request pipeline to get at the raw Response,
 * and in doing so it used to skip the header apiClient adds for every other call. The
 * Next.js proxy forwards only what the client sends and applies no server-side default,
 * so every data-table read and write resolved under the user's DEFAULT workspace
 * instead of the active one. Nothing errored: the user simply saw, and edited, the
 * wrong workspace's rows.
 *
 * The header must also be spread BEFORE the caller's headers, so a caller-supplied
 * X-Active-Organization-ID still wins. That is the precedence apiClient's executeFetch
 * gives, and the per-request override (Quota / Storage workspace filters) depends on it.
 */
const mockGetAuthToken = vi.fn();
const mockGetActiveOrgHeaderForRequest = vi.fn();

vi.mock('@/lib/api', () => ({
  apiClient: {
    getAuthToken: () => mockGetAuthToken(),
  },
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => mockGetActiveOrgHeaderForRequest(),
}));

import { authenticatedFetch } from '../authenticatedFetch';

/** Headers the implementation passed to fetch on its most recent call. */
function sentHeaders(): Record<string, string> {
  const call = vi.mocked(global.fetch).mock.calls.at(-1);
  return (call?.[1]?.headers ?? {}) as Record<string, string>;
}

describe('authenticatedFetch active-workspace header', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    mockGetAuthToken.mockResolvedValue('test-token');
    mockGetActiveOrgHeaderForRequest.mockReturnValue({ 'X-Active-Organization-ID': 'org-active' });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('sends the active workspace so rows do not resolve under the default one', async () => {
    await authenticatedFetch('/api/proxy/rows');

    expect(sentHeaders()['X-Active-Organization-ID']).toBe('org-active');
  });

  it('lets a caller-supplied workspace win, so per-request overrides still work', async () => {
    // Quota / Storage scope a single call to another workspace without switching the app.
    await authenticatedFetch('/api/proxy/rows', {
      headers: { 'X-Active-Organization-ID': 'org-override' },
    });

    expect(sentHeaders()['X-Active-Organization-ID']).toBe('org-override');
  });

  it('omits the header entirely in personal scope rather than sending an empty value', async () => {
    // An empty string is not the same as absent: the gateway would have to special-case it.
    mockGetActiveOrgHeaderForRequest.mockReturnValue({});

    await authenticatedFetch('/api/proxy/rows');

    expect(sentHeaders()).not.toHaveProperty('X-Active-Organization-ID');
  });

  it('is additive: Authorization, Content-Type and caller headers all survive', async () => {
    await authenticatedFetch('/api/proxy/rows', {
      method: 'POST',
      headers: { 'X-Custom': 'kept' },
    });

    expect(sentHeaders()).toMatchObject({
      'Content-Type': 'application/json',
      'X-Active-Organization-ID': 'org-active',
      'X-Custom': 'kept',
      Authorization: 'Bearer test-token',
    });
  });

  it('still scopes the request when there is no token to be had', async () => {
    // The workspace header and the bearer come from different sources; losing one
    // must not silently drop the other back to the default workspace. getAuthToken
    // answers null only once its wait for the auth bootstrap is exhausted.
    mockGetAuthToken.mockResolvedValue(null);

    await authenticatedFetch('/api/proxy/rows');

    expect(sentHeaders()['X-Active-Organization-ID']).toBe('org-active');
    expect(sentHeaders()).not.toHaveProperty('Authorization');
  });

  // The case this replaces mocked getAuthToken REJECTING. That cannot happen: ApiClient.getToken
  // catches a throwing provider and returns null, so the old assertion pinned a contract the real
  // object does not have. What is worth pinning is that a failed refresh, which surfaces as null,
  // still leaves the workspace scoping intact - covered by the test above.
});
