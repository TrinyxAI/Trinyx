package com.apimarketplace.gateway;

import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.stream.Stream;

/** Single explicit allowlist for edge endpoints that authenticate themselves or are read-only. */
final class GatewayPublicRoutes {

    private static final String[] EXACT = {
            "/healthz",
            "/widget.js",
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

    private static final String[] GET_PATTERNS = {
            "/api/ce-marketplace/*/snapshot"
    };

    private static final String CE_MARKETPLACE_PREFIX = "/api/ce-marketplace/";
    private static final String SNAPSHOT_SUFFIX = "/snapshot";

    private static final String[] PREFIX = {
            "/actuator/health/",
            "/cdp/",
            "/api/catalog/public/bundles/",
            "/api/public/",
            "/api/shared/",
            "/api/websearch/screenshots/",
            "/widget/",
            "/share/",
            "/c/",
            "/webhook/",
            "/approval-callback/",
            "/chat/",
            "/form/",
            "/app/public/"
    };

    private GatewayPublicRoutes() {}

    static boolean matches(HttpMethod method, String path) {
        return matchesAnyMethod(path)
                || (HttpMethod.GET.equals(method) && matchesCeMarketplaceSnapshot(path));
    }

    private static boolean matchesAnyMethod(String path) {
        return Arrays.asList(EXACT).contains(path)
                || Arrays.stream(PREFIX).anyMatch(path::startsWith);
    }

    private static boolean matchesCeMarketplaceSnapshot(String path) {
        if (!path.startsWith(CE_MARKETPLACE_PREFIX) || !path.endsWith(SNAPSHOT_SUFFIX)) {
            return false;
        }
        String publicationId = path.substring(
                CE_MARKETPLACE_PREFIX.length(), path.length() - SNAPSHOT_SUFFIX.length());
        return !publicationId.isBlank() && publicationId.indexOf('/') < 0;
    }

    static String[] securityMatchers() {
        return Stream.concat(Arrays.stream(EXACT),
                        Arrays.stream(PREFIX).map(prefix -> prefix + "**"))
                .toArray(String[]::new);
    }

    static String[] getSecurityMatchers() {
        return GET_PATTERNS.clone();
    }
}
