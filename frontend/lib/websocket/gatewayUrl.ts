/**
 * Resolves the gateway origin the BROWSER uses for the WebSocket connection.
 *
 * Why this exists: `NEXT_PUBLIC_GATEWAY_WS_URL` is inlined into the client bundle by
 * `next build`, and the published CE image bakes it as `http://localhost:8080`. In a
 * browser, `localhost` is the VISITOR's machine, so every CE install opened from
 * anywhere other than the Docker host itself connected to nothing and lost the whole
 * realtime layer (agent streaming, run updates, task boards). HTTP was unaffected: it
 * goes through the same-origin `/api/proxy` route, resolved server-side.
 *
 * The fix is to also consult a RUNTIME value from `/api/runtime-config`, so one prebuilt
 * image serves localhost, a LAN address and a public domain alike, which is what makes
 * app-store / NAS / one-click installs work without rebuilding the frontend from source.
 *
 * `resolveGatewayHttpUrl` and `toWebSocketUrl` are pure (no fetch, no window, no module
 * state) so the precedence rules are unit-testable, which is where the regression risk
 * actually lives. `fetchGatewayRuntimeConfig` below is the impure part and does hold a
 * module-level memo.
 *
 * Scope: runtime resolution is a SELF-HOSTED (CE) concern. Cloud never fetches the runtime
 * config at all (the IS_CE gate below), so its behaviour is exactly what it was. Note that
 * cloud's real build bakes an EMPTY gateway URL and relies on the same-origin fallback, it
 * does not bake an absolute one; the gating argument holds either way, but do not assume
 * the baked value is populated there.
 */

import { IS_CE } from '@/lib/edition';

export interface GatewayRuntimeConfig {
    /**
     * Absolute browser-facing gateway origin, from the GATEWAY_PUBLIC_URL env var.
     * Set this when the gateway is not on `<the address you opened the app with>`:
     * behind a reverse proxy, on a separate host, or on a public domain with TLS.
     */
    gatewayUrl: string | null;
    /**
     * Port the gateway is published on, used to derive the origin from the address
     * the app was actually opened with. CE only (null on cloud, where the build-time
     * value is the real gateway URL and must win).
     */
    gatewayPort: number | null;
}

/** The subset of `window.location` this module needs, so tests need no DOM. */
export interface LocationLike {
    protocol: string;
    hostname: string;
    host: string;
}

const nonEmpty = (value: string | null | undefined): string | null => {
    const trimmed = (value ?? '').trim();
    return trimmed.length > 0 ? trimmed : null;
};

/**
 * Classifies the build-time URL, which is the discriminator that keeps this fix from
 * breaking existing installs:
 *
 *   'remote'   names a real host, so it was a deliberate choice (a from-source build with
 *              NEXT_PUBLIC_GATEWAY_WS_URL=https://api.example.com) and must be honoured.
 *   'loopback' only reachable from the machine running the browser. This is the PUBLISHED
 *              image's http://localhost:8080 default, useless to a remote visitor, so it
 *              may be overridden by a derived origin.
 *   'unusable' absent, or not an absolute http(s) URL. Never returned to the caller:
 *              `new WebSocket('localhost:8080/ws')` throws a SyntaxError that the ws
 *              client swallows into an endless silent reconnect, which is the same trap
 *              the runtime value is validated against server-side. The runtime input got
 *              that check; without this, the baked input escaped it.
 */
type BakedKind = 'remote' | 'loopback' | 'unusable';

