package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/internal/credentials/scopes} - the OAuth-scope preflight catalog-service
 * calls before executing a tool ({@code HttpExecutionService.preflightScopeCheck}).
 *
 * <p>These tests exist for one reason: the endpoint must resolve through
 * {@link CredentialService#findByNameIdentifyingIntegration}, NOT the raw by-name lookup.
 * {@code name} here is a requirement SLUG, while a credential's name is free text a user
 * typed, so a raw match answers the preflight with whatever row happens to carry that label
 * and compares one provider's granted scopes against another provider's requirement.
 *
 * <p>It is also the one caller of that lookup with NO integration fallback: what the filter
 * rejects becomes a 404, and the catalog side then fails OPEN (a missing credential means
 * "skip the check"). So a regression here does not raise an error anywhere, it just quietly
 * stops enforcing scopes. Nothing else in the suite would notice a one-line revert to the
 * unfiltered lookup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCredentialController GET /scopes")
class InternalCredentialControllerScopesTest {

    @Mock
    private InternalCredentialService credentialService;

    @Mock
    private CredentialService userCredentialService;

    @Mock
    private PlatformCredentialService platformCredentialService;

    @Mock
    private PlatformCredentialPricingService pricingService;

    @Mock
    private PricingVersionService pricingVersionService;

    @Mock
    private CredentialEncryptionService encryptionService;

    @InjectMocks
    private InternalCredentialController controller;

    @Test
    @DisplayName("resolves through the integration-filtered lookup, never the raw by-name one")
    void usesTheFilteredLookup() {
        when(userCredentialService.findByNameIdentifyingIntegration("user-1", "gmail"))
                .thenReturn(Optional.of(credential("gmail", "gmail", CredentialType.OAuth2,
                        List.of("https://mail.google.com/"))));

        ResponseEntity<Map<String, Object>> response = controller.getCredentialScopes("user-1", "gmail");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("integration", "gmail");
        assertThat(response.getBody()).containsEntry("scopes", List.of("https://mail.google.com/"));
        // The raw lookup would answer for a credential of any provider; this endpoint must
        // never reach it. (The method no longer exists on the service, so this verify also
        // documents intent for whoever is tempted to add it back.)
        verify(userCredentialService).findByNameIdentifyingIntegration("user-1", "gmail");
    }

    @Test
    @DisplayName("404s when the name belongs to a credential of another provider")
    void mislabelledCredentialDoesNotAnswer() {
        // A Slack key a user happened to name "elevenlabs". The filtered lookup rejects it,
        // so the preflight must find nothing rather than compare Slack's granted scopes
        // against ElevenLabs' requirement and reach a verdict about the wrong account.
        when(userCredentialService.findByNameIdentifyingIntegration("user-1", "elevenlabs"))
                .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response =
                controller.getCredentialScopes("user-1", "elevenlabs");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("reports null scopes for a non-OAuth2 credential rather than an empty list")
    void nonOauth2CredentialReportsNullScopes() {
        // An empty list would read as "this credential was granted zero scopes" and fail the
        // preflight; null is what tells the caller the scope concept does not apply.
        when(userCredentialService.findByNameIdentifyingIntegration("user-1", "smtp"))
                .thenReturn(Optional.of(credential("smtp", "", CredentialType.API_Key, List.of())));

        ResponseEntity<Map<String, Object>> response = controller.getCredentialScopes("user-1", "smtp");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("type", "API_Key");
        assertThat(response.getBody()).containsEntry("scopes", null);
    }

    @Test
    @DisplayName("carries no secret: the body names the credential without exposing its data")
    void bodyCarriesNoSecret() {
        when(userCredentialService.findByNameIdentifyingIntegration("user-1", "gmail"))
                .thenReturn(Optional.of(credential("gmail", "gmail", CredentialType.OAuth2,
                        List.of("email"))));

        ResponseEntity<Map<String, Object>> response = controller.getCredentialScopes("user-1", "gmail");

        assertThat(response.getBody()).containsOnlyKeys("type", "scopes", "integration", "name");
        assertThat(response.getBody().toString()).doesNotContain("super-secret");
        verify(encryptionService, never()).decrypt(org.mockito.ArgumentMatchers.anyString());
    }

    private Credential credential(String name, String integration, CredentialType type,
                                  List<String> scopes) {
        return new Credential(
                42L,
                "user-1",
                "org-1",
                name,
                integration,
                type,
                CredentialEnvironment.Production,
                CredentialStatus.active,
                "Test credential",
                Map.of("access_token", "super-secret"),
                scopes,
                List.of(),
                "user-1",
                "icon",
                true,
                null,
                Instant.parse("2026-05-04T10:00:00Z"),
                Instant.parse("2026-05-05T10:00:00Z"));
    }
}
