package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ExternalCreditProxyService;
import com.apimarketplace.auth.service.CloudCreditAuthorityService;
import com.apimarketplace.auth.service.CloudIdentityBindingService;
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
    private final CloudIdentityBindingService identities;

    public ExternalCreditProxyController(ExternalCreditProxyService proxy,
                                         CloudIdentityBindingService identities) {
        this.proxy = proxy;
        this.identities = identities;
    }

    @PostMapping("/reserve")
    public ExternalCreditProxyService.ReserveResult reserve(
            @RequestHeader("X-User-ID") long userId,
            @RequestHeader(value = "X-Principal-ID", required = false) UUID principalId,
            @RequestHeader(value = "X-Billing-Subject-ID", required = false) UUID billingSubjectId,
            @RequestHeader("X-Organization-ID") UUID organizationId,
            @RequestHeader(value = "X-Install-ID", required = false) UUID installId,
            @Valid @RequestBody ExternalCreditProxyService.ReserveCommand command) {
        return proxy.reserve(context(userId, principalId, billingSubjectId,
                organizationId, installId), command);
    }


    @PostMapping("/reserve-llm")
    public ExternalCreditProxyService.ReserveResult reserveLlm(
            @RequestHeader("X-User-ID") long userId,
            @RequestHeader(value = "X-Principal-ID", required = false) UUID principalId,
            @RequestHeader(value = "X-Billing-Subject-ID", required = false) UUID billingSubjectId,
            @RequestHeader("X-Organization-ID") UUID organizationId,
            @RequestHeader(value = "X-Install-ID", required = false) UUID installId,
            @Valid @RequestBody ExternalCreditProxyService.LlmReserveCommand command) {
        return proxy.reserveLlm(context(userId, principalId, billingSubjectId,
                organizationId, installId), command);
    }

    @PostMapping("/{operationId}/dispatching")
    public CloudCreditAuthorityService.SettlementResponse dispatching(
            @PathVariable UUID operationId,
            @Valid @RequestBody ExternalCreditProxyService.DispatchingCommand command) {
        return proxy.dispatching(operationId, command);
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

    @PostMapping("/{operationId}/outcome-unknown")
    public ResponseEntity<ExternalCreditProxyService.SettlementResult> outcomeUnknown(
            @PathVariable UUID operationId,
            @Valid @RequestBody OutcomeUnknownCommand command) {
        var result = proxy.outcomeUnknown(operationId,
                new ExternalCreditProxyService.OutcomeUnknownCommand(
                        command.reason(), command.requestHash(),
                        command.provider(), command.model()));
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
    private ExternalCreditProxyService.Context context(
            long userId, UUID principalId, UUID billingSubjectId,
            UUID organizationId, UUID installId) {
        boolean complete = principalId != null && billingSubjectId != null && installId != null;
        boolean empty = principalId == null && billingSubjectId == null && installId == null;
        if (!complete && !empty) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "PARTIAL_IDENTITY_CONTEXT");
        }
        if (complete) {
            return new ExternalCreditProxyService.Context(
                    principalId, billingSubjectId, organizationId, installId);
        }
        CloudIdentityBindingService.BindingContext binding =
                identities.context(userId, organizationId);
        return new ExternalCreditProxyService.Context(
                binding.principalId(), binding.billingSubjectId(),
                binding.organizationId(), binding.installId());
    }

    public record AmountCommitCommand(
            @NotNull BigDecimal actualCredits,
            String provider,
            String model,
            String providerRequestId) {}

    public record ReleaseLocalCommand(String reason) {}
    public record OutcomeUnknownCommand(String reason, String requestHash,
                                        String provider, String model) {}
}
