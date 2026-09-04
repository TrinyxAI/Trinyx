package com.apimarketplace.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaidMonolithSslContextFactoryTest {

    @Test
    void dedicatedContextDoesNotMutateGlobalJvmTrust(@TempDir Path tempDir)
            throws Exception {
        String password = "test-secret";
        Path store = tempDir.resolve("paid-monolith.p12");
        Path passwordFile = tempDir.resolve("password");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password.toCharArray());
        try (OutputStream output = Files.newOutputStream(store)) {
            keyStore.store(output, password.toCharArray());
        }
        Files.writeString(passwordFile, password + "\n");

        String originalStore = System.getProperty("javax.net.ssl.trustStore");
        String originalPassword = System.getProperty("javax.net.ssl.trustStorePassword");
        String originalType = System.getProperty("javax.net.ssl.trustStoreType");

        assertThat(PaidMonolithSslContextFactory.create(
                store.toString(), passwordFile.toString())).isNotNull();
        assertThat(System.getProperty("javax.net.ssl.trustStore")).isEqualTo(originalStore);
        assertThat(System.getProperty("javax.net.ssl.trustStorePassword"))
                .isEqualTo(originalPassword);
        assertThat(System.getProperty("javax.net.ssl.trustStoreType")).isEqualTo(originalType);
    }

    @Test
    void absentConfigurationUsesTheNormalPlatformTrust() {
        assertThat(PaidMonolithSslContextFactory.create("", "")).isNull();
    }

    @Test
    void partialOrUnreadableConfigurationFailsClosed(@TempDir Path tempDir) {
        assertThatThrownBy(() ->
                PaidMonolithSslContextFactory.create("missing.p12", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");

        assertThatThrownBy(() ->
                PaidMonolithSslContextFactory.create(
                        tempDir.resolve("missing.p12").toString(),
                        tempDir.resolve("missing-secret").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or unreadable");
    }
}
