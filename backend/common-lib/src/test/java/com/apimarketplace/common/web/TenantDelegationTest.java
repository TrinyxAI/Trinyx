package com.apimarketplace.common.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantDelegationTest {

    private static final String SECRET = "d".repeat(32);
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    @Test
    void bindsEveryTenantIdentityFieldAndExpires() {
        String token = TenantDelegation.issue(
                SECRET, "42", "principal", "billing", "org", "install", NOW);

        assertThat(TenantDelegation.verify(
                token, SECRET, "42", "principal", "billing", "org", "install", NOW.plusSeconds(30)))
                .isTrue();
        assertThat(TenantDelegation.verify(
                token, SECRET, "43", "principal", "billing", "org", "install", NOW.plusSeconds(30)))
                .isFalse();
        assertThat(TenantDelegation.verify(
                token, SECRET, "42", "principal", "billing", "other-org", "install", NOW.plusSeconds(30)))
                .isFalse();
        assertThat(TenantDelegation.verify(
                token, SECRET, "42", "principal", "billing", "org", "install", NOW.plusSeconds(121)))
                .isFalse();
    }

    @Test
    void rejectsTamperingAndPublicPlaceholders() {
        String token = TenantDelegation.issue(
                SECRET, "42", "principal", "billing", "org", "install", NOW);

        assertThat(TenantDelegation.verify(
                token + "x", SECRET, "42", "principal", "billing", "org", "install", NOW))
                .isFalse();
        assertThatThrownBy(() -> TenantDelegation.requireSecret(
                "replace-with-at-least-32-random-bytes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }
}
