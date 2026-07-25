import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Tests for the runtime-config endpoint.
 *
 * This route is the only thing standing between an operator's env var and
 * `new WebSocket(...)`. Anything it forwards unvalidated fails in the browser as a
 * synchronous SyntaxError that the ws client swallows into an endless silent reconnect,
 * so "garbage in, garbage out" here costs the user a dead realtime layer with no error
 * message. Every case below is a value an operator following the compose comment could
 * plausibly type.
 *
 * IS_CE is frozen at import, so each case re-imports the route under a mocked edition
 * (same pattern as lib/__tests__/format-cost.test.ts).
 */

const ORIGINAL_ENV = { ...process.env };

async function load(isCe: boolean) {
    vi.resetModules();
    vi.doMock('@/lib/edition', () => ({ IS_CE: isCe, IS_CLOUD: !isCe, EDITION: isCe ? 'ce' : 'cloud' }));
    return import('../route');
}

async function body(isCe: boolean) {
    const { GET } = await load(isCe);
    return GET().json();
}

beforeEach(() => {
    delete process.env.GATEWAY_PUBLIC_URL;
    delete process.env.BACKEND_PORT;
    vi.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
    vi.resetModules();
    vi.doUnmock('@/lib/edition');
    vi.restoreAllMocks();
});

describe('GET /api/runtime-config', () => {
    it('defaults to port 8080 with no explicit gateway URL', async () => {
        await expect(body(true)).resolves.toEqual({ gatewayUrl: null, gatewayPort: 8080 });
    });

    it('serves BACKEND_PORT so a non-default published port needs no rebuild', async () => {
        process.env.BACKEND_PORT = '18080';
        await expect(body(true)).resolves.toEqual({ gatewayUrl: null, gatewayPort: 18080 });
    });

    it('serves GATEWAY_PUBLIC_URL for the reverse-proxy case', async () => {
        process.env.GATEWAY_PUBLIC_URL = 'https://livecontext.example.com';
        process.env.BACKEND_PORT = '18080';
        await expect(body(true)).resolves.toEqual({
            gatewayUrl: 'https://livecontext.example.com',
            gatewayPort: 18080,
        });
    });

    it('strips a trailing slash, which would otherwise produce a "//ws" handshake', async () => {
        process.env.GATEWAY_PUBLIC_URL = 'https://livecontext.example.com/';
        await expect(body(true)).resolves.toMatchObject({ gatewayUrl: 'https://livecontext.example.com' });
    });

    it('accepts a plain http URL, which is what the docs tell LAN operators to use', async () => {
        // Every other accepted case here is https, so narrowing the scheme check to
        // https-only would silently reject the documented LAN value with the suite green.
        process.env.GATEWAY_PUBLIC_URL = 'http://192.168.1.50:8080';
        await expect(body(true)).resolves.toMatchObject({ gatewayUrl: 'http://192.168.1.50:8080' });
    });

    it.each([
        ['example.com:8080', 'no scheme, the most likely typo'],
        ['ws://example.com', 'a ws scheme where an http origin is expected'],
        ['ftp://example.com', 'an unusable scheme'],
        ['   ', 'whitespace only'],
    ])('rejects GATEWAY_PUBLIC_URL=%s (%s)', async (value) => {
        process.env.GATEWAY_PUBLIC_URL = value;
        // Rejected, not forwarded: falling back to the derived origin keeps the install
        // working, whereas forwarding it kills the socket with no diagnostic.
        await expect(body(true)).resolves.toMatchObject({ gatewayUrl: null });
    });

    it.each([
        ['0', 'not a usable port'],
        ['-1', 'negative'],
        ['70000', 'above the 16-bit range, unusable by WebSocket'],
        ['not-a-port', 'not numeric'],
    ])('falls back to 8080 for BACKEND_PORT=%s (%s)', async (value) => {
        process.env.BACKEND_PORT = value;
        await expect(body(true)).resolves.toMatchObject({ gatewayPort: 8080 });
    });

    it.each(['8080abc', '80 80', ' 8080x'])('rejects the numeric-prefixed BACKEND_PORT=%s', async (value) => {
        // Deliberately NOT asserting "falls back to 8080": that is what a de-anchored
        // /\d+/ mutant also produces for "8080abc", so the assertion could not fail for the
        // reason it names. Anchored, the digits must be the WHOLE value; these inputs
        // therefore fall back, and a value whose leading digits differ from the default
        // proves the difference.
        process.env.BACKEND_PORT = value;
        await expect(body(true)).resolves.toMatchObject({ gatewayPort: 8080 });
    });

    it('rejects a numeric-prefixed port whose digits are NOT the default', async () => {
        // 1234abc: an anchored guard falls back to 8080, a de-anchored one yields 1234.
        process.env.BACKEND_PORT = '1234abc';
        await expect(body(true)).resolves.toMatchObject({ gatewayPort: 8080 });
    });

    it('accepts the range boundaries', async () => {
        process.env.BACKEND_PORT = '65535';
        await expect(body(true)).resolves.toMatchObject({ gatewayPort: 65535 });
        process.env.BACKEND_PORT = '1';
        await expect(body(true)).resolves.toMatchObject({ gatewayPort: 1 });
    });

    it('returns nulls on cloud, where the build-time gateway URL is the real one', async () => {
        // Not merely "gatewayPort is null": cloud must not publish an unauthenticated
        // endpoint echoing configuration that no client reads.
        process.env.GATEWAY_PUBLIC_URL = 'https://livecontext.example.com';
        process.env.BACKEND_PORT = '18080';
        await expect(body(false)).resolves.toEqual({ gatewayUrl: null, gatewayPort: null });
    });

    it('is served uncached, so a proxy cannot pin a stale origin', async () => {
        const { GET } = await load(true);
        expect(GET().headers.get('Cache-Control')).toBe('no-store');
    });

    it('opts out of static rendering, or the answer would be frozen at build time', async () => {
        // The whole point is that the value can change between `docker build` and
        // `docker run`. A prerendered route would silently defeat the feature.
        const mod = await load(true);
        expect(mod.dynamic).toBe('force-dynamic');
    });
});
