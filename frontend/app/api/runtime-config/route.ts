import { NextResponse } from 'next/server';
import { IS_CE } from '@/lib/edition';

/**
 * Browser-facing runtime configuration.
 *
 * The only values here are ones that CANNOT be baked at build time because they depend on
 * where the install is actually reachable from. Everything else keeps using the build-time
 * NEXT_PUBLIC_* inlining.
 *
 * This route exists because the published CE frontend image inlines the gateway URL as
 * `http://localhost:8080` into the client bundle, which in a browser means the VISITOR's
 * machine: opening a CE install from any host other than the Docker host lost the entire
 * realtime layer. Reading the value here, per request, lets one prebuilt image serve
 * localhost, a LAN address and a public domain without rebuilding from source.
 *
 * No authentication: it returns only deployment topology the operator chose to publish, no
 * secrets, and the client needs it before it has a session.
 */

// Read per request, never prerendered or cached into the build output - the whole point is
// that the value can change between `docker build` and `docker run`.
export const dynamic = 'force-dynamic';

const DEFAULT_GATEWAY_PORT = 8080;
const MAX_PORT = 65535;

/**
 * Rejects what the browser cannot use, instead of forwarding it and letting it fail
 * opaquely. `new WebSocket("example.com:8080/ws")` throws a synchronous SyntaxError that
 * the ws client swallows into an endless silent reconnect, so a missing scheme has to be
 * caught here. A trailing slash is normalised because the client appends "/ws" and
 * "https://host//ws" 404s the handshake. Both are the exact typos an operator following
 * the compose comment will make.
 */
function normalizeGatewayUrl(raw: string): string | null {
    const value = raw.trim();
    if (!value) return null;
    let parsed: URL;
    try {
        parsed = new URL(value);
    } catch {
        console.warn(`[runtime-config] ignoring GATEWAY_PUBLIC_URL="${value}": not an absolute URL`);
        return null;
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        console.warn(`[runtime-config] ignoring GATEWAY_PUBLIC_URL="${value}": scheme must be http or https`);
        return null;
    }
    return value.replace(/\/+$/, '');
}

/**
 * Strict on purpose. `Number.parseInt` alone accepts "8080abc" as 8080 and lets 70000
 * through, and a port above 65535 reaches the browser as an unusable origin whose failure
 * is the same silent reconnect loop as above.
 */
function resolveGatewayPort(raw: string): number {
    const value = raw.trim();
    if (!value) return DEFAULT_GATEWAY_PORT;
    if (!/^\d+$/.test(value)) {
        console.warn(`[runtime-config] ignoring BACKEND_PORT="${value}": not a number, using ${DEFAULT_GATEWAY_PORT}`);
        return DEFAULT_GATEWAY_PORT;
    }
    const port = Number.parseInt(value, 10);
    if (port < 1 || port > MAX_PORT) {
        console.warn(`[runtime-config] ignoring BACKEND_PORT="${value}": outside 1-${MAX_PORT}, using ${DEFAULT_GATEWAY_PORT}`);
        return DEFAULT_GATEWAY_PORT;
    }
    return port;
}

export function GET() {
    // Both fields are CE-only, for one reason: cloud resolves the gateway from its
    // build-time URL and never reads this route, so emitting anything there would be an
    // unauthenticated endpoint echoing configuration nobody consumes.
    if (!IS_CE) {
        return NextResponse.json(
            { gatewayUrl: null, gatewayPort: null },
            { headers: { 'Cache-Control': 'no-store' } },
        );
    }

    return NextResponse.json(
        {
            gatewayUrl: normalizeGatewayUrl(process.env.GATEWAY_PUBLIC_URL ?? ''),
            gatewayPort: resolveGatewayPort(process.env.BACKEND_PORT ?? ''),
        },
        { headers: { 'Cache-Control': 'no-store' } },
    );
}
