/**
 * @vitest-environment jsdom
 */
import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

/**
 * Regression suite for the races introduced when gateway resolution became async.
 *
 * Before runtime resolution, `wsClient.connect()` ran synchronously inside the lifecycle
 * effect, so "we decided to connect" and "we connected" were the same instant and neither
 * race below could exist. Awaiting the runtime config opened a window in which the
 * component can be torn down, or the workspace switched, before connect() ever runs.
 *
 * Both cases are asserted on `wsClient` calls rather than on internal refs: what matters
 * is that no socket is created after teardown and that a client which has never connected
 * is not asked to reconnect.
 */

const connect = vi.fn();
const disconnect = vi.fn();
const reconnect = vi.fn();

vi.mock('@/lib/websocket/ws-client', () => ({
    wsClient: {
        connect: (...a: unknown[]) => connect(...a),
        disconnect: (...a: unknown[]) => disconnect(...a),
        reconnect: (...a: unknown[]) => reconnect(...a),
        refreshToken: () => {},
    },
}));

vi.mock('@/lib/providers/smart-providers', () => ({
    useAuth: () => ({
        isAuthenticated,
        isLoading: false,
        getAccessToken: async () => 'token',
    }),
}));

let isAuthenticated = true;
let currentOrgId: string | null = null;
const useCurrentOrgStore = ((selector: (s: { currentOrgId: string | null }) => unknown) =>
    selector({ currentOrgId })) as unknown as {
        (selector: (s: { currentOrgId: string | null }) => unknown): unknown;
        getState: () => { currentOrgId: string | null };
    };
useCurrentOrgStore.getState = () => ({ currentOrgId });
vi.mock('@/lib/stores/current-org-store', () => ({ useCurrentOrgStore }));

// A config fetch we can hold open, so the teardown or the workspace switch happens while
// resolution is genuinely in flight. The resolved VALUE is settable so one case can prove
// the fetched config actually reaches the resolver.
type Cfg = { gatewayUrl: string | null; gatewayPort: number | null } | null;
let releaseConfig: (value: Cfg) => void;
let configPending: Promise<Cfg>;
vi.mock('../gatewayUrl', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../gatewayUrl')>();
    return { ...actual, fetchGatewayRuntimeConfig: () => configPending };
});

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
    connect.mockClear();
    disconnect.mockClear();
    reconnect.mockClear();
    isAuthenticated = true;
    currentOrgId = null;
    configPending = new Promise<Cfg>((resolve) => { releaseConfig = resolve; });
    vi.spyOn(console, 'log').mockImplementation(() => {});
});

afterEach(() => {
    vi.restoreAllMocks();
});

