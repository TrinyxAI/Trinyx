'use client';

import { useEffect, useRef } from 'react';
import { useAuth } from '@/lib/providers/smart-providers';
import { wsClient } from './ws-client';
import { fetchGatewayRuntimeConfig, resolveGatewayHttpUrl, toWebSocketUrl } from './gatewayUrl';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

/**
 * WebSocket provider - owns the single shared gateway connection for the whole
 * app lifetime. Mounted ONCE near the root (smart-providers), so it must NOT churn
 * the socket on navigation or re-render:
 *  - connects once when auth is ready,
 *  - re-handshakes ONLY when the active workspace changes (new `?activeOrg`),
 *  - disconnects only on logout or real unmount.
 *
 * The token getter is read through a ref so its (unstable) identity never enters
 * the effect deps. Previously `getAccessToken` was a dependency: every parent
 * re-render gave it a new identity → the effect cleanup ran `wsClient.disconnect()`
 * and reconnected, churning the socket. That dropped real-time events (the client
 * latched off after the churn) and burst the gateway per-user rate limit.
 *
 * PR25 R1 - reconnect on `currentOrgId` change so the session's `organizationId`
 * reflects the active workspace (else ChannelAuthorizer denies
 * `org:<currentOrgId>:notifications` after a mid-session workspace switch).
 */
export function WebSocketProvider({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading, getAccessToken } = useAuth();
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);

  // Always-latest token getter, WITHOUT making it an effect dependency. Updated in
  // an effect (not during render) so the connection lifecycle effect below never
  // re-runs just because `getAccessToken`'s identity changed.
  const getAccessTokenRef = useRef(getAccessToken);
  useEffect(() => { getAccessTokenRef.current = getAccessToken; }, [getAccessToken]);

  const startedRef = useRef(false);
  const lastOrgRef = useRef<string | null | undefined>(undefined);
  // Resolving the gateway origin is async, so "we intend to connect" (startedRef) and
  // "wsClient.connect has actually run" (connectedRef) are no longer the same instant. The
  // workspace-switch branch needs the second one: reconnect() on a client that has never
  // connected throws (its token provider is still null, before the URL is even used) and
  // burns a spurious failed attempt plus a backoff.
  const connectedRef = useRef(false);
  // Cleared by the unmount-only effect's cleanup and re-armed by its body. When teardown
  // happens first, the in-flight resolution sees it and abandons; when the resolution wins
  // the race instead, it connects and the cleanup then disconnects normally. Both orders
  // are correct, so this is a guard, not an ordering assumption.
  const mountedRef = useRef(true);

  // Connection lifecycle: connect once · reconnect on workspace switch · disconnect on logout.
  useEffect(() => {
    if (isLoading) return;

    if (!isAuthenticated) {
      // Logged out - tear down and allow a clean reconnect on next login.
      if (startedRef.current) {
        wsClient.disconnect();
        startedRef.current = false;
        connectedRef.current = false;
        lastOrgRef.current = undefined;
      }
      return;
    }

    if (!startedRef.current) {
      // Stable providers - read live values; never re-created per render.
      const tokenProvider = (): Promise<string> => getAccessTokenRef.current();
      const activeOrgProvider = (): string | null => useCurrentOrgStore.getState().currentOrgId;

      // Claim the slot BEFORE awaiting: a re-render during the config fetch must not
      // start a second connection.
      startedRef.current = true;
      lastOrgRef.current = currentOrgId;

      // The gateway origin is resolved at runtime rather than from the build-time
      // NEXT_PUBLIC_GATEWAY_WS_URL alone, because that value is inlined into the
      // client bundle: the published CE image bakes `http://localhost:8080`, which in
      // a browser is the VISITOR's machine, so every install opened from anywhere but
      // the Docker host silently lost the socket. fetchGatewayRuntimeConfig never
      // rejects - on failure the resolver falls back to its later ranks.
      //
      // Because this awaits, connect() no longer runs synchronously inside the effect.
      // Both teardown paths must therefore be re-checked after the await: logout resets
      // startedRef, and real unmount (the effect at the bottom of this component) calls
      // wsClient.disconnect() without touching it. Connecting after either would set
      // intentionalClose=false again and leak a socket that reconnects forever.
      void (async () => {
        const config = await fetchGatewayRuntimeConfig();
        if (!mountedRef.current || !startedRef.current) return;

        const gatewayUrl = toWebSocketUrl(
          resolveGatewayHttpUrl(config, process.env.NEXT_PUBLIC_GATEWAY_WS_URL, window.location),
        );
        // Read the live org rather than the value captured when this effect ran: the
        // workspace may have switched while the config was in flight, and
        // activeOrgProvider is about to send the live one.
        const activeOrg = useCurrentOrgStore.getState().currentOrgId;
        console.log(`[WS:provider] Connecting to gateway: ${gatewayUrl} (activeOrg=${activeOrg ?? 'personal'})`);
        wsClient.connect(gatewayUrl, tokenProvider, activeOrgProvider);
        connectedRef.current = true;
      })().catch((error) => {
        // connect() handles its own failures today; this keeps a future throw from
        // becoming an unhandled rejection.
        console.error('[WS:provider] gateway resolution failed', error);
      });
    } else if (lastOrgRef.current !== currentOrgId) {
      lastOrgRef.current = currentOrgId;
      if (!connectedRef.current) {
        // The first connect is still resolving its gateway origin. activeOrgProvider
        // reads the live store, so the pending connect will already use the new
        // workspace; calling reconnect() on a client that has never connected throws on
        // its null token provider and burns a spurious failed attempt plus a backoff.
        return;
      }
      // Workspace switched - re-handshake so the session's organizationId updates.
      console.log(`[WS:provider] Active workspace changed to ${currentOrgId ?? 'personal'}; reconnecting WS`);
      wsClient.reconnect();
    }
  }, [isAuthenticated, isLoading, currentOrgId]);

  // Token refresh - independent of the connection lifecycle; reads the live getter.
  useEffect(() => {
    const refreshInterval = setInterval(
      () => { wsClient.refreshToken(); },
      12 * 60 * 60 * 1000, // every 12h, well ahead of the 14-day token expiry
    );
    return () => clearInterval(refreshInterval);
  }, []);

  // Disconnect ONLY on real unmount (app teardown) - never on navigation/re-render.
  // mountedRef is cleared in the cleanup so a gateway resolution still in flight abandons
  // instead of calling connect() on the socket this just tore down, and RE-ARMED in the
  // body: a ref survives a teardown that preserves the component INSTANCE (StrictMode's dev
  // double-invoke, Offscreen/Activity), so without re-arming it every later resolution
  // would abort forever - dead realtime with no error. A conditionally remounted subtree is
  // NOT such a path (fresh hook state re-initialises the ref), and StrictMode is off in
  // this app, so this is defence rather than a live bug.
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; wsClient.disconnect(); };
  }, []);

  return <>{children}</>;
}
