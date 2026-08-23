package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ExternalCreditProxyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/internal/cloud-credit-proxy")
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "external-paid-monolith")
public class ExternalCreditProxyController {

    private final ExternalCreditProxyService proxy;

    public ExternalCreditProxyController(ExternalCreditProxyService proxy) {
        this.proxy = proxy;
    }

    @PostMapping("/reserve")
    public ExternalCreditProxyService.ReserveResult reserve(
            @RequestHeader("X-Principal-ID") UUID principalId,
            @RequestHeader("X-Billing-Subject-ID") UUID billingSubjectId,
            @RequestHeader("X-Organization-ID") UUID organizationId,
            @RequestHeader("X-Install-ID") UUID installId,
            @Valid @RequestBody ExternalCreditProxyService.ReserveCommand command) {
        return proxy.reserve(new ExternalCreditProxyService.Context(
                principalId, billingSubjectId, organizationId, installId), command);
    }

    @PostMapping("/{operationId}/commit")
    public ResponseEntity<ExternalCreditProxyService.SettlementResult> commit(
            @PathVariable UUID operationId,
            @Valid @RequestBody ExternalCreditProxyService.CommitCommand command) {
        var result = proxy.commit(operationId, command);
        return result.queued() ? ResponseEntity.accepted().body(result)
                : ResponseEntity.ok(result);
    }

    @PostMapping("/{operationId}/release")
    public ResponseEntity<ExternalCreditProxyService.SettlementResult> release(
            @PathVariable UUID operationId,
            @Valid @RequestBody ExternalCreditProxyService.ReleaseCommand command) {
        var result = proxy.release(operationId, command);
        return result.queued() ? ResponseEntity.accepted().body(result)
                : ResponseEntity.ok(result);
    }
}
