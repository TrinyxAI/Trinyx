package com.apimarketplace.common.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySignatureV2Test {

    @Test
    void signatureBindsEverySecurityRelevantField() {
        GatewaySignatureV2.Context base = context("GET", "/api/a?x=1", "nonce", "body", "USER");
        String signature = GatewaySignatureV2.sign("secret", base);

        assertThat(GatewaySignatureV2.sign("secret", context("POST", "/api/a?x=1", "nonce", "body", "USER")))
                .isNotEqualTo(signature);
        assertThat(GatewaySignatureV2.sign("secret", context("GET", "/api/b?x=1", "nonce", "body", "USER")))
                .isNotEqualTo(signature);
        assertThat(GatewaySignatureV2.sign("secret", context("GET", "/api/a?x=1", "other", "body", "USER")))
                .isNotEqualTo(signature);
        assertThat(GatewaySignatureV2.sign("secret", context("GET", "/api/a?x=1", "nonce", "other", "USER")))
                .isNotEqualTo(signature);
        assertThat(GatewaySignatureV2.sign("secret", context("GET", "/api/a?x=1", "nonce", "body", "ADMIN")))
                .isNotEqualTo(signature);
    }

    private GatewaySignatureV2.Context context(
            String method, String target, String nonce, String hash, String roles) {
        return new GatewaySignatureV2.Context(
                "1700000000000", nonce, method, target, hash,
                "provider", "42", "principal", "billing", "org", "OWNER", roles, "install");
    }
}
