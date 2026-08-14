package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * "Delete my account" must remove the identity too, or not run at all.
 *
 * <p>The Keycloak delete used to be best-effort: a missing admin secret, or any HTTP failure,
 * logged a warning and the purge carried on. That is the state production was in - the secret
 * was never provisioned on the auth deployment - so the nightly purge would have destroyed a
 * user's data while leaving their identity in the realm. Two bad outcomes at once: their e-mail
 * address stays on file after a deletion advertised as permanent, and signing in again
 * bootstraps them a brand-new account, so the deletion removed their work but not their access.
 *
 * <p>Aborting is the safe direction: the row stays queued and the next nightly pass retries.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Account purge - identity removal is mandatory")
class AccountPurgeIdentityTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private StripeBillingService stripeBillingService;
    @Mock private RestTemplate restTemplate;
    @Mock private WorkspaceDataPurger workspaceDataPurger;
    @Mock private EntityManager em;

    private AccountPurgeService service;
    private User user;

    private static final Long USER_ID = 42L;
    private static final String PROVIDER_ID = "b6cd2ba7-5d6b-49f5-bc9c-ada1dc5f2074";

    @BeforeEach
    void setUp() {
        service = new AccountPurgeService(userRepository, organizationRepository,
                Optional.of(stripeBillingService), restTemplate, workspaceDataPurger);
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "authMode", "keycloak");
        ReflectionTestUtils.setField(service, "kcRealm", "livecontext");
        ReflectionTestUtils.setField(service, "kcClientId", "livecontext-admin-api");

        user = new User();
        user.setId(USER_ID);
        user.setEmail("gone@test.local");
        user.setEnabled(false);
        user.setDeactivatedAt(java.time.LocalDateTime.now().minusDays(40));
        user.setProviderId(PROVIDER_ID);
        lenient().when(em.find(eq(User.class), eq(USER_ID), any(LockModeType.class))).thenReturn(user);
    }

    @Test
    @DisplayName("aborts the purge when the Keycloak admin secret is missing")
    void abortsWhenKeycloakSecretMissing() {
        ReflectionTestUtils.setField(service, "kcServerUrl", "http://kc:8080");
        ReflectionTestUtils.setField(service, "kcClientSecret", ""); // exactly production's state

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Keycloak admin credentials are not configured");

        // Nothing may have been destroyed: the whole point is that a half-deletion is worse
        // than a deletion deferred by one night.
        verifyNoInteractions(workspaceDataPurger);
        verify(em, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("aborts the purge when the Keycloak admin token cannot be obtained")
    void abortsWhenKeycloakTokenFails() {
        ReflectionTestUtils.setField(service, "kcServerUrl", "http://kc:8080");
        ReflectionTestUtils.setField(service, "kcClientSecret", "s3cret");
        when(restTemplate.exchange(anyString(), any(), any(), eq(java.util.Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("kc unreachable"));

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aborting the purge");

        verifyNoInteractions(workspaceDataPurger);
    }

    @Test
    @DisplayName("aborts the purge when the Keycloak DELETE itself fails")
    void abortsWhenKeycloakDeleteFails() {
        // Distinct from the token failure above: the token call succeeds and it is the identity
        // deletion that fails (500, timeout). This is the branch that decides whether a Keycloak
        // outage can downgrade "delete my account" to "delete my data, keep my login".
        ReflectionTestUtils.setField(service, "kcServerUrl", "http://kc:8080");
        ReflectionTestUtils.setField(service, "kcClientSecret", "s3cret");
        when(restTemplate.exchange(anyString(), eq(org.springframework.http.HttpMethod.POST), any(), eq(java.util.Map.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(java.util.Map.of("access_token", "t")));
        when(restTemplate.exchange(anyString(), eq(org.springframework.http.HttpMethod.DELETE), any(), eq(Void.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("kc unreachable"));

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aborting the purge");

        verifyNoInteractions(workspaceDataPurger);
        verify(em, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("aborts when the user has no provider_id under keycloak auth")
    void abortsWhenProviderIdMissing() {
        // The identity exists, we have merely lost the handle to it. Purging anyway destroys the
        // data and leaves a realm identity keyed by the same e-mail, so signing in again
        // bootstraps a fresh account: the deletion removes their work but not their access.
        ReflectionTestUtils.setField(service, "kcServerUrl", "http://kc:8080");
        ReflectionTestUtils.setField(service, "kcClientSecret", "s3cret");
        user.setProviderId("  ");

        assertThatThrownBy(() -> service.purgeUser(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no provider_id");

        verifyNoInteractions(workspaceDataPurger, restTemplate);
        verify(em, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("a Keycloak 404 means the identity is already gone, so the purge proceeds")
    void proceedsWhenKeycloakUserAlreadyAbsent() {
        ReflectionTestUtils.setField(service, "kcServerUrl", "http://kc:8080");
        ReflectionTestUtils.setField(service, "kcClientSecret", "s3cret");
        when(restTemplate.exchange(anyString(), eq(org.springframework.http.HttpMethod.POST), any(), eq(java.util.Map.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(java.util.Map.of("access_token", "t")));
        when(restTemplate.exchange(anyString(), eq(org.springframework.http.HttpMethod.DELETE), any(), eq(Void.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.NotFound.create(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        when(organizationRepository.findByOwnerId(USER_ID)).thenReturn(java.util.List.of());
        when(em.createNativeQuery(anyString())).thenReturn(mock(jakarta.persistence.Query.class, RETURNS_DEEP_STUBS));

        // Already absent is the state we want; refusing here would strand the account forever.
        assertThatCode(() -> service.purgeUser(USER_ID)).doesNotThrowAnyException();

        // And it must actually purge: swallowing the 404 and returning early would leave the row
        // untouched while reporting success, which reads identically in the logs.
        verify(em, atLeastOnce()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("embedded (CE) auth has no external identity, so the purge is not blocked")
    void ceModeIsNotBlocked() {
        ReflectionTestUtils.setField(service, "authMode", "embedded");
        ReflectionTestUtils.setField(service, "kcServerUrl", "");
        ReflectionTestUtils.setField(service, "kcClientSecret", "");
        when(organizationRepository.findByOwnerId(USER_ID)).thenReturn(java.util.List.of());
        when(em.createNativeQuery(anyString())).thenReturn(mock(jakarta.persistence.Query.class, RETURNS_DEEP_STUBS));

        assertThatCode(() -> service.purgeUser(USER_ID)).doesNotThrowAnyException();
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("an account that was restored in the meantime is never purged")
    void restoredAccountIsSkipped() {
        user.setEnabled(true);
        user.setDeactivatedAt(null);

        org.assertj.core.api.Assertions.assertThat(service.purgeUser(USER_ID))
                .as("a restored account must never be purged")
                .isFalse();
        verifyNoInteractions(restTemplate, workspaceDataPurger);
    }
}
