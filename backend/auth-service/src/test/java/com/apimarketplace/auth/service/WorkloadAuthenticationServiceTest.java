package com.apimarketplace.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkloadAuthenticationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void issuesShortLivedTokenAndRejectsReplay() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(true, false);

        WorkloadAuthenticationService service = new WorkloadAuthenticationService(
                redis, new com.fasterxml.jackson.databind.ObjectMapper(),
                "cloud-1=" + publicKey,
                "trinyx-cloud",
                "trinyx-billing-authority",
                "trinyx-cloud",
                "trinyx-billing-authority",
                "cloud-1",
                privateKey);

        String token = service.issue("trinyx-cloud-runtime");
        var identity = service.authenticate("Bearer " + token);

        assertThat(identity.serviceId()).isEqualTo("trinyx-cloud-runtime");
        assertThat(identity.expiresAt()).isAfter(java.time.Instant.now());
        assertThatThrownBy(() -> service.authenticate("Bearer " + token))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Replayed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesWrongDirectionalAudience() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));

        WorkloadAuthenticationService service = new WorkloadAuthenticationService(
                redis, new com.fasterxml.jackson.databind.ObjectMapper(), "cloud-1=" + publicKey,
                "trinyx-paid-authority", "trinyx-cloud-internal",
                "trinyx-cloud", "trinyx-billing-authority",
                "cloud-1", privateKey);

        String token = service.issue("trinyx-cloud-runtime");

        assertThatThrownBy(() -> service.authenticate("Bearer " + token))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid workload token claims");
    }
}