function classifyBakedUrl(url: string | null): BakedKind {
    if (!url) return 'unusable';
    let parsed: URL;
    try {
        parsed = new URL(url);
    } catch {
        return 'unusable';
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return 'unusable';
    // URL.hostname returns IPv6 in bracketed form, so '[::1]' is the only reachable
    // spelling of the IPv6 loopback here. The IPv4 loopback test matches the WHOLE address,
    // not a `127.` prefix: a prefix test classifies the ordinary remote host
    // 127.0.0.1.evil.example as loopback and would override an operator's deliberate
    // build-time URL for it.
    const host = parsed.hostname.toLowerCase();
    const loopback = host === 'localhost'
        || host === '0.0.0.0'
        || host === '[::1]'
        || /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(host);
    return loopback ? 'loopback' : 'remote';
}

/**
 * Precedence, and the reason for each rank:
 *
 *   1. `config.gatewayUrl`        the operator stated exactly where the gateway is at
 *                                 runtime. Nothing outranks an explicit answer.
 *   2. a NON-loopback `bakedUrl`  a build-time URL naming a real host is also explicit:
 *                                 it is what a from-source build and every cloud build
 *                                 set, and overriding it would silently discard intent.
 *   3. `config.gatewayPort`       derive from the address the app was opened with. This
 *                                 is the zero-configuration path that fixes LAN and
 *                                 domain installs, and it is why this module exists.
 *   4. a loopback `bakedUrl`      last resort when the runtime config is unreachable.
 *                                 Preserves the pre-existing behaviour rather than
 *                                 inventing a new one during a failure.
 *   5. the current origin         final fallback. Reached when the runtime config is
 *                                 unavailable (or supplies no usable port) and the baked
 *                                 value is absent or unusable. Note that on CE the route
 *                                 always supplies a port, so a successful fetch means
 *                                 rank 3 answers first.
 *
 * Ranks 2 and 4 are the same input split by `classifyBakedUrl`, which is what lets the
 * published image's useless `localhost` default be overridden while a deliberate
 * build-time URL is not. An 'unusable' baked value is never returned at either rank.
 */
export function resolveGatewayHttpUrl(
    config: GatewayRuntimeConfig | null,
    bakedUrl: string | null | undefined,
    location: LocationLike,
): string {
    const explicit = nonEmpty(config?.gatewayUrl);
    if (explicit) return explicit;

    const baked = nonEmpty(bakedUrl);
    const bakedKind = classifyBakedUrl(baked);
    if (bakedKind === 'remote') return baked as string;

    const port = config?.gatewayPort;
    if (typeof port === 'number' && Number.isInteger(port) && port > 0 && port <= 65535) {
        return `${location.protocol}//${location.hostname}:${port}`;
    }

    if (bakedKind === 'loopback') return baked as string;

    return `${location.protocol}//${location.host}`;
}

/** http(s) origin -> ws(s) origin. Anything else is returned untouched. */
export function toWebSocketUrl(httpUrl: string): string {
    return httpUrl.replace(/^https:/, 'wss:').replace(/^http:/, 'ws:');
}

/** Path of the runtime config endpoint. Exported so tests assert the real one. */
export const RUNTIME_CONFIG_PATH = '/api/runtime-config';

/**
 * Ceiling on how long the WebSocket waits for the runtime config. Same-origin and served by
 * the process rendering the page, so this is generous; it exists to bound a hang, not to
 * race a slow network.
 */
export const RUNTIME_CONFIG_TIMEOUT_MS = 3000;

let cached: Promise<GatewayRuntimeConfig | null> | null = null;

/**
 * Fetches the runtime config once per page load. NEVER throws and never rejects: a
 * failure degrades to `resolveGatewayHttpUrl`'s later ranks rather than leaving the
 * socket unconnected.
 *
 * Only a SUCCESSFUL result is memoised. Caching a failure would pin the tab to the
 * baked URL for its whole lifetime after one transient blip (container still starting,
 * a proxy answering HTML so `json()` rejects), and on the published image that baked URL
 * is `http://localhost:8080`, i.e. it would silently reinstate the exact bug this module
 * exists to fix, recoverable only by a page reload.
 *
 * CE only. On cloud the build-time URL already is the real gateway, so this returns null
 * WITHOUT issuing a request: no extra round-trip is added to a non-CE path.
 */
export function fetchGatewayRuntimeConfig(): Promise<GatewayRuntimeConfig | null> {
    if (!IS_CE) return Promise.resolve(null);
    if (cached) return cached;
    // The socket now waits on this request, so a HANG (not a failure) would leave the
    // realtime layer dead for the whole page load with no retry: the effect that awaits it
    // has already claimed its slot and will not re-run. A rejection is handled, an
    // unbounded wait is not, hence the timeout. AbortSignal.timeout is guarded because the
    // resolver must keep working on runtimes that lack it rather than throw here.
    const signal = typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function'
        ? AbortSignal.timeout(RUNTIME_CONFIG_TIMEOUT_MS)
        : undefined;
    const attempt = fetch(RUNTIME_CONFIG_PATH, { cache: 'no-store', signal })
        .then((response) => (response.ok ? response.json() : null))
        .then((body): GatewayRuntimeConfig | null => {
            if (!body || typeof body !== 'object') return null;
            const raw = body as Record<string, unknown>;
            return {
                gatewayUrl: typeof raw.gatewayUrl === 'string' ? raw.gatewayUrl : null,
                // Integrality matters: a junk 8080.5 would otherwise survive and produce
                // "http://host:8080.5", which WebSocket rejects with a SyntaxError.
                gatewayPort: typeof raw.gatewayPort === 'number' && Number.isInteger(raw.gatewayPort)
                    ? raw.gatewayPort
                    : null,
            };
        })
        .catch(() => null)
        .then((result) => {
            // Forget the failure so the next caller retries; keep the success memoised.
            if (result === null) cached = null;
            return result;
        });
    cached = attempt;
    return attempt;
}

/** Test seam: drops the memoised config so each case starts clean. */
export function resetGatewayRuntimeConfigCache(): void {
    cached = null;
}
