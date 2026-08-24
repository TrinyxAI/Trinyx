package com.apimarketplace.gateway;

import java.util.Arrays;
import java.util.stream.Stream;

/** Single explicit allowlist for edge endpoints that authenticate themselves or are read-only. */
final class GatewayPublicRoutes {

    private static final String[] EXACT = {
            "/healthz",
            "/actuator/health",
            "/cdp",
            "/api/catalog/public/bundles",
            "/api/public",
            "/api/shared",
            "/webhooks/stripe",
            "/api/ce/releases/latest",
            "/.well-known/jwks.json",
            "/v1/authorize",
            "/api/users/health",
            "/api/auth/health",
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/openid-configuration",
            "/api/billing/plans",
            "/api/billing/checkout/finalize",
            "/api/billing/checkout/success",
            "/api/credentials/oauth2/callback",
            "/api/contact",
            "/api/v3/chat/models",
            "/api/files/proxy-signed",
            "/api/websearch/screenshots"
    };

    private static final String[] PREFIX = {
            "/actuator/health/",
            "/cdp/",
            "/api/catalog/public/bundles/",
            "/api/public/",
            "/api/shared/",
            "/api/websearch/screenshots/"
    };

    private GatewayPublicRoutes() {}

    static boolean matches(String path) {
        return Arrays.asList(EXACT).contains(path)
                || Arrays.stream(PREFIX).anyMatch(path::startsWith);
    }

    static String[] securityMatchers() {
        return Stream.concat(Arrays.stream(EXACT),
                        Arrays.stream(PREFIX).map(prefix -> prefix + "**"))
                .toArray(String[]::new);
    }
}
