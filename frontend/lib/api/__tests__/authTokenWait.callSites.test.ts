import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * The call sites whose BEHAVIOUR changed when they moved from `apiClient.getTokenProvider()` to
 * `apiClient.getAuthToken()`, as opposed to the ones that merely gained the wait.
 *
 * <p>Each of these used to give up the moment it saw no provider, which during the async auth
 * bootstrap in `smart-providers.tsx` meant giving up on a signed-in user: an upload refused, a
 * conversation list rendered empty. Waiting is the fix; these pin that the wait is actually what
 * they do now, and that the genuinely-signed-out path still ends rather than hanging.
 *
 * <p>The prod symptom that started this was the same defect one layer over, on file bytes:
 * 98 x 401 on `GET /api/files/by-id/<id>/raw` in the 7 days to 2026-08-25, every one of them
 * sent with no `Authorization` header at all.
 */

const { getAuthToken } = vi.hoisted(() => ({ getAuthToken: vi.fn() }));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getAuthToken },
  ApiError: class ApiError extends Error {},
}));
vi.mock('../api-client', () => ({
  apiClient: { getAuthToken },
  ApiError: class ApiError extends Error {},
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgIdForRequest: () => 'org-7',
  getActiveOrgHeaderForRequest: () => ({ 'X-Active-Organization-ID': 'org-7' }),
}));

import { attachmentApi } from '../attachmentApi';
import { ChatApiService } from '../services/chat-api.service';

function okJson(body: unknown) {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

beforeEach(() => {
  vi.resetAllMocks();
  getAuthToken.mockResolvedValue('jwt-abc');
});

afterEach(() => vi.restoreAllMocks());

describe('attachmentApi.uploadAttachment', () => {
  it('waits for the token instead of refusing an upload started during the auth bootstrap', async () => {
    // Pre-fix this read getTokenProvider() and threw "No token provider" at a signed-in user
    // whose file was picked a few hundred ms too early.
    let release: (t: string) => void = () => {};
    getAuthToken.mockReturnValue(new Promise<string>((resolve) => { release = resolve; }));
    const fetchMock = vi.fn().mockResolvedValue(
      okJson({ storageId: 's-1', type: 'image', fileName: 'a.png', mimeType: 'image/png', sizeBytes: 3 }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const pending = attachmentApi.uploadAttachment(new File(['x'], 'a.png', { type: 'image/png' }));
    await Promise.resolve();
    expect(fetchMock).not.toHaveBeenCalled();

    release('jwt-late');
    await expect(pending).resolves.toMatchObject({ storageId: 's-1' });
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer jwt-late');
  });

  it('still refuses, with an actionable message, when there is genuinely no session', async () => {
    getAuthToken.mockResolvedValue(null);
    vi.stubGlobal('fetch', vi.fn());

    await expect(attachmentApi.uploadAttachment(new File(['x'], 'a.png', { type: 'image/png' })))
      .rejects.toThrow('Authentication required');
  });
});

describe('ChatApiService.stopChatStream', () => {
  it('waits for the token so pressing stop during the bootstrap reaches the backend', async () => {
    let release: (t: string) => void = () => {};
    getAuthToken.mockReturnValue(new Promise<string>((resolve) => { release = resolve; }));
    const fetchMock = vi.fn().mockResolvedValue(okJson({ stopped: true }));
    vi.stubGlobal('fetch', fetchMock);

    const pending = new ChatApiService().stopChatStream('conv-1');
    await Promise.resolve();
    expect(fetchMock).not.toHaveBeenCalled();

    release('jwt-late');
    await pending;
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer jwt-late');
  });

  it('reports the missing session rather than posting an unauthenticated stop', async () => {
    getAuthToken.mockResolvedValue(null);
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(new ChatApiService().stopChatStream('conv-1')).rejects.toThrow('No access token available');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
