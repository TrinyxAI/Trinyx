package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CloudCreditAuthorityService;
import com.apimarketplace.auth.service.WorkloadAuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Paid-monolith-only authority surface. It is never routed through the public Cloud edge. */
@RestController
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
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody CloudCreditAuthorityService.ReserveRequest request) {
        authenticate(authorization);
        return authority.reserve(request);
    }

    @PostMapping("/{operationId}/commit")
    public CloudCreditAuthorityService.SettlementResponse commit(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable UUID operationId,
            @Valid @RequestBody CloudCreditAuthorityService.CommitRequest request) {
        authenticate(authorization);
        return authority.commit(operationId, request);
    }

    @PostMapping("/{operationId}/release")
    public CloudCreditAuthorityService.SettlementResponse release(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable UUID operationId,
            @Valid @RequestBody CloudCreditAuthorityService.ReleaseRequest request) {
        authenticate(authorization);
        return authority.release(operationId, request);
    }

    private void authenticate(String authorization) {
        try {
            var workload = workloadAuthentication.authenticate(authorization);
            if (!"trinyx-cloud-runtime".equals(workload.serviceId())) {
                throw new SecurityException("Workload is not allowed to access the wallet");
            }
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_WORKLOAD_IDENTITY", e);
        }
    }
}
