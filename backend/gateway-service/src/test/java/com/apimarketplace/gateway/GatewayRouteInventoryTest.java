package com.apimarketplace.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.boot.env.YamlPropertySourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteInventoryTest {

    private final String routes = load();

    @Test
    void applicationYamlIsSyntacticallyLoadableBySpring() throws Exception {
        assertThat(new YamlPropertySourceLoader().load(
                "gateway-routes",
                new ByteArrayResource(routes.getBytes(StandardCharsets.UTF_8))))
                .isNotEmpty();
    }

    @Test
    void routesEveryCoreServiceThroughAnExplicitFamily() {
        Map<String, String> required = Map.ofEntries(
                Map.entry("auth-application", "AUTH_SERVICE_URL"),
                Map.entry("catalog-application", "CATALOG_SERVICE_URL"),
                Map.entry("agent-application", "AGENT_SERVICE_URL"),
                Map.entry("conversation-application", "CONVERSATION_SERVICE_URL"),
                Map.entry("datasource-application", "DATASOURCE_SERVICE_URL"),
                Map.entry("interface-application", "INTERFACE_SERVICE_URL"),
                Map.entry("trigger-application", "TRIGGER_SERVICE_URL"),
                Map.entry("publication-application", "PUBLICATION_SERVICE_URL"),
                Map.entry("orchestrator-application", "ORCHESTRATOR_SERVICE_URL"),
                Map.entry("storage-application", "STORAGE_SERVICE_URL"));
        required.forEach((id, target) -> assertThat(route(id)).contains(target, "Path="));
    }

    @Test
    void workflowDagAndKnownCollisionsReachTheirFacadeServices() {
        assertThat(route("orchestrator-workflows-v2"))
                .contains("ORCHESTRATOR_SERVICE_URL", "Path=/api/v2/workflows/**");
        assertThat(route("conversation-admin"))
                .contains("CONVERSATION_SERVICE_URL", "/api/admin/conversations/**");
        assertThat(route("publication-ce-tls"))
                .contains("PUBLICATION_SERVICE_URL", "/api/ce/tls/**");
        assertThat(route("orchestrator-storage-facade"))
                .contains("ORCHESTRATOR_SERVICE_URL", "/api/storage/explorer/**");
        assertThat(routes.indexOf("- id: orchestrator-workflows-v2"))
                .isLessThan(routes.indexOf("- id: orchestrator-application"));
    }

    @Test
    void preservesHistoricalPublicEdgeRewritesAndCollisionOrder() {
        assertThat(route("agent-widget-loader-public"))
                .contains("AGENT_SERVICE_URL", "Path=/widget.js",
                        "SetPath=/api/internal/widget/loader.js");
        assertThat(route("agent-widget-public"))
                .contains("AGENT_SERVICE_URL", "Path=/widget/**",
                        "/api/internal/widget/");
        assertThat(route("publication-share-public"))
                .contains("PUBLICATION_SERVICE_URL", "Path=/share/**",
                        "/api/public/share/");
        assertThat(route("conversation-share-public"))
                .contains("CONVERSATION_SERVICE_URL", "Path=/c/**",
                        "/api/shared/c/");
        assertThat(route("agent-webhook-public"))
                .contains("AGENT_SERVICE_URL", "Path=/webhook/agent/**",
                        "/api/internal/webhook/");
        assertThat(route("orchestrator-webhook-public"))
                .contains("ORCHESTRATOR_SERVICE_URL", "Path=/webhook/**",
                        "/api/internal/webhook/");
        assertThat(routes.indexOf("- id: agent-webhook-public"))
                .isLessThan(routes.indexOf("- id: orchestrator-webhook-public"));
        assertThat(route("orchestrator-approval-callback-public"))
                .contains("Path=/approval-callback/**", "/api/internal/approval-callback/");
        assertThat(route("orchestrator-chat-public"))
                .contains("Path=/chat/**", "/api/internal/chat/");
        assertThat(route("orchestrator-form-public"))
                .contains("Path=/form/**", "/api/internal/form/");
        assertThat(route("orchestrator-app-public"))
                .contains("Path=/app/public/**", "/api/internal/app/public/");
    }

    @Test
    void serviceSelectorsCannotBypassPublicRoutePolicy() {
        assertThat(routes)
                .doesNotContain("Path=/api/auth-service/")
                .doesNotContain("Path=/api/orchestrator-service/")
                .doesNotContain("RewritePath=/api/");
    }

    @Test
    void noCatchAllOrInternalRouteIsExposed() {
        assertThat(routes)
                .doesNotContain("Path=/api/**")
                .doesNotContain("Path=/internal/**")
                .doesNotContain("Path=/api/internal/**")
                .doesNotContain("Path=/webhooks/**");
        assertThat(route("auth-stripe-webhook")).contains("Path=/webhooks/stripe");
    }

    private String route(String id) {
        String marker = "            - id: " + id;
        int start = routes.indexOf(marker);
        assertThat(start).as("route %s exists", id).isGreaterThanOrEqualTo(0);
        int end = routes.indexOf("\n            - id:", start + marker.length());
        return routes.substring(start, end < 0 ? routes.length() : end);
    }

    private static String load() {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
