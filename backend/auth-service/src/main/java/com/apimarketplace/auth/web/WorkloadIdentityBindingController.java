package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CloudIdentityBindingService;
import com.apimarketplace.auth.service.WorkloadAuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** S2S-only identity tombstone ingestion; never exposed at the Cloud edge. */
@RestController
@RequestMapping("/internal/v1/identity-bindings/revocations")
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "external-paid-monolith")
public class WorkloadIdentityBindingController {

    private final CloudIdentityBindingService bindings;
    private final WorkloadAuthenticationService workloads;

    public WorkloadIdentityBindingController(CloudIdentityBindingService bindings,
                                             WorkloadAuthenticationService workloads) {
        this.bindings = bindings;
        this.workloads = workloads;
    }

    @PutMapping
    public CloudIdentityBindingService.BindingContext revoke(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody TombstoneRequest request) {
        authenticate(authorization);
        return bindings.applyRevocation(request.assertion());
    }

    private void authenticate(String authorization) {
        try {
            var identity = workloads.authenticate(authorization,
                    "trinyx-paid-authority", "trinyx-cloud-internal");
            if (!"trinyx-paid-authority".equals(identity.serviceId())) {
                throw new SecurityException("Unexpected identity authority workload");
            }
        } catch (SecurityException | IllegalArgumentException invalidWorkload) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "INVALID_WORKLOAD_IDENTITY", invalidWorkload);
        }
    }

    public record TombstoneRequest(@NotBlank String assertion) {}
}
