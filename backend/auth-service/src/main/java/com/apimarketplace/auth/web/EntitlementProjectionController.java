package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.EntitlementProjectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Internal S2S endpoint; the edge gateway never exposes /api/internal/**. */
@RestController
@RequestMapping("/api/internal/v1/entitlement-projections")
public class EntitlementProjectionController {

    private final EntitlementProjectionService projections;

    public EntitlementProjectionController(EntitlementProjectionService projections) {
        this.projections = projections;
    }

    @PutMapping
    public EntitlementProjectionService.ApplyResult apply(@Valid @RequestBody ProjectionRequest request) {
        return projections.apply(request.assertion());
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

    public record ProjectionRequest(@NotBlank String assertion) {}
}
