package com.apimarketplace.auth.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InternalServiceAuthenticationConfigurationTest {

    @Test
    void everyInternalAuthRouteRequiresServiceHmacAndExplicitAudience() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        String publicPaths = between(yaml, "    public-paths:", "    hmac-required-paths:");
        String hmacPaths = between(
                yaml, "    hmac-required-paths:", "    service-authenticated-paths:");
        String servicePaths = between(
                yaml, "    service-authenticated-paths:", "    service-secrets:");

        assertThat(publicPaths).doesNotContain("- /api/internal/");
        assertThat(hmacPaths).contains("- /api/internal/");
        assertThat(servicePaths).contains("- /api/internal/");
        assertThat(yaml)
                .contains("\"POST:/api/internal/auth/model-pricing/sync\"")
                .contains("\"POST:/api/internal/auth/credit/dead-letter\"")
                .contains("\"GET:/api/internal/auth/users/{userId}/default-organization\"")
                .contains("\"PUT:/api/internal/auth/org-restrictions/bulk\"");
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new AssertionError("Missing YAML section " + start + " .. " + end);
        }
        return value.substring(from, to);
    }
}
