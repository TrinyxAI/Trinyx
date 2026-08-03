import { apiClient } from '@/lib/api';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';

/**
 * Make authenticated fetch calls using apiClient's token provider.
 * This is a thin wrapper for cases where we need the raw Response object.
 *
 * For most API calls, prefer using apiClient methods directly (get, post, put, delete).
 * Use this helper when you need access to response headers or status codes.
 *
 * <p>There is deliberately NO module-local token getter here. AGENTS.md forbids custom
 * token providers outside smart-providers.tsx, and the one this module used to export
 * (`setAuthTokenGetter`) took precedence over apiClient's provider while outliving the
 * component that installed it, since nothing ever cleared it on unmount.
 *
 * <p>The active-workspace header is spread BEFORE the caller's headers, matching
 * apiClient's precedence (`executeFetch` lets a caller-supplied
 * `X-Active-Organization-ID` win). Without it these raw fetches resolved under the
 * user's DEFAULT workspace: the Next.js proxy forwards only what the client sent and
 * applies no server-side default.
 */
export async function authenticatedFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const tokenProvider = apiClient.getTokenProvider();
  let token: string | null = null;

  if (tokenProvider) {
    try {
      token = await tokenProvider();
    } catch (e) {
      console.warn('[authenticatedFetch] Failed to get token:', e);
    }
  }

  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    ...getActiveOrgHeaderForRequest(),
    ...(options.headers || {}),
  };

  if (token) {
    (headers as Record<string, string>)['Authorization'] = `Bearer ${token}`;
  }

  return fetch(url, {
    ...options,
    headers,
  });
}
