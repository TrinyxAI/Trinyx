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
        var identity = workloads.authenticate(authorization,
                "trinyx-paid-authority", "trinyx-cloud-internal");
        if (!PAID_AUTHORITY.equals(identity.serviceId())) {
            throw new SecurityException("Unexpected entitlement projection workload");
        }
        return projections.apply(request.assertion());
    }

    public record ProjectionRequest(@NotBlank String assertion) {}
}