describe('WebSocketProvider gateway resolution races', () => {
    it('does not connect when the component unmounts while the config is in flight', async () => {
        const { WebSocketProvider } = await import('../ws-provider');
        const { unmount } = render(<WebSocketProvider><div /></WebSocketProvider>);

        // Teardown happens first, resolution lands afterwards.
        unmount();
        releaseConfig(null);
        await flush();

        expect(disconnect).toHaveBeenCalled();
        // Pre-fix this called connect() AFTER disconnect(), resetting intentionalClose and
        // leaking a socket that reconnected forever past app teardown.
        expect(connect).not.toHaveBeenCalled();
    });

    it('connects normally when nothing tears down', async () => {
        // Guards against the fix degenerating into "never connect".
        const { WebSocketProvider } = await import('../ws-provider');
        render(<WebSocketProvider><div /></WebSocketProvider>);

        releaseConfig(null);
        await flush();

        expect(connect).toHaveBeenCalledTimes(1);
        expect(String(connect.mock.calls[0][0])).toMatch(/^wss?:\/\//);
    });

    it('does not reconnect a client that has not connected yet when the workspace switches', async () => {
        const { WebSocketProvider } = await import('../ws-provider');
        const { rerender } = render(<WebSocketProvider><div /></WebSocketProvider>);

        // Workspace switches while the first connect is still resolving.
        currentOrgId = 'org-2';
        rerender(<WebSocketProvider><div /></WebSocketProvider>);

        // Pre-fix startedRef was already true, so this took the org-switch branch and
        // called reconnect() on a client with no gateway URL and no token provider,
        // burning a spurious failed attempt and a backoff.
        expect(reconnect).not.toHaveBeenCalled();

        releaseConfig(null);
        await flush();

        // The pending connect still happens, and picks up the live workspace through
        // activeOrgProvider rather than the value captured when the effect ran.
        expect(connect).toHaveBeenCalledTimes(1);
        const activeOrgProvider = connect.mock.calls[0][2] as () => string | null;
        expect(activeOrgProvider()).toBe('org-2');
    });

    it('DOES reconnect when the workspace switches after the connection landed', () => {
        // The other half of the guard. Without this, connectedRef could be left false and
        // every real workspace switch would be swallowed, silently breaking the guarantee
        // that the session's organizationId follows the active workspace.
        return (async () => {
            const { WebSocketProvider } = await import('../ws-provider');
            const { rerender } = render(<WebSocketProvider><div /></WebSocketProvider>);
            releaseConfig(null);
            await flush();
            expect(connect).toHaveBeenCalledTimes(1);

            currentOrgId = 'org-2';
            rerender(<WebSocketProvider><div /></WebSocketProvider>);
            expect(reconnect).toHaveBeenCalledTimes(1);
        })();
    });

    it('does not connect when the user logs out while the config is in flight', () => {
        // The second teardown path the provider comment names. Only the unmount one was
        // covered, so dropping the startedRef half of the post-await guard went unnoticed:
        // the mutant reconnects a socket after logout and clears intentionalClose.
        return (async () => {
            const { WebSocketProvider } = await import('../ws-provider');
            const { rerender } = render(<WebSocketProvider><div /></WebSocketProvider>);

            isAuthenticated = false;
            rerender(<WebSocketProvider><div /></WebSocketProvider>);
            expect(disconnect).toHaveBeenCalled();

            releaseConfig(null);
            await flush();
            expect(connect).not.toHaveBeenCalled();
        })();
    });

    it('derives the origin from window.location when the config supplies only a port', () => {
        // The zero-configuration path this whole change exists to deliver, asserted THROUGH
        // the provider. Every other case here resolves via gatewayUrl (rank 1), where the
        // location argument is unused, so replacing window.location with a bogus object
        // survived the entire suite. jsdom serves http://localhost by default.
        return (async () => {
            const { WebSocketProvider } = await import('../ws-provider');
            render(<WebSocketProvider><div /></WebSocketProvider>);

            releaseConfig({ gatewayUrl: null, gatewayPort: 9999 });
            await flush();

            expect(connect).toHaveBeenCalledTimes(1);
            expect(connect.mock.calls[0][0]).toBe('ws://localhost:9999');
        })();
    });

    it('falls back to the BAKED url when the config supplies nothing usable', () => {
        // Pins the second argument of resolveGatewayHttpUrl at the provider level: passing
        // null instead of the baked env var was otherwise indistinguishable, because the
        // variable is unset under vitest.
        return (async () => {
            vi.stubEnv('NEXT_PUBLIC_GATEWAY_WS_URL', 'https://baked.example.com');
            const { WebSocketProvider } = await import('../ws-provider');
            render(<WebSocketProvider><div /></WebSocketProvider>);

            releaseConfig(null);
            await flush();

            expect(connect).toHaveBeenCalledTimes(1);
            expect(connect.mock.calls[0][0]).toBe('wss://baked.example.com');
        })();
    });

    it('still connects when effects are double-invoked on the same instance', () => {
        // StrictMode tears the effect down and re-runs it on the SAME component instance, so
        // refs survive: mountedRef stays false from the first cleanup unless the effect body
        // re-arms it, and every later resolution then aborts forever - dead realtime with no
        // error. A plain unmount+render does NOT reproduce this (fresh instance, fresh refs),
        // which is why this case needs StrictMode explicitly.
        return (async () => {
            const { WebSocketProvider } = await import('../ws-provider');
            render(
                <StrictMode>
                    <WebSocketProvider><div /></WebSocketProvider>
                </StrictMode>,
            );

            releaseConfig(null);
            await flush();

            expect(connect).toHaveBeenCalled();
        })();
    });

    it('passes the FETCHED config through to the resolved gateway URL', () => {
        // Without this the provider can ignore the config entirely (resolve with null) and
        // the whole runtime-resolution feature is disabled with every other test green.
        // gatewayUrl is the one rank whose effect is visible in the connect() argument
        // regardless of the test environment's location.
        return (async () => {
            const { WebSocketProvider } = await import('../ws-provider');
            render(<WebSocketProvider><div /></WebSocketProvider>);

            releaseConfig({ gatewayUrl: 'https://gateway.example.com', gatewayPort: 8080 });
            await flush();

            expect(connect).toHaveBeenCalledTimes(1);
            expect(connect.mock.calls[0][0]).toBe('wss://gateway.example.com');
        })();
    });
});
