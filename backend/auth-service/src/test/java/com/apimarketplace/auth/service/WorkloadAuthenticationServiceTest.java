package com.apimarketplace.auth.service;

import com.apimarketplace.common.security.Ed25519Jws;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkloadAuthenticationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void issuesShortLivedTokenAndRejectsReplay() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        StringRedisTemplate redis = redis(true, false);
        WorkloadAuthenticationService service = service(redis, pair);

        String token = service.issue("trinyx-cloud-runtime");
        var identity = service.authenticate("Bearer " + token);

        assertThat(identity.serviceId()).isEqualTo("trinyx-cloud-runtime");
        assertThat(identity.expiresAt()).isAfter(Instant.now());
        assertThatThrownBy(() -> service.authenticate("Bearer " + token))
                .isInstanceOf(WorkloadAuthenticationService.WorkloadAuthenticationException.class)
                .hasMessageContaining("Replayed");
    }

    @Test
    void missingAndMalformedBearerAreAuthenticationFailures() throws Exception {
        WorkloadAuthenticationService service = service(redis(true), keyPair());

        assertAuthenticationFailure(() -> service.authenticate(null));
        assertAuthenticationFailure(() -> service.authenticate(""));
        assertAuthenticationFailure(() -> service.authenticate("Bearer invalid"));
    }

    @Test
    void invalidSignatureIsAnAuthenticationFailure() throws Exception {
        KeyPair trusted = keyPair();
        KeyPair attacker = keyPair();
        WorkloadAuthenticationService service = service(redis(true), trusted);
        String token = token(attacker, "trinyx-cloud", "trinyx-billing-authority",
                "trinyx-cloud-runtime", Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(60));

        assertAuthenticationFailure(() -> service.authenticate("Bearer " + token));
    }

    @Test
    void expiredWrongIssuerAndWrongAudienceAreAuthenticationFailures() throws Exception {
        KeyPair pair = keyPair();
        WorkloadAuthenticationService service = service(redis(true), pair);
        Instant now = Instant.now();

        String expired = token(pair, "trinyx-cloud", "trinyx-billing-authority",
                "trinyx-cloud-runtime", now.minusSeconds(90), now.minusSeconds(1));
        String wrongIssuer = token(pair, "other-issuer", "trinyx-billing-authority",
                "trinyx-cloud-runtime", now.minusSeconds(1), now.plusSeconds(60));
        String wrongAudience = token(pair, "trinyx-cloud", "other-audience",
                "trinyx-cloud-runtime", now.minusSeconds(1), now.plusSeconds(60));

        assertAuthenticationFailure(() -> service.authenticate("Bearer " + expired));
        assertAuthenticationFailure(() -> service.authenticate("Bearer " + wrongIssuer));
        assertAuthenticationFailure(() -> service.authenticate("Bearer " + wrongAudience));
    }

    private static void assertAuthenticationFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(
                        WorkloadAuthenticationService.WorkloadAuthenticationException.class);
    }

    private static WorkloadAuthenticationService service(
            StringRedisTemplate redis, KeyPair pair) {
        String privateKey = Base64.getEncoder().encodeToString(
                pair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(
                pair.getPublic().getEncoded());
        return new WorkloadAuthenticationService(
                redis, new ObjectMapper(), "cloud-1=" + publicKey,
                "trinyx-cloud", "trinyx-billing-authority",
                "trinyx-cloud", "trinyx-billing-authority",
                "cloud-1", privateKey);
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redis(Boolean... results) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(
                anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(results[0], java.util.Arrays.copyOfRange(
                        results, 1, results.length));
        return redis;
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String token(
            KeyPair pair, String issuer, String audience, String serviceId,
            Instant issuedAt, Instant expiresAt) {
        var claims = new ObjectMapper().createObjectNode();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("serviceId", serviceId);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("nbf", issuedAt.minusSeconds(1).getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        return Ed25519Jws.sign(claims, "cloud-1", pair.getPrivate());
    }
}
