// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useInterfaceFileUrls } from '../useInterfaceFileUrls';

vi.mock('@/lib/api/api-client', () => ({
  // getTokenProvider is mocked as "not installed yet" on purpose: that is the real state during
  // the async auth bootstrap, and it is what the pre-fix code read. Any call site that reaches
  // for it instead of getAuthToken therefore reproduces the prod 401 in these tests.
  apiClient: { getAuthToken: vi.fn(), getTokenProvider: vi.fn(() => undefined) },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: vi.fn(() => ({ 'X-Active-Organization-ID': 'org-7' })),
}));

import { apiClient } from '@/lib/api/api-client';
const mockGetAuthToken = vi.mocked(apiClient.getAuthToken);

const ID = '9a443915-a594-48a1-9760-e7a1b4b2eaf7';
const RAW = `/api/proxy/files/by-id/${ID}/raw?disposition=inline`;

function fileRef() {
  return { _type: 'file' as const, path: 'tenant1/run/abc.png', name: 'abc.png', mimeType: 'image/png', size: 3, id: ID };
}

beforeEach(() => {
  vi.resetAllMocks();
  mockGetAuthToken.mockResolvedValue('jwt-abc');
});
afterEach(() => vi.restoreAllMocks());

function mockFetchOk() {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    blob: () => Promise.resolve(new Blob(['png'], { type: 'image/png' })),
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('useInterfaceFileUrls', () => {
  it('resolves each FileRef to a base64 data: URI fetched with the Bearer + active-org header (no token in the URL)', async () => {
    const fetchMock = mockFetchOk();
    const { result } = renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:/));
    // The resolved value is a self-contained data: URI - renders in a sandboxed (no same-origin) iframe.
    expect(result.current.resolveFileUrl(RAW).startsWith('data:image/png;base64,')).toBe(true);

    const [calledUrl, init] = fetchMock.mock.calls[0];
    // SECURITY: the by-id URL is fetched with the header - never with a ?token=.
    expect(calledUrl).toBe(RAW);
    expect(String(calledUrl)).not.toMatch(/token=/);
    expect(init.headers.Authorization).toBe('Bearer jwt-abc');
    expect(init.headers['X-Active-Organization-ID']).toBe('org-7'); // cross-org resolution
  });

  it('returns the raw URL unchanged for an unknown/unresolved key (never injects a token)', async () => {
    mockFetchOk();
    const { result } = renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, true));
    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:/));
    const other = '/api/proxy/files/by-id/other/raw?disposition=inline';
    expect(result.current.resolveFileUrl(other)).toBe(other);
    expect(result.current.resolveFileUrl(other)).not.toMatch(/token=/);
  });

  // Regression - prod 2026-08-25. This hook produced the gateway's most frequent error: 98 x 401
  // on GET /api/files/by-id/<id>/raw in 7 days, arriving in PAIRS 0.0s apart. It read
  // apiClient.getTokenProvider() directly, which is undefined until the async auth bootstrap in
  // smart-providers.tsx installs it. Inside that window the fetch went out anonymous (401 #1),
  // res.ok was false so the entry stayed unresolved, and resolveFileUrl then handed the interface
  // the RAW by-id URL - which the sandboxed iframe (allow-scripts only, so no header and no
  // same-origin) could only load anonymously too (401 #2), leaving the image permanently broken.
  it('waits for the token instead of resolving every file anonymously while auth is still booting', async () => {
    let releaseToken: (t: string) => void = () => {};
    mockGetAuthToken.mockReturnValue(new Promise<string>((resolve) => { releaseToken = resolve; }));
    const fetchMock = mockFetchOk();

    const { result } = renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, true));

    await Promise.resolve();
    expect(fetchMock).not.toHaveBeenCalled();

    releaseToken('jwt-late');
    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:/));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer jwt-late');
  });

  it('falls back to the raw URL, not a crash, when there is genuinely no token', async () => {
    // The signed-out counterpart: getAuthToken answers null once its wait is exhausted, and this
    // hook must still resolve to SOMETHING the iframe can render rather than throwing inside the
    // effect. The raw URL is the documented fallback; what the fix removes is reaching it while a
    // token was merely still on its way.
    mockGetAuthToken.mockResolvedValue(null);
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401 });
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, true));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toBe(RAW));
  });

  it('does nothing when disabled (edit mode) - no fetch', () => {
    const fetchMock = mockFetchOk();
    renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, false));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does nothing when there are no FileRefs in the data', () => {
    const fetchMock = mockFetchOk();
    renderHook(() => useInterfaceFileUrls({ title: 'hello', count: 3 }, true));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
