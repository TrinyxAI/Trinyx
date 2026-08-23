package com.apimarketplace.gateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
final class GatewayHealthController {
    @GetMapping("/healthz")
    Map<String, Object> health() {
        return Map.of("status", "UP", "component", "trinyx-authenticated-gateway",
                "signatureVersion", 2);
    }
}
