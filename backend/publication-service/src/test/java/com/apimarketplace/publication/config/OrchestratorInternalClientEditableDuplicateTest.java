package com.apimarketplace.publication.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins how {@link OrchestratorInternalClient#findEditableDuplicate} reads a failure.
 *
 * <p>This lookup is the idempotence of "create my editable copy", and a copy re-clones the
 * application's WHOLE resource set. Answering "you have no copy" on anything other than a
 * real 404 therefore clones a second set of interfaces, tables and agents behind the
 * user's back - the exact duplication the feature removed, reintroduced through the error
 * path. Only 404 may mean "none"; everything else must refuse.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrchestratorInternalClient.findEditableDuplicate - only a 404 means 'no copy'")
class OrchestratorInternalClientEditableDuplicateTest {

    @Mock private RestTemplate restTemplate;
    @Captor private ArgumentCaptor<String> urlCaptor;
    @Captor private ArgumentCaptor<HttpEntity<Void>> entityCaptor;

    private OrchestratorInternalClient client;

    private static final UUID APP_CLONE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @BeforeEach
    void setUp() {
        client = new OrchestratorInternalClient(restTemplate, "http://orchestrator:8099");
    }

    @Test
    @DisplayName("Returns the copy the orchestrator reports, and scopes the request to the caller's workspace")
    void returnsTheExistingCopy() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("id", "wf-copy")));

        Map<String, Object> found = client.findEditableDuplicate(APP_CLONE, "buyer-7", "org-buyer", null);

        assertThat(found).containsEntry("id", "wf-copy");
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), entityCaptor.capture(),
                any(ParameterizedTypeReference.class));
        assertThat(urlCaptor.getValue())
                .contains("applicationWorkflowId=" + APP_CLONE)
                .contains("tenantId=buyer-7")
                // The endpoint REQUIRES the org; omitting it would 400, which is now an
                // error rather than a silent "no copy".
                .contains("organizationId=org-buyer");
        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst("X-User-ID")).isEqualTo("buyer-7");
        assertThat(headers.getFirst("X-Organization-ID")).isEqualTo("org-buyer");
    }

    @Test
    @DisplayName("404 means the user genuinely has no copy yet")
    void notFoundMeansNoCopy() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(), new byte[0], null));

        assertThat(client.findEditableDuplicate(APP_CLONE, "buyer-7", "org-buyer", null)).isNull();
    }

    @Test
    @DisplayName("A 5xx REFUSES instead of answering 'no copy' - that answer would clone a second resource set")
    void serverErrorPropagates() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "boom", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> client.findEditableDuplicate(APP_CLONE, "buyer-7", "org-buyer", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing editable copy");
    }

    @Test
    @DisplayName("A transport failure (orchestrator unreachable) refuses too")
    void transportFailurePropagates() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> client.findEditableDuplicate(APP_CLONE, "buyer-7", "org-buyer", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("findBySourcePublicationStrict answers 404 as 'not installed' and refuses on anything else")
    void strictApplicationLookupSeparatesNotInstalledFromFailure() {
        // Its lenient sibling answers null on ANY failure, which is right for callers where
        // absent == not acquired. Here a null becomes "Application is not installed in this
        // workspace", so a blip would tell an owner they do not own their app.
        UUID publicationId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("id", "app-clone")))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(), new byte[0], null))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "boom", new HttpHeaders(), new byte[0], null));

        assertThat(client.findBySourcePublicationStrict(publicationId, "buyer-7", "org-buyer"))
                .containsEntry("id", "app-clone");
        assertThat(client.findBySourcePublicationStrict(publicationId, "buyer-7", "org-buyer")).isNull();
        assertThatThrownBy(() -> client.findBySourcePublicationStrict(publicationId, "buyer-7", "org-buyer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whether the application is installed");

        // The endpoint requires the org, so it is always sent (the lenient sibling omits a blank one).
        verify(restTemplate, org.mockito.Mockito.atLeastOnce()).exchange(urlCaptor.capture(),
                eq(HttpMethod.GET), any(), any(ParameterizedTypeReference.class));
        assertThat(urlCaptor.getValue()).contains("organizationId=org-buyer");
    }

    @Test
    @DisplayName("A 400 (e.g. a missing org) refuses as well, rather than degrading to 'no copy'")
    void badRequestPropagates() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> client.findEditableDuplicate(APP_CLONE, "buyer-7", "org-buyer", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
