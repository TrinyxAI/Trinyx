import { afterEach, describe, expect, it, vi } from 'vitest';
import {
    resolveGatewayHttpUrl,
    toWebSocketUrl,
    type GatewayRuntimeConfig,
} from '../gatewayUrl';

/**
 * Regression suite for the baked-localhost gateway bug: the published CE frontend
 * image inlines NEXT_PUBLIC_GATEWAY_WS_URL=http://localhost:8080 into the client
 * bundle, so a CE install opened from any machine other than the Docker host pointed
 * its WebSocket at the VISITOR's own localhost and the realtime layer went dead.
 *
 * Every case below is written against the ADDRESS THE APP WAS OPENED WITH, because
 * that is the variable the old code ignored.
 */

const LOCALHOST = { protocol: 'http:', hostname: 'localhost', host: 'localhost:3000' };
const NAS = { protocol: 'http:', hostname: '192.168.1.50', host: '192.168.1.50:3000' };
const DOMAIN = { protocol: 'https:', hostname: 'lc.example.com', host: 'lc.example.com' };

const config = (over: Partial<GatewayRuntimeConfig> = {}): GatewayRuntimeConfig => ({
    gatewayUrl: null,
    gatewayPort: null,
    ...over,
});

const BAKED = 'http://localhost:8080';

describe('resolveGatewayHttpUrl', () => {
    it('derives the gateway from the address the app was opened with (the NAS bug)', () => {
        // Pre-fix this returned the baked http://localhost:8080, i.e. the browser's own
        // machine, and the socket never connected.
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), BAKED, NAS))
            .toBe('http://192.168.1.50:8080');
    });

    it('keeps the localhost install byte-identical to the old behaviour', () => {
        // The fix must be a no-op for the documented "try it on your own machine" case.
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), BAKED, LOCALHOST))
            .toBe('http://localhost:8080');
    });

    it('carries the scheme over so an https install does not downgrade to ws://', () => {
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), BAKED, DOMAIN))
            .toBe('https://lc.example.com:8080');
    });

    it('honours a non-default published backend port', () => {
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 18080 }), BAKED, NAS))
            .toBe('http://192.168.1.50:18080');
    });

    it('lets an explicit GATEWAY_PUBLIC_URL win over the derived origin', () => {
        // The reverse-proxy / separate-host case: the operator knows better than we do.
        expect(resolveGatewayHttpUrl(
            config({ gatewayUrl: 'https://api.example.com', gatewayPort: 8080 }), BAKED, NAS,
        )).toBe('https://api.example.com');
    });

    it('lets an explicit runtime URL win over a deliberate NON-loopback baked URL', () => {
        // Rank 1 vs rank 2, the precedence pair the loopback split introduced. Without a
        // case combining both, swapping those two ranks passes every other assertion while
        // silently ignoring a runtime override on a from-source build.
        expect(resolveGatewayHttpUrl(
            config({ gatewayUrl: 'https://runtime.example.com', gatewayPort: 8080 }),
            'https://baked.example.com',
            NAS,
        )).toBe('https://runtime.example.com');
    });

    it('never returns an unusable baked URL, at either rank', () => {
        // A baked value with no scheme reaches new WebSocket() as a SyntaxError the ws
        // client swallows into an endless silent reconnect. The runtime input is validated
        // server-side; this is the same guarantee for the build-time one.
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), 'localhost:8080', NAS))
            .toBe('http://192.168.1.50:8080');
        expect(resolveGatewayHttpUrl(null, 'localhost:8080', NAS))
            .toBe('http://192.168.1.50:3000');
        expect(resolveGatewayHttpUrl(null, 'ftp://example.com', NAS))
            .toBe('http://192.168.1.50:3000');
    });

    it('trims a padded runtime URL rather than passing the padding through', () => {
        expect(resolveGatewayHttpUrl(
            config({ gatewayUrl: '  https://api.example.com  ' }), BAKED, NAS,
        )).toBe('https://api.example.com');
    });

    it('ignores a blank or whitespace gatewayUrl instead of producing an empty origin', () => {
        expect(resolveGatewayHttpUrl(config({ gatewayUrl: '   ', gatewayPort: 8080 }), BAKED, NAS))
            .toBe('http://192.168.1.50:8080');
    });

    it('falls back to the baked URL when the runtime config could not be fetched', () => {
        // Cloud path, and the CE path when /api/runtime-config is unreachable.
        expect(resolveGatewayHttpUrl(null, 'https://gateway.example.com', NAS))
            .toBe('https://gateway.example.com');
    });

    // ── Regression: a deliberate build-time URL must outrank the derived origin ──
    // The first version of this module derived from gatewayPort ahead of ANY baked URL,
    // which silently discarded the intent of anyone who had built from source with a real
    // gateway URL. Only a LOOPBACK baked URL may be overridden, because that is the useless
    // default the published image bakes.
    //
    // Scope note, so this is not over-credited: a single-origin reverse-proxy install has
    // no baked URL at all, so the loopback split does nothing for it. On CE the route
    // always supplies a port, so rank 3 still answers before the same-origin fallback;
    // such an install has to set GATEWAY_PUBLIC_URL explicitly.
    it('respects a non-loopback baked URL over the derived origin', () => {
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), 'https://api.example.com', NAS))
            .toBe('https://api.example.com');
    });

    it('still overrides a loopback baked URL, which is the published default', () => {
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), 'http://localhost:8080', NAS))
            .toBe('http://192.168.1.50:8080');
    });

    it.each([
        'http://localhost:8080',
        'http://127.0.0.1:8080',
        'http://127.1.2.3:8080',
        'http://0.0.0.0:8080',
        'http://[::1]:8080',
    ])('treats %s as loopback and overrides it', (baked) => {
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), baked, NAS))
            .toBe('http://192.168.1.50:8080');
    });

    it('keeps a loopback baked URL when there is no runtime config to derive from', () => {
        // Failure path: inventing a new origin during an outage would be worse than
        // reproducing the pre-existing behaviour.
        expect(resolveGatewayHttpUrl(null, 'http://localhost:8080', NAS))
            .toBe('http://localhost:8080');
    });

    it('keeps the baked URL winning on cloud, where gatewayPort is null', () => {
        expect(resolveGatewayHttpUrl(config(), 'https://gateway.example.com', DOMAIN))
            .toBe('https://gateway.example.com');
    });

    it('falls back to the current origin when nothing is configured at all', () => {
        expect(resolveGatewayHttpUrl(null, undefined, NAS)).toBe('http://192.168.1.50:3000');
        expect(resolveGatewayHttpUrl(config(), '', DOMAIN)).toBe('https://lc.example.com');
    });

    // BAKED is loopback, so a rejected port falls through to it (rank 4) rather than to
    // the derived origin - which is what makes these assertions distinguish "the port was
    // rejected" from "the port was used".
    it.each([0, -1, Number.NaN, 70000, 8080.5])('ignores the nonsensical port %s', (port) => {
        expect(resolveGatewayHttpUrl(config({ gatewayPort: port }), BAKED, NAS)).toBe(BAKED);
    });
});

