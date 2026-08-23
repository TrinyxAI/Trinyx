package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ExternalCreditProxyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.math.BigDecimal;

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


    @PostMapping("/reserve-llm")
    public ExternalCreditProxyService.ReserveResult reserveLlm(
            @RequestHeader("X-Principal-ID") UUID principalId,
            @RequestHeader("X-Billing-Subject-ID") UUID billingSubjectId,
            @RequestHeader("X-Organization-ID") UUID organizationId,
            @RequestHeader("X-Install-ID") UUID installId,
            @Valid @RequestBody ExternalCreditProxyService.LlmReserveCommand command) {
        return proxy.reserveLlm(new ExternalCreditProxyService.Context(
                principalId, billingSubjectId, organizationId, installId), command);
    }

    @PostMapping("/{operationId}/commit-llm")
    public ResponseEntity<ExternalCreditProxyService.SettlementResult> commitLlm(
            @PathVariable UUID operationId,
            @Valid @RequestBody ExternalCreditProxyService.LlmCommitCommand command) {
        var result = proxy.commitLlm(operationId, command);
        return result.queued() ? ResponseEntity.accepted().body(result)
                : ResponseEntity.ok(result);
    }

    @PostMapping("/{operationId}/commit-amount")
    public ResponseEntity<ExternalCreditProxyService.SettlementResult> commitAmount(
            @PathVariable UUID operationId,
            @Valid @RequestBody AmountCommitCommand command) {
        String requestHash = proxy.requestHash(operationId);
        var result = proxy.commit(operationId, new ExternalCreditProxyService.CommitCommand(
                command.actualCredits(), command.provider(), command.model(),
                command.providerRequestId(), null, null, requestHash));
        return result.queued() ? ResponseEntity.accepted().body(result)
                : ResponseEntity.ok(result);
    }

    @PostMapping("/{operationId}/release-local")
    public ResponseEntity<ExternalCreditProxyService.SettlementResult> releaseLocal(
            @PathVariable UUID operationId,
            @RequestBody(required = false) ReleaseLocalCommand command) {
        String requestHash = proxy.requestHash(operationId);
        var result = proxy.release(operationId, new ExternalCreditProxyService.ReleaseCommand(
                command == null ? "provider-not-called" : command.reason(), requestHash));
        return result.queued() ? ResponseEntity.accepted().body(result)
                : ResponseEntity.ok(result);
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
    public record AmountCommitCommand(
            @NotNull BigDecimal actualCredits,
            String provider,
            String model,
            String providerRequestId) {}

    public record ReleaseLocalCommand(String reason) {}
}
