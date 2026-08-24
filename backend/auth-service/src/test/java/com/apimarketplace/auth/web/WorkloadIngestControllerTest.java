package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CloudIdentityBindingService;
import com.apimarketplace.auth.service.EntitlementProjectionService;
import com.apimarketplace.auth.service.WorkloadAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkloadIngestControllerTest {

    @Test
    void invalidProjectionWorkloadIsATerminalUnauthorizedRejection() {
        EntitlementProjectionService projections = mock(EntitlementProjectionService.class);
        WorkloadAuthenticationService workloads = mock(WorkloadAuthenticationService.class);
        when(workloads.authenticate("Bearer invalid",
                "trinyx-paid-authority", "trinyx-cloud-internal"))
                .thenThrow(new SecurityException("invalid"));

        WorkloadEntitlementProjectionController controller =
                new WorkloadEntitlementProjectionController(projections, workloads);

        assertThatThrownBy(() -> controller.apply("Bearer invalid",
                new WorkloadEntitlementProjectionController.ProjectionRequest("assertion")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(projections);
    }

    @Test
    void malformedProjectionAssertionIsATerminalBadRequest() {
        EntitlementProjectionService projections = mock(EntitlementProjectionService.class);
        WorkloadAuthenticationService workloads = mock(WorkloadAuthenticationService.class);
        when(workloads.authenticate("Bearer valid",
                "trinyx-paid-authority", "trinyx-cloud-internal"))
                .thenReturn(new WorkloadAuthenticationService.WorkloadIdentity(
                        "trinyx-paid-authority", UUID.randomUUID(), Instant.now().plusSeconds(60)));
        when(projections.apply("malformed")).thenThrow(new IllegalArgumentException("invalid"));

        WorkloadEntitlementProjectionController controller =
                new WorkloadEntitlementProjectionController(projections, workloads);

        assertThatThrownBy(() -> controller.apply("Bearer valid",
                new WorkloadEntitlementProjectionController.ProjectionRequest("malformed")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void invalidIdentityWorkloadIsATerminalUnauthorizedRejection() {
        CloudIdentityBindingService bindings = mock(CloudIdentityBindingService.class);
        WorkloadAuthenticationService workloads = mock(WorkloadAuthenticationService.class);
        when(workloads.authenticate("Bearer invalid",
                "trinyx-paid-authority", "trinyx-cloud-internal"))
                .thenThrow(new SecurityException("invalid"));

        WorkloadIdentityBindingController controller =
                new WorkloadIdentityBindingController(bindings, workloads);

        assertThatThrownBy(() -> controller.revoke("Bearer invalid",
                new WorkloadIdentityBindingController.TombstoneRequest("assertion")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(bindings);
    }

    @Test
    void malformedIdentityTombstoneIsATerminalBadRequest() {
        CloudIdentityBindingService bindings = mock(CloudIdentityBindingService.class);
        WorkloadAuthenticationService workloads = mock(WorkloadAuthenticationService.class);
        when(workloads.authenticate("Bearer valid",
                "trinyx-paid-authority", "trinyx-cloud-internal"))
                .thenReturn(new WorkloadAuthenticationService.WorkloadIdentity(
                        "trinyx-paid-authority", UUID.randomUUID(), Instant.now().plusSeconds(60)));
        when(bindings.applyRevocation("malformed"))
                .thenThrow(new IllegalArgumentException("invalid"));

        WorkloadIdentityBindingController controller =
                new WorkloadIdentityBindingController(bindings, workloads);

        assertThatThrownBy(() -> controller.revoke("Bearer valid",
                new WorkloadIdentityBindingController.TombstoneRequest("malformed")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