describe('toWebSocketUrl', () => {
    it('maps http to ws and https to wss', () => {
        expect(toWebSocketUrl('http://192.168.1.50:8080')).toBe('ws://192.168.1.50:8080');
        expect(toWebSocketUrl('https://lc.example.com')).toBe('wss://lc.example.com');
    });

    // These two kill a colon-DROPPING mutant (replace('http','ws')), which would turn
    // "httpx://" into "wsx://".
    it('is not fooled by a lookalike scheme prefix', () => {
        expect(toWebSocketUrl('httpx://weird.example.com')).toBe('httpx://weird.example.com');
    });

    it('leaves an already-ws URL untouched', () => {
        expect(toWebSocketUrl('wss://lc.example.com/thing')).toBe('wss://lc.example.com/thing');
    });

    // ANCHORING is a separate property and needs its own case: `replace('http:','ws:')`
    // without the ^ anchor passes both cases above (neither contains "http:" after the
    // scheme), and only shows up when "http:" appears LATER in the URL.
    it('is anchored: an http: URL inside a query string is not rewritten', () => {
        expect(toWebSocketUrl('wss://lc.example.com/cb?next=http://elsewhere.example'))
            .toBe('wss://lc.example.com/cb?next=http://elsewhere.example');
    });

    it('classifies a near-loopback hostname as remote, not loopback', () => {
        // The 127. test is a prefix match on a HOST, so it must be anchored: these are
        // ordinary remote hosts and their baked URL must win at rank 2.
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), 'http://127.0.0.1.evil.example', NAS))
            .toBe('http://127.0.0.1.evil.example');
        expect(resolveGatewayHttpUrl(config({ gatewayPort: 8080 }), 'http://localhost.example.com', NAS))
            .toBe('http://localhost.example.com');
    });
});

/**
 * IS_CE is a module-level const frozen at import, so each case re-imports the module
 * under a mocked edition (same pattern as format-cost.test.ts). This matters here
 * because runtime resolution is a SELF-HOSTED concern: cloud must not gain a network
 * round-trip in front of its WebSocket connection.
 */
