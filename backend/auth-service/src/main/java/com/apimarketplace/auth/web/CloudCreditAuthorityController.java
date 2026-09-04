package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CloudCreditAuthorityService;
import com.apimarketplace.auth.service.WorkloadAuthenticationService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Paid-monolith-only authority surface. It is never routed through the public Cloud edge. */
@RestController
@ConditionalOnProperty(name = "billing.authority.mode", havingValue = "paid-monolith-authority", matchIfMissing = false)
@RequestMapping("/internal/v1/credit-reservations")
public class CloudCreditAuthorityController {

    private final CloudCreditAuthorityService authority;
    private final WorkloadAuthenticationService workloadAuthentication;

    public CloudCreditAuthorityController(CloudCreditAuthorityService authority,
                                          WorkloadAuthenticationService workloadAuthentication) {
        this.authority = authority;
        this.workloadAuthentication = workloadAuthentication;
    }

    @PostMapping
    public CloudCreditAuthorityService.ReserveResponse reserve(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @Valid @RequestBody CloudCreditAuthorityService.ReserveRequest request) {
        authenticate(authorization);
        return authority.reserve(request);
    }

    @PostMapping("/{operationId}/dispatching")
    public CloudCreditAuthorityService.SettlementResponse dispatching(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @PathVariable UUID operationId,
            @Valid @RequestBody CloudCreditAuthorityService.DispatchingRequest request) {
        authenticate(authorization);
        return authority.dispatching(operationId, request);
    }

    @PostMapping("/{operationId}/commit")
    public CloudCreditAuthorityService.SettlementResponse commit(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @PathVariable UUID operationId,
            @Valid @RequestBody CloudCreditAuthorityService.CommitRequest request) {
        authenticate(authorization);
        return authority.commit(operationId, request);
    }

    @PostMapping("/{operationId}/release")
    public CloudCreditAuthorityService.SettlementResponse release(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @PathVariable UUID operationId,
            @Valid @RequestBody CloudCreditAuthorityService.ReleaseRequest request) {
        authenticate(authorization);
        return authority.release(operationId, request);
    }

    @PostMapping("/{operationId}/outcome-unknown")
    public CloudCreditAuthorityService.SettlementResponse outcomeUnknown(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @PathVariable UUID operationId,
            @Valid @RequestBody CloudCreditAuthorityService.OutcomeUnknownRequest request) {
        authenticate(authorization);
        return authority.outcomeUnknown(operationId, request);
    }

    private void authenticate(String authorization) {
        try {
            var workload = workloadAuthentication.authenticate(authorization);
            if (!"trinyx-cloud-runtime".equals(workload.serviceId())) {
                throw new WorkloadAuthenticationService.WorkloadAuthenticationException(
                        "Workload is not allowed to access the wallet");
            }
        } catch (WorkloadAuthenticationService.WorkloadAuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_WORKLOAD_IDENTITY", e);
        }
    }
}
