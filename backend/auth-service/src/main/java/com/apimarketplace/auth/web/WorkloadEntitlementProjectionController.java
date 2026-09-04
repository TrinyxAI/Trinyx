package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.EntitlementProjectionService;
import com.apimarketplace.auth.service.WorkloadAuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/entitlement-projections")
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "external-paid-monolith")
public class WorkloadEntitlementProjectionController {

    private static final String PAID_AUTHORITY = "trinyx-paid-authority";

    private final EntitlementProjectionService projections;
    private final WorkloadAuthenticationService workloads;

    public WorkloadEntitlementProjectionController(
            EntitlementProjectionService projections,
            WorkloadAuthenticationService workloads) {
        this.projections = projections;
        this.workloads = workloads;
    }

    @PutMapping
    public EntitlementProjectionService.ApplyResult apply(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ProjectionRequest request) {
        authenticate(authorization);
        try {
            return projections.apply(request.assertion());
        } catch (IllegalArgumentException invalidAssertion) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_ENTITLEMENT_ASSERTION", invalidAssertion);
        }
    }

    private void authenticate(String authorization) {
        try {
            var identity = workloads.authenticate(authorization,
                    "trinyx-paid-authority", "trinyx-cloud-internal");
            if (!PAID_AUTHORITY.equals(identity.serviceId())) {
                throw new SecurityException("Unexpected entitlement projection workload");
            }
        } catch (SecurityException | IllegalArgumentException invalidWorkload) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "INVALID_WORKLOAD_IDENTITY", invalidWorkload);
        }
    }

    public record ProjectionRequest(@NotBlank String assertion) {}
}