describe('fetchGatewayRuntimeConfig', () => {
    afterEach(() => {
        vi.resetModules();
        vi.doUnmock('@/lib/edition');
        vi.unstubAllGlobals();
    });

    async function load(isCe: boolean) {
        vi.resetModules();
        vi.doMock('@/lib/edition', () => ({ IS_CE: isCe, IS_CLOUD: !isCe, EDITION: isCe ? 'ce' : 'cloud' }));
        const mod = await import('../gatewayUrl');
        mod.resetGatewayRuntimeConfigCache();
        return mod;
    }

    it('CE: reads the config from the runtime-config endpoint, uncached', async () => {
        // Asserting the URL and options, not just the parsed result: without this a
        // mutant pointing the fetch at the wrong path, or dropping `no-store` so a proxy
        // serves a stale origin, survives every other case in this file.
        const spy = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ gatewayUrl: null, gatewayPort: 8080 }),
        });
        vi.stubGlobal('fetch', spy);
        const { fetchGatewayRuntimeConfig, RUNTIME_CONFIG_PATH } = await load(true);
        await expect(fetchGatewayRuntimeConfig()).resolves.toEqual({ gatewayUrl: null, gatewayPort: 8080 });
        expect(RUNTIME_CONFIG_PATH).toBe('/api/runtime-config');
        expect(spy).toHaveBeenCalledTimes(1);
        const [url, opts] = spy.mock.calls[0];
        expect(url).toBe('/api/runtime-config');
        expect(opts.cache).toBe('no-store');
        // The socket waits on this request, so it must be time-bounded: a hang would leave
        // realtime dead for the page load with no retry.
        expect(opts.signal).toBeInstanceOf(AbortSignal);
    });

    it('cloud: never issues the request at all', async () => {
        // The cloud image bakes the real gateway URL, so there is nothing to resolve.
        // Asserting on the SPY, not just the result: returning null while still hitting
        // the network would be an invisible regression on a path outside CE's scope.
        const spy = vi.fn();
        vi.stubGlobal('fetch', spy);
        const { fetchGatewayRuntimeConfig } = await load(false);
        await expect(fetchGatewayRuntimeConfig()).resolves.toBeNull();
        expect(spy).not.toHaveBeenCalled();
    });

    it('CE: fetches once per page load even across reconnects', async () => {
        const spy = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ gatewayPort: 8080 }) });
        vi.stubGlobal('fetch', spy);
        const { fetchGatewayRuntimeConfig } = await load(true);
        await fetchGatewayRuntimeConfig();
        await fetchGatewayRuntimeConfig();
        expect(spy).toHaveBeenCalledTimes(1);
    });

    it('CE: resolves to null instead of rejecting when the request fails', async () => {
        // A failed config fetch must degrade to the baked URL, never leave the socket
        // unconnected or surface an unhandled rejection.
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
        const { fetchGatewayRuntimeConfig } = await load(true);
        await expect(fetchGatewayRuntimeConfig()).resolves.toBeNull();
    });

    it('CE: resolves to null on a non-2xx response', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({}) }));
        const { fetchGatewayRuntimeConfig } = await load(true);
        await expect(fetchGatewayRuntimeConfig()).resolves.toBeNull();
    });

    it('CE: drops malformed field types rather than trusting the payload', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ gatewayUrl: 42, gatewayPort: '8080' }),
        }));
        const { fetchGatewayRuntimeConfig } = await load(true);
        await expect(fetchGatewayRuntimeConfig()).resolves.toEqual({ gatewayUrl: null, gatewayPort: null });
    });

    it('CE: drops a non-integer port from the payload', async () => {
        // 8080.5 would reach the browser as "http://host:8080.5", which WebSocket rejects
        // with a SyntaxError that the ws client swallows into a silent reconnect loop.
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ gatewayUrl: null, gatewayPort: 8080.5 }),
        }));
        const { fetchGatewayRuntimeConfig } = await load(true);
        await expect(fetchGatewayRuntimeConfig()).resolves.toEqual({ gatewayUrl: null, gatewayPort: null });
    });

    // ── Regression: a transient failure must not be cached ──────────────────────
    // Memoising the null pinned the tab to the baked URL for its whole lifetime after one
    // blip. On the published CE image that baked URL is http://localhost:8080, so caching
    // the failure silently reinstated the exact bug this module exists to fix, recoverable
    // only by reloading the page.
    it('CE: retries after a failure instead of caching it', async () => {
        const spy = vi.fn()
            .mockRejectedValueOnce(new Error('container still starting'))
            .mockResolvedValueOnce({ ok: true, json: async () => ({ gatewayUrl: null, gatewayPort: 8080 }) });
        vi.stubGlobal('fetch', spy);
        const { fetchGatewayRuntimeConfig } = await load(true);
        await expect(fetchGatewayRuntimeConfig()).resolves.toBeNull();
        await expect(fetchGatewayRuntimeConfig()).resolves.toEqual({ gatewayUrl: null, gatewayPort: 8080 });
        expect(spy).toHaveBeenCalledTimes(2);
    });

    it('CE: retries after a non-2xx response too', async () => {
        const spy = vi.fn()
            .mockResolvedValueOnce({ ok: false, json: async () => ({}) })
            .mockResolvedValueOnce({ ok: true, json: async () => ({ gatewayPort: 8080 }) });
        vi.stubGlobal('fetch', spy);
        const { fetchGatewayRuntimeConfig } = await load(true);
        await expect(fetchGatewayRuntimeConfig()).resolves.toBeNull();
        await expect(fetchGatewayRuntimeConfig()).resolves.toEqual({ gatewayUrl: null, gatewayPort: 8080 });
        expect(spy).toHaveBeenCalledTimes(2);
    });
});
