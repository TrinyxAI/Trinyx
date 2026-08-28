package com.apimarketplace.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Signs the exact inter-service HTTP request with a service-specific HMAC key.
 * Method, path/query, body, tenant and organization are bound; nonce replay is
 * rejected by the downstream GatewayAuthenticationFilter.
 */
public final class ServiceRequestSigningInterceptor implements ClientHttpRequestInterceptor {
    private final String serviceId;
    private final String secret;

    public ServiceRequestSigningInterceptor(String serviceId, String secret) {
        if (serviceId == null || !serviceId.matches("[a-z0-9][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Invalid internal service identity");
        }
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("Internal service HMAC key must be at least 32 characters");
        }
        this.serviceId = serviceId;
        this.secret = secret;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String bodyHash = GatewaySignatureV2.sha256Hex(body);
        URI uri = request.getURI();
        String target = uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        GatewaySignatureV2.Context context = new GatewaySignatureV2.Context(
                timestamp, nonce, request.getMethod().name(), target, bodyHash,
                serviceId, headers.getFirst("X-User-ID"),
                headers.getFirst("X-Principal-ID"),
                headers.getFirst("X-Billing-Subject-ID"),
                headers.getFirst("X-Organization-ID"),
                headers.getFirst("X-Organization-Role"),
                headers.getFirst("X-User-Roles"),
                headers.getFirst("X-Install-ID"));
        headers.set("X-Provider-ID", serviceId);
        headers.set("X-Gateway-Signature-Version", GatewaySignatureV2.VERSION);
        headers.set("X-Gateway-Timestamp", timestamp);
        headers.set("X-Gateway-Nonce", nonce);
        headers.set("X-Gateway-Body-SHA256", bodyHash);
        headers.set("X-Gateway-Secret", GatewaySignatureV2.sign(secret, context));
        return execution.execute(request, body);
    }
}
