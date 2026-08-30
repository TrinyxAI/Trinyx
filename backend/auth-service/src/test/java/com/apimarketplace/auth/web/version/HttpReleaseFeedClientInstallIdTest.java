package com.apimarketplace.auth.web.version;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins what actually goes out on the wire.
 *
 * <p>Every other test in this feature calls a method directly, so none of them can see whether the
 * header is really attached to the request - and a header that is built but never sent fails
 * silently: the update check still works, and the fleet count simply stays at zero forever. That is
 * the failure mode this feature exists to remove, so it gets a real HTTP server.
 */
class HttpReleaseFeedClientInstallIdTest {

    private static final UUID INSTALL = UUID.fromString("3b7c1f2a-9d4e-4f6b-8c1a-2e5d7f9a0b3c");

    private HttpServer server;
    private final AtomicReference<String> receivedInstallHeader = new AtomicReference<>();
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();

    @BeforeEach
    void startFeed() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ce/releases/latest", exchange -> {
            receivedInstallHeader.set(exchange.getRequestHeaders().getFirst(CeInstallHeaders.INSTALL_ID));
            receivedQuery.set(exchange.getRequestURI().getQuery());
            byte[] body = "{\"latestVersion\":\"0.3.0\",\"releaseUrl\":null,\"securityFix\":false,\"publishedAt\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopFeed() {
        server.stop(0);
    }

    private String feedUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/ce/releases/latest";
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CeInstallIdProvider> providing(UUID id) {
        ObjectProvider<CeInstallIdProvider> provider = mock(ObjectProvider.class);
        if (id == null) {
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }
        CeInstallIdProvider source = mock(CeInstallIdProvider.class);
        when(source.current()).thenReturn(Optional.of(id));
        when(provider.getIfAvailable()).thenReturn(source);
        return provider;
    }

    @Test
    @DisplayName("sends the anonymous install id with the update check")
    void sendsInstallId() {
        HttpReleaseFeedClient client = new HttpReleaseFeedClient(feedUrl(), 5000, providing(INSTALL));

        assertThat(client.fetchLatest("0.2.13").latestVersion()).isEqualTo("0.3.0");

        assertThat(receivedInstallHeader.get()).isEqualTo(INSTALL.toString());
        assertThat(receivedQuery.get()).isEqualTo("current=0.2.13");
    }

    @Test
    @DisplayName("opted out: no identifier is sent, and the update check still answers")
    void optedOutSendsNothing() {
        // The provider bean is absent exactly when ce.version-check.send-install-id=false, so this
        // is what the opt-out looks like from here.
        HttpReleaseFeedClient client = new HttpReleaseFeedClient(feedUrl(), 5000, providing(null));

        assertThat(client.fetchLatest("0.2.13").latestVersion()).isEqualTo("0.3.0");

        assertThat(receivedInstallHeader.get()).isNull();
        assertThat(receivedQuery.get()).isEqualTo("current=0.2.13");
    }

    @Test
    @DisplayName("an unreadable install id does not stop the update check")
    void unreadableIdStillChecksForUpdates() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CeInstallIdProvider> provider = mock(ObjectProvider.class);
        CeInstallIdProvider source = mock(CeInstallIdProvider.class);
        when(source.current()).thenReturn(Optional.empty());
        when(provider.getIfAvailable()).thenReturn(source);

        HttpReleaseFeedClient client = new HttpReleaseFeedClient(feedUrl(), 5000, provider);

        // Identity is optional; learning about a security release is not.
        assertThat(client.fetchLatest("0.2.13").latestVersion()).isEqualTo("0.3.0");
        assertThat(receivedInstallHeader.get()).isNull();
    }

    @Test
    @DisplayName("the anonymous header is not the tenant-bound cloud-link header")
    void headerIsNotTheCloudLinkOne() {
        // CeInstallHeaders spends a paragraph on why these must differ: X-LiveContext-Install-Id
        // carries the CLOUD-LINK id, a UUID bound to a tenant, already sent to this same cloud on
        // the LLM relay and the bundle downloads. Renaming the constant to that spelling compiles,
        // passes every other test in this feature (they all read the constant), and starts writing
        // tenant-linkable ids into a table whose entire premise is that it holds none.
        assertThat(CeInstallHeaders.INSTALL_ID).isEqualTo("X-LiveContext-Anon-Install-Id");
    }
}
