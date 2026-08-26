package com.apimarketplace.auth.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Configures the JVM truststore for the private paid-monolith TLS connection
 * from file-backed configuration before the Spring context creates HTTP clients.
 */
public final class PaidMonolithTrustStoreEnvironmentPostProcessor
        implements EnvironmentPostProcessor {

    static final String TRUSTSTORE_FILE_ENV = "PAID_MONOLITH_TRUSTSTORE_FILE";
    static final String TRUSTSTORE_PASSWORD_FILE_ENV =
            "PAID_MONOLITH_TRUSTSTORE_PASSWORD_FILE";

    private static final String JAVAX_TRUSTSTORE = "javax.net.ssl.trustStore";
    private static final String JAVAX_TRUSTSTORE_PASSWORD =
            "javax.net.ssl.trustStorePassword";
    private static final String JAVAX_TRUSTSTORE_TYPE = "javax.net.ssl.trustStoreType";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {
        String trustStoreFile = trimToNull(environment.getProperty(TRUSTSTORE_FILE_ENV));
        String passwordFile = trimToNull(
                environment.getProperty(TRUSTSTORE_PASSWORD_FILE_ENV));

        if (trustStoreFile == null && passwordFile == null) {
            return;
        }
        if (trustStoreFile == null) {
            throw new IllegalStateException(
                    "Paid-monolith truststore path is required when password-file configuration is enabled");
        }
        if (passwordFile == null) {
            throw new IllegalStateException(
                    "Paid-monolith truststore password-file path is required when truststore configuration is enabled");
        }

        Path trustStorePath = configuredPath(
                trustStoreFile, "Paid-monolith truststore path is invalid");
        requireReadableFile(
                trustStorePath, "Paid-monolith truststore file is missing or unreadable");

        Path passwordPath = configuredPath(
                passwordFile, "Paid-monolith truststore password-file path is invalid");
        requireReadableFile(
                passwordPath, "Paid-monolith truststore password file is missing or unreadable");

        String password = readPassword(passwordPath);

        System.setProperty(JAVAX_TRUSTSTORE, trustStorePath.toString());
        System.setProperty(JAVAX_TRUSTSTORE_PASSWORD, password);
        System.setProperty(JAVAX_TRUSTSTORE_TYPE, "PKCS12");
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

    private static String readPassword(Path passwordPath) {
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
        return password;
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
