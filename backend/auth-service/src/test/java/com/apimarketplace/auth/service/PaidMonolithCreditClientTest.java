package com.apimarketplace.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.*;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaidMonolithCreditClientTest {

    private final RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
    private final RestTemplate http = mock(RestTemplate.class);
    private final WorkloadAuthenticationService workloads = mock(WorkloadAuthenticationService.class);
    private PaidMonolithCreditClient client;

    @BeforeEach
    void setUp() {
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(http);
        when(workloads.issue("trinyx-cloud-runtime")).thenReturn("workload-jwt");
        client = new PaidMonolithCreditClient(
                builder, workloads, "https://billing-internal.trinyx.private:8443/");
    }

    @Test
    void buildsDedicatedTlsRequestFactoryWithRealSpringBootBuilder(
            @TempDir Path tempDir) throws Exception {
        String password = "test-truststore-password";
        Path trustStore = tempDir.resolve("paid-truststore.p12");
        Path passwordFile = tempDir.resolve("paid-truststore.password");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password.toCharArray());
        try (OutputStream output = Files.newOutputStream(trustStore)) {
            keyStore.store(output, password.toCharArray());
        }
        Files.writeString(passwordFile, password + System.lineSeparator());

        PaidMonolithCreditClient tlsClient = new PaidMonolithCreditClient(
                new RestTemplateBuilder(), workloads,
                "https://billing-internal.trinyx.private:8443",
                trustStore.toString(), passwordFile.toString());

        RestTemplate configuredHttp =
                (RestTemplate) ReflectionTestUtils.getField(tlsClient, "http");
        assertThat(configuredHttp).isNotNull();
        assertThat(configuredHttp.getRequestFactory())
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void dispatchingUsesThePrivateExactRouteAndWorkloadCredential() {
        UUID operationId = UUID.randomUUID();
        var response = new CloudCreditAuthorityService.SettlementResponse(
                operationId, "DISPATCHING", BigDecimal.ZERO, BigDecimal.TEN,
                false, "PROVIDER_DISPATCH_AUTHORIZED_HOLD_RETAINED");
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(CloudCreditAuthorityService.SettlementResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        assertThat(client.dispatching(operationId,
                new CloudCreditAuthorityService.DispatchingRequest(
                        "a".repeat(64), "openai", "gpt"))).isEqualTo(response);

        var url = org.mockito.ArgumentCaptor.forClass(String.class);
        var entity = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(http).exchange(url.capture(), eq(HttpMethod.POST), entity.capture(),
                eq(CloudCreditAuthorityService.SettlementResponse.class));
        assertThat(url.getValue()).isEqualTo(
                "https://billing-internal.trinyx.private:8443/internal/v1/credit-reservations/"
                        + operationId + "/dispatching");
        assertThat(entity.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer workload-jwt");
    }

    @Test
    void businessConflictIsPermanentAndNeverClassifiedForRetry() {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(CloudCreditAuthorityService.ReserveResponse.class)))
                .thenThrow(response(HttpStatus.CONFLICT, "REQUEST_HASH_MISMATCH"));

        assertThatThrownBy(() -> client.reserve(reserve()))
                .isInstanceOf(PaidMonolithCreditClient.PermanentAuthorityException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("REQUEST_HASH_MISMATCH");
    }

    @Test
    void authenticationRejectionIsPermanent() {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(CloudCreditAuthorityService.ReserveResponse.class)))
                .thenThrow(response(HttpStatus.UNAUTHORIZED, "invalid workload"));

        assertThatThrownBy(() -> client.reserve(reserve()))
                .isInstanceOf(PaidMonolithCreditClient.PermanentAuthorityException.class);
    }

    @Test
    void requestTimeoutAndRateLimitRemainRetryable() {
        for (HttpStatus status : new HttpStatus[]{
                HttpStatus.REQUEST_TIMEOUT, HttpStatus.TOO_MANY_REQUESTS}) {
            reset(http);
            when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                    eq(CloudCreditAuthorityService.ReserveResponse.class)))
                    .thenThrow(response(status, "retry later"));

            assertThatThrownBy(() -> client.reserve(reserve()))
                    .isInstanceOf(PaidMonolithCreditClient.RetryableAuthorityException.class);
        }
    }

    @Test
    void serverAndTransportFailuresRemainRetryable() {
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(CloudCreditAuthorityService.ReserveResponse.class)))
                .thenThrow(response(HttpStatus.SERVICE_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> client.reserve(reserve()))
                .isInstanceOf(PaidMonolithCreditClient.RetryableAuthorityException.class);

        reset(http);
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(CloudCreditAuthorityService.ReserveResponse.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> client.reserve(reserve()))
                .isInstanceOf(PaidMonolithCreditClient.RetryableAuthorityException.class);
    }

    private static RestClientResponseException response(HttpStatus status, String body) {
        if (status.is5xxServerError()) {
            return new HttpServerErrorException(status, status.getReasonPhrase(),
                    HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        return new HttpClientErrorException(status, status.getReasonPhrase(),
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static CloudCreditAuthorityService.ReserveRequest reserve() {
        return new CloudCreditAuthorityService.ReserveRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "LLM", BigDecimal.ONE, BigDecimal.TEN,
                "openai", "gpt", "a".repeat(64));
    }
}
