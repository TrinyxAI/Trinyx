package com.apimarketplace.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Cloud-side transport to the sole paid-monolith wallet authority.
 * Browser JWTs and gateway HMAC credentials are never reused for this hop.
 */
@Service
public class PaidMonolithCreditClient {

    private static final String SERVICE_ID = "trinyx-cloud-runtime";

    private final RestTemplate http;
    private final WorkloadAuthenticationService workloads;
    private final String baseUrl;

    public PaidMonolithCreditClient(
            RestTemplateBuilder builder,
            WorkloadAuthenticationService workloads,
            @Value("${paid-monolith.billing-url:https://billing-internal.trinyx.private}") String baseUrl) {
        this.http = builder.connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15)).build();
        this.workloads = workloads;
        this.baseUrl = strip(baseUrl);
    }

    public CloudCreditAuthorityService.ReserveResponse reserve(
            CloudCreditAuthorityService.ReserveRequest request) {
        return exchange("/internal/v1/credit-reservations", HttpMethod.POST, request,
                CloudCreditAuthorityService.ReserveResponse.class);
    }

    public CloudCreditAuthorityService.SettlementResponse dispatching(
            UUID operationId, CloudCreditAuthorityService.DispatchingRequest request) {
        return exchange("/internal/v1/credit-reservations/" + operationId + "/dispatching",
                HttpMethod.POST, request, CloudCreditAuthorityService.SettlementResponse.class);
    }

    public CloudCreditAuthorityService.SettlementResponse commit(
            UUID operationId, CloudCreditAuthorityService.CommitRequest request) {
        return exchange("/internal/v1/credit-reservations/" + operationId + "/commit",
                HttpMethod.POST, request, CloudCreditAuthorityService.SettlementResponse.class);
    }

    public CloudCreditAuthorityService.SettlementResponse release(
            UUID operationId, CloudCreditAuthorityService.ReleaseRequest request) {
        return exchange("/internal/v1/credit-reservations/" + operationId + "/release",
                HttpMethod.POST, request, CloudCreditAuthorityService.SettlementResponse.class);
    }

    public CloudCreditAuthorityService.SettlementResponse outcomeUnknown(
            UUID operationId, CloudCreditAuthorityService.OutcomeUnknownRequest request) {
        return exchange("/internal/v1/credit-reservations/" + operationId
                        + "/outcome-unknown",
                HttpMethod.POST, request,
                CloudCreditAuthorityService.SettlementResponse.class);
    }

    private <T> T exchange(String path, HttpMethod method, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(workloads.issue(SERVICE_ID));
        try {
            ResponseEntity<T> response = http.exchange(baseUrl + path, method,
                    new HttpEntity<>(body, headers), responseType);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RetryableAuthorityException(
                        "Billing authority returned an empty/non-success response", null);
            }
            return response.getBody();
        } catch (RestClientResponseException responseFailure) {
            int status = responseFailure.getStatusCode().value();
            if (responseFailure.getStatusCode().is4xxClientError()
                    && status != HttpStatus.REQUEST_TIMEOUT.value()
                    && status != HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new PermanentAuthorityException(status,
                        responseFailure.getResponseBodyAsString(), responseFailure);
            }
            throw new RetryableAuthorityException(
                    "Billing authority returned " + responseFailure.getStatusCode().value(),
                    responseFailure);
        } catch (ResourceAccessException transportFailure) {
            throw new RetryableAuthorityException(
                    "Billing authority transport unavailable", transportFailure);
        } catch (RestClientException protocolFailure) {
            throw new RetryableAuthorityException(
                    "Billing authority protocol failure", protocolFailure);
        }
    }

    public static final class PermanentAuthorityException extends RuntimeException {
        private final int statusCode;

        public PermanentAuthorityException(int statusCode, String responseBody, Throwable cause) {
            super("Permanent billing authority rejection " + statusCode
                    + (responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody),
                    cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public static final class RetryableAuthorityException extends RuntimeException {
        public RetryableAuthorityException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String strip(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("paid-monolith.billing-url is required");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
