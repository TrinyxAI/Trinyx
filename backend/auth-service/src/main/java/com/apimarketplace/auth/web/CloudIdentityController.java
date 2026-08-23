package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CloudIdentityBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** HMAC-authenticated internal identity materialization API. Never routed at the public edge. */
@RestController
@ConditionalOnProperty(name = "billing.authority.mode", havingValue = "external-paid-monolith", matchIfMissing = false)
@RequestMapping("/api/internal/cloud-identity")
public class CloudIdentityController {

    private final CloudIdentityBindingService bindings;

    public CloudIdentityController(CloudIdentityBindingService bindings) {
        this.bindings = bindings;
    }

    @GetMapping("/context")
    public CloudIdentityBindingService.BindingContext context(
            @RequestParam String keycloakSubject,
            @RequestHeader("X-Provider-ID") String signedProviderId) {
        if (!keycloakSubject.equals(signedProviderId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "SIGNED_SUBJECT_MISMATCH");
        }
        return bindings.context(keycloakSubject);
    }

    @PostMapping("/bind")
    public CloudIdentityBindingService.BindingContext bind(
            @Valid @RequestBody BindRequest request,
            @RequestHeader("X-Provider-ID") String keycloakSubject,
            @RequestHeader("X-User-ID") long cloudUserId) {
        return bindings.bind(request.identityBinding(), keycloakSubject, cloudUserId);
    }

    @PostMapping("/revoke")
    public ResponseEntity<Map<String, String>> revoke(
            @RequestParam UUID installId,
            @RequestParam UUID principalId,
            @RequestParam long bindingRevision) {
        bindings.revoke(installId, principalId, bindingRevision);
        return ResponseEntity.ok(Map.of("status", "REVOKED"));
    }

    public record BindRequest(@NotBlank String identityBinding) {}
}
