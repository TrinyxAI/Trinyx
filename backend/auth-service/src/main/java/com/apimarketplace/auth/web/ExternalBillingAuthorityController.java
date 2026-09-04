package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ExternalBillingAuthorityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.UUID;

/** Paid-monolith API used by the linked installation to obtain short-lived signed Cloud state. */
@RestController
@ConditionalOnProperty(name = "billing.authority.mode", havingValue = "paid-monolith-authority", matchIfMissing = false)
@RequestMapping("/api/cloud-authority/v2")
public class ExternalBillingAuthorityController {

    private final ExternalBillingAuthorityService authority;

    public ExternalBillingAuthorityController(ExternalBillingAuthorityService authority) {
        this.authority = authority;
    }

    @PostMapping("/link")
    public ExternalBillingAuthorityService.AuthorityBundle link(
            @RequestHeader("X-User-ID") long userId,
            @Valid @RequestBody LinkRequest request) {
        return authority.issue(userId, request.installId(), request.organizationId(),
                request.keycloakSubject());
    }

    @PostMapping("/entitlements/refresh")
    public ExternalBillingAuthorityService.Projection refresh(
            @RequestHeader("X-User-ID") long userId,
            @Valid @RequestBody RefreshRequest request) {
        return authority.refresh(userId, request.installId(), request.organizationId());
    }

    @PostMapping("/revoke")
    public ExternalBillingAuthorityService.Projection revoke(
            @RequestHeader("X-User-ID") long userId,
            @Valid @RequestBody RefreshRequest request) {
        return authority.revoke(userId, request.installId(), request.organizationId());
    }

    public record LinkRequest(@NotNull UUID installId, @NotNull UUID organizationId,
                              @NotBlank String keycloakSubject) {}
    public record RefreshRequest(@NotNull UUID installId, @NotNull UUID organizationId) {}
}
