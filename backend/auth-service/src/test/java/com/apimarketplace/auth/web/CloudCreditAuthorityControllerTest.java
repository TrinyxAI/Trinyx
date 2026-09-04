package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CloudCreditAuthorityService;
import com.apimarketplace.auth.service.WorkloadAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudCreditAuthorityControllerTest {

    private final CloudCreditAuthorityService authority = mock(CloudCreditAuthorityService.class);
    private final WorkloadAuthenticationService workloads = mock(WorkloadAuthenticationService.class);
    private final CloudCreditAuthorityController controller =
            new CloudCreditAuthorityController(authority, workloads);

    @Test
    void onlyDedicatedCloudRuntimeServiceCanReachWallet() {
        when(workloads.authenticate("Bearer other-workload")).thenReturn(
                new WorkloadAuthenticationService.WorkloadIdentity(
                        "unrelated-service", UUID.randomUUID(), Instant.now().plusSeconds(30)));

        assertThatThrownBy(() -> controller.reserve("Bearer other-workload", reserve()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException) failure)
                        .getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(authority);
    }

    @Test
    void missingAndMalformedCredentialsReturnUnauthorizedWithoutParserLeak() {
        when(workloads.authenticate(null)).thenThrow(
                new WorkloadAuthenticationService.WorkloadAuthenticationException(
                        "Missing workload bearer token"));
        when(workloads.authenticate("Bearer invalid")).thenThrow(
                new WorkloadAuthenticationService.WorkloadAuthenticationException(
                        "Invalid workload bearer token",
                        new IllegalArgumentException("Compact JWS must have three parts")));

        for (String credential : new String[]{null, "Bearer invalid"}) {
            assertThatThrownBy(() -> controller.reserve(credential, reserve()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                        assertThat(failure.getStatusCode().value()).isEqualTo(401);
                        assertThat(failure.getReason())
                                .isEqualTo("INVALID_WORKLOAD_IDENTITY")
                                .doesNotContain("Compact JWS");
                    });
        }
        verifyNoInteractions(authority);
    }

    @Test
    void authenticatedCloudRuntimeDelegatesExactlyOnce() {
        var request = reserve();
        var response = new CloudCreditAuthorityService.ReserveResponse(
                request.operationId(), request.operationId(), "RESERVED",
                Instant.now().plusSeconds(600), BigDecimal.TEN, false);
        when(workloads.authenticate("Bearer workload")).thenReturn(
                new WorkloadAuthenticationService.WorkloadIdentity(
                        "trinyx-cloud-runtime", UUID.randomUUID(), Instant.now().plusSeconds(30)));
        when(authority.reserve(request)).thenReturn(response);

        assertThat(controller.reserve("Bearer workload", request)).isEqualTo(response);
        verify(authority).reserve(request);
    }

    @Test
    void authenticatedCloudRuntimeCanAcknowledgeDispatchExactlyOnce() {
        UUID operationId = UUID.randomUUID();
        var request = new CloudCreditAuthorityService.DispatchingRequest(
                "a".repeat(64), "openai", "gpt");
        var response = new CloudCreditAuthorityService.SettlementResponse(
                operationId, "DISPATCHING", BigDecimal.ZERO, BigDecimal.TEN,
                false, "PROVIDER_DISPATCH_AUTHORIZED_HOLD_RETAINED");
        when(workloads.authenticate("Bearer workload")).thenReturn(
                new WorkloadAuthenticationService.WorkloadIdentity(
                        "trinyx-cloud-runtime", UUID.randomUUID(), Instant.now().plusSeconds(30)));
        when(authority.dispatching(operationId, request)).thenReturn(response);

        assertThat(controller.dispatching("Bearer workload", operationId, request))
                .isEqualTo(response);
        verify(authority).dispatching(operationId, request);
    }

    private static CloudCreditAuthorityService.ReserveRequest reserve() {
        return new CloudCreditAuthorityService.ReserveRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "LLM", BigDecimal.ONE, BigDecimal.TEN,
                "openai", "gpt", "a".repeat(64));
    }
}
