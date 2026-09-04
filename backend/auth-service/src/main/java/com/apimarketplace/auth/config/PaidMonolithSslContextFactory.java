package com.apimarketplace.auth.config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * Builds the trust boundary used only by the paid-monolith HTTP client.
 *
 * <p>This deliberately never writes {@code javax.net.ssl.*}: OAuth, Stripe, webhooks and every
 * other HTTPS client keep the platform/JVM trust configuration.
 */
public final class PaidMonolithSslContextFactory {

    private PaidMonolithSslContextFactory() {
    }

    public static SSLContext create(String trustStoreFile, String passwordFile) {
        String store = trimToNull(trustStoreFile);
        String passwordPath = trimToNull(passwordFile);
        if (store == null && passwordPath == null) {
            return null;
        }
        if (store == null || passwordPath == null) {
            throw new IllegalStateException(
                    "Paid-monolith truststore and password-file must be configured together");
        }

        Path storePath = configuredPath(
                store, "Paid-monolith truststore path is invalid");
        Path secretPath = configuredPath(
                passwordPath, "Paid-monolith truststore password-file path is invalid");
        requireReadableFile(
                storePath, "Paid-monolith truststore file is missing or unreadable");
        requireReadableFile(
                secretPath, "Paid-monolith truststore password file is missing or unreadable");

        char[] password = readPassword(secretPath);
        try (InputStream input = Files.newInputStream(storePath)) {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(input, password);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "Paid-monolith truststore could not initialize its dedicated SSL context",
                    failure);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static Path configuredPath(String value, String errorMessage) {
        try {
            return Path.of(value);
        } catch (InvalidPathException invalidPath) {
            throw new IllegalStateException(errorMessage, invalidPath);
        }
    }

    private static void requireReadableFile(Path path, String errorMessage) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(errorMessage);
        }
    }

    private static char[] readPassword(Path passwordPath) {
        final String contents;
        try {
            contents = Files.readString(passwordPath, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(
                    "Paid-monolith truststore password file could not be read", unreadable);
        }
        String password = removeTrailingLineBreaks(contents);
        if (password.isEmpty()) {
            throw new IllegalStateException(
                    "Paid-monolith truststore password file is empty");
        }
        return password.toCharArray();
    }

    private static String removeTrailingLineBreaks(String value) {
        int end = value.length();
        while (end > 0) {
            char current = value.charAt(end - 1);
            if (current != '\n' && current != '\r') {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
