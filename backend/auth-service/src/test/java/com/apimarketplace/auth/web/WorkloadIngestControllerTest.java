package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CloudIdentityBindingService;
import com.apimarketplace.auth.service.EntitlementProjectionService;
import com.apimarketplace.auth.service.WorkloadAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkloadIngestControllerTest {

    @Test
    void invalidProjectionWorkloadIsAterminalUnauthorizedRejection() {
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
    void invalidIdentityWorkloadIsAterminalUnauthorizedRejection() {
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
}
