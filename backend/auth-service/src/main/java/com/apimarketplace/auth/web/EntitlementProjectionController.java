package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.EntitlementProjectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Internal S2S endpoint; the edge gateway never exposes /api/internal/**. */
@RestController
@ConditionalOnProperty(name = "billing.authority.mode", havingValue = "external-paid-monolith", matchIfMissing = false)
@RequestMapping("/api/internal/v1/entitlement-projections")
public class EntitlementProjectionController {

    private final EntitlementProjectionService projections;

    public EntitlementProjectionController(EntitlementProjectionService projections) {
        this.projections = projections;
    }

    @GetMapping("/decision")
    public EntitlementProjectionService.Decision decision(
            @RequestParam UUID installId,
            @RequestParam UUID organizationId,
            @RequestParam UUID billingSubjectId,
            @RequestParam(required = false) String feature,
            @RequestParam(defaultValue = "false") boolean paidOperation) {
        return projections.authorize(installId, organizationId, billingSubjectId, feature, paidOperation);
    }
}
