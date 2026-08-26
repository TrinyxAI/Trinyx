import { apiClient } from '@/lib/api';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';

/**
 * Make authenticated fetch calls using apiClient's resolved access token.
 * This is a thin wrapper for cases where we need the raw Response object.
 *
 * For most API calls, prefer using apiClient methods directly (get, post, put, delete).
 * Use this helper when you need access to response headers or status codes.
 *
 * <p>The token comes from `apiClient.getAuthToken()`. There is deliberately NO module-local token
 * getter here. AGENTS.md forbids custom token providers outside smart-providers.tsx, and the one
 * this module used to export (`setAuthTokenGetter`) took precedence over apiClient's provider
 * while outliving the component that installed it, since nothing ever cleared it on unmount.
 *
 * <p>The active-workspace header is spread BEFORE the caller's headers, matching
 * apiClient's precedence (`executeFetch` lets a caller-supplied
 * `X-Active-Organization-ID` win). Without it these raw fetches resolved under the
 * user's DEFAULT workspace: the Next.js proxy forwards only what the client sent and
 * applies no server-side default.
 */
export async function authenticatedFetch(url: string, options: RequestInit = {}): Promise<Response> {
  // getAuthToken rather than the provider: it waits for the async auth bootstrap, so a table
  // rendering during it fetches authenticated instead of anonymously. No try/catch: it never
  // rejects (a throwing provider is caught inside and becomes null), and the one that used to be
  // here guarded a raw provider call that genuinely could throw.
  const token = await apiClient.getAuthToken();

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
