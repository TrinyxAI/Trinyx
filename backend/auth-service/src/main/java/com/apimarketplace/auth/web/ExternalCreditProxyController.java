package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ExternalCreditProxyService;
import com.apimarketplace.auth.service.ModelPricingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.math.BigDecimal;
import java.math.RoundingMode;

@RestController
@RequestMapping("/api/internal/cloud-credit-proxy")
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "external-paid-monolith")
public class ExternalCreditProxyController {

    private final ExternalCreditProxyService proxy;
    private final ModelPricingService pricing;

    public ExternalCreditProxyController(ExternalCreditProxyService proxy,
                                         ModelPricingService pricing) {
        this.proxy = proxy;
        this.pricing = pricing;
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
            @Valid @RequestBody LlmReserveCommand command) {
        if (!pricing.hasPricing(command.provider(), command.model())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "MODEL_PRICING_UNKNOWN");
        }
        int prompt = Math.max(0, command.estimatedPromptTokens());
        int completion = Math.max(0, command.maximumCompletionTokens());
        BigDecimal estimated = pricing.calculateCost(
                command.provider(), command.model(), prompt, completion);
        BigDecimal maximum = estimated.multiply(new BigDecimal("1.25"))
                .setScale(6, RoundingMode.UP);
        if (maximum.signum() <= 0) {
            maximum = new BigDecimal("0.000001");
        }
        return proxy.reserve(new ExternalCreditProxyService.Context(
                principalId, billingSubjectId, organizationId, installId),
                new ExternalCreditProxyService.ReserveCommand(
                        command.operationId(), command.feature(), command.sourceType(),
                        estimated, maximum, command.provider(), command.model()));
    }


    @PostMapping("/{operationId}/commit-llm")
    public ResponseEntity<ExternalCreditProxyService.SettlementResult> commitLlm(
            @PathVariable UUID operationId,
            @Valid @RequestBody LlmCommitCommand command) {
        if (!pricing.hasPricing(command.provider(), command.model())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "MODEL_PRICING_UNKNOWN_AT_SETTLEMENT");
        }
        BigDecimal actual = pricing.calculateCost(command.provider(), command.model(),
                Math.max(0, command.promptTokens()), Math.max(0, command.completionTokens()));
        var result = proxy.commit(operationId, new ExternalCreditProxyService.CommitCommand(
                actual, command.provider(), command.model(), command.providerRequestId(),
                (long) Math.max(0, command.promptTokens()),
                (long) Math.max(0, command.completionTokens()), command.requestHash()));
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
    public record LlmReserveCommand(
            @NotNull UUID operationId,
            String feature,
            String sourceType,
            String provider,
            String model,
            int estimatedPromptTokens,
            int maximumCompletionTokens) {}

    public record LlmCommitCommand(
            String provider,
            String model,
            String providerRequestId,
            int promptTokens,
            int completionTokens,
            String requestHash) {}
}
