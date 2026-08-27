package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.domain.CredentialRenameRefusedException;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialService")
class CredentialServiceTest {

    @Mock private CredentialRepository credentialRepository;
    @Mock private StringRedisTemplate redisTemplate;

    private CredentialService service;

    @BeforeEach
    void setUp() {
        service = new CredentialService(credentialRepository, redisTemplate);
    }

    @Test
    @DisplayName("deleteCredentialForScope reassigns default to the most recent remaining credential")
    void deleteCredentialForScopeReassignsDefaultToMostRecentRemainingCredential() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential olderDefault = credential(2L, true, "2026-05-01T10:00:00Z");
        Credential mostRecent = credential(3L, false, "2026-05-03T10:00:00Z");
        Credential middle = credential(4L, false, "2026-05-02T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(olderDefault, middle, mostRecent));

        boolean result = service.deleteCredentialForScope(1L, "tenant-1", "org-1");

        assertThat(result).isTrue();
        verify(credentialRepository).deleteById(1L);
        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 3L);
    }

    @Test
    @DisplayName("deleteCredentialForScope leaves existing default when deleting a non-default credential")
    void deleteCredentialForScopeLeavesExistingDefaultWhenDeletingNonDefaultCredential() {
        Credential deletedNonDefault = credential(1L, false, "2026-05-04T10:00:00Z");
        Credential remainingDefault = credential(2L, true, "2026-05-01T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedNonDefault));
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(remainingDefault));

        boolean result = service.deleteCredentialForScope(1L, "tenant-1", "org-1");

        assertThat(result).isTrue();
        verify(credentialRepository).deleteById(1L);
        verify(credentialRepository, never()).setAsDefaultInScope("tenant-1", "org-1", 2L);
    }

    @Test
    @DisplayName("deleteCredentialForScope retries when a concurrent delete removes the fallback default")
    void deleteCredentialForScopeRetriesWhenConcurrentDeleteRemovesFallbackDefault() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential remainingFallback = credential(2L, false, "2026-05-01T10:00:00Z");
        Credential vanishedFallback = credential(3L, false, "2026-05-03T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        // The parallel request already deleted credential 3 by the time we look it up.
        when(credentialRepository.findById(3L)).thenReturn(Optional.empty());
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(remainingFallback, vanishedFallback))
                .thenReturn(List.of(remainingFallback));
        // PRODUCTION-FAITHFUL: setAsDefaultInScope raises a bare IllegalArgumentException,
        // but the @Repository proxy translates it into a Spring DataAccessException
        // (InvalidDataAccessApiUsageException) before the service sees it. The catch
        // must handle THIS wrapped type, not the bare IAE - a narrowed catch regresses.
        doThrow(new InvalidDataAccessApiUsageException(
                "Credential not found: 3", new IllegalArgumentException("Credential not found: 3")))
                .when(credentialRepository)
                .setAsDefaultInScope("tenant-1", "org-1", 3L);

        boolean result = service.deleteCredentialForScope(1L, "tenant-1", "org-1");

        assertThat(result).isTrue();
        verify(credentialRepository).deleteById(1L);
        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 3L);
        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 2L);
        // The retry path is only reached because the catch re-probes existence;
        // pre-fix code (no try/catch) never looks the vanished fallback up again.
        verify(credentialRepository).findById(3L);
    }

    @Test
    @DisplayName("deleteCredentialForScope stops without a default when every fallback vanishes concurrently")
    void deleteCredentialForScopeStopsWhenEveryFallbackVanishes() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential vanishedFallback = credential(2L, false, "2026-05-01T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        when(credentialRepository.findById(2L)).thenReturn(Optional.empty());
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(vanishedFallback));
        doThrow(new InvalidDataAccessApiUsageException(
                "Credential not found: 2", new IllegalArgumentException("Credential not found: 2")))
                .when(credentialRepository)
                .setAsDefaultInScope("tenant-1", "org-1", 2L);

        boolean result = service.deleteCredentialForScope(1L, "tenant-1", "org-1");

        assertThat(result).isTrue();
        verify(credentialRepository).deleteById(1L);
        // The only candidate vanished, so no default could be assigned; the loop
        // must terminate rather than retry the same vanished id forever. Exactly
        // two scope queries prove it: one that found the doomed fallback, one
        // that finds it filtered out and returns.
        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 2L);
        verify(credentialRepository).findById(2L);
        verify(credentialRepository, times(2))
                .findByScopeAndIntegration("tenant-1", "org-1", "gmail");
    }

    @Test
    @DisplayName("deleteCredentialForScope propagates an unrelated failure when the fallback still exists")
    void deleteCredentialForScopePropagatesUnrelatedFailureWhenFallbackStillExists() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential presentFallback = credential(2L, false, "2026-05-01T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        // The fallback is still present, so the failure is NOT a concurrent delete.
        when(credentialRepository.findById(2L)).thenReturn(Optional.of(presentFallback));
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(presentFallback));
        // An out-of-scope error is also surfaced as a translated DataAccessException.
        doThrow(new InvalidDataAccessApiUsageException(
                "Credential not in active org scope",
                new IllegalArgumentException("Credential not in active org scope")))
                .when(credentialRepository)
                .setAsDefaultInScope("tenant-1", "org-1", 2L);

        assertThatThrownBy(() -> service.deleteCredentialForScope(1L, "tenant-1", "org-1"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("Credential not in active org scope");

        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 2L);
        // The catch re-probes existence to decide rethrow-vs-skip; the present
        // fallback means the failure is real and must propagate. This is the load-
        // bearing distinction: skip only when the row is actually gone.
        verify(credentialRepository).findById(2L);
    }

    @Test
    @DisplayName("deleteCredentialForScope propagates a genuine infrastructure failure (fallback still present)")
    void deleteCredentialForScopePropagatesInfrastructureFailureWhenFallbackStillExists() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential fallback = credential(2L, false, "2026-05-01T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        // The re-probe finds the fallback alive, so the failure is NOT a concurrent delete.
        when(credentialRepository.findById(2L)).thenReturn(Optional.of(fallback));
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(fallback));
        // A real infrastructure failure (a DataAccessException that is NOT a vanished row).
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(credentialRepository)
                .setAsDefaultInScope("tenant-1", "org-1", 2L);

        assertThatThrownBy(() -> service.deleteCredentialForScope(1L, "tenant-1", "org-1"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("db down");

        // Existence re-probe runs (the catch is type-agnostic); the row is present,
        // so the error is genuine and must propagate - never silently swallowed.
        verify(credentialRepository).findById(2L);
    }

    @Test
    @DisplayName("findActiveIntegrationsForScope returns the org-wide set when an organization is supplied")
    void findActiveIntegrationsForScopeUsesOrgScope() {
        when(credentialRepository.findActiveIntegrationsByOrganizationId("org-1"))
                .thenReturn(java.util.Set.of("twitter", "gmail"));

        java.util.Set<String> result = service.findActiveIntegrationsForScope("tenant-1", "org-1");

        assertThat(result).containsExactlyInAnyOrder("twitter", "gmail");
        verify(credentialRepository, never()).findActiveIntegrationsByTenantId(anyString());
    }

    @Test
    @DisplayName("findActiveIntegrationsForScope falls back to tenant scope when no organization is supplied")
    void findActiveIntegrationsForScopeFallsBackToTenant() {
        when(credentialRepository.findActiveIntegrationsByTenantId("tenant-1"))
                .thenReturn(java.util.Set.of("slack"));

        java.util.Set<String> result = service.findActiveIntegrationsForScope("tenant-1", null);

        assertThat(result).containsExactly("slack");
        verify(credentialRepository, never()).findActiveIntegrationsByOrganizationId(anyString());
    }

    @Test
    @DisplayName("touchLastUsed delegates to the repository's single-column last_used UPDATE")
    void touchLastUsedDelegatesToRepository() {
        service.touchLastUsed(243L);

        // Must use the targeted UPDATE - never the full save() (which would re-encrypt
        // credential_data and bump updated_at, making a mere use look like an edit).
        verify(credentialRepository).touchLastUsed(243L);
        verify(credentialRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ==================== renameCredentialForScope ====================

    @Test
    @DisplayName("renameCredentialForScope writes the new name and returns the persisted row")
    void renameCredentialForScopeWritesNewName() {
        Credential before = credential(7L, true, "2026-05-04T10:00:00Z");
        Credential after = named(before, "Gmail (work)");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before), Optional.of(after));
        when(credentialRepository.updateName(7L, "org-1", "Gmail (work)")).thenReturn(1);

        Optional<Credential> result = service.renameCredentialForScope(7L, "tenant-1", "org-1", "Gmail (work)");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Gmail (work)");
        verify(credentialRepository).updateName(7L, "org-1", "Gmail (work)");
    }

    @Test
    @DisplayName("renameCredentialForScope leaves integration, default flag and secrets untouched")
    void renameCredentialForScopeTouchesNothingButTheName() {
        Credential before = credential(7L, true, "2026-05-04T10:00:00Z");
        Credential after = named(before, "New label");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before), Optional.of(after));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "New label"))
                .thenReturn(List.of());
        when(credentialRepository.updateName(7L, "org-1", "New label")).thenReturn(1);

        service.renameCredentialForScope(7L, "tenant-1", "org-1", "New label").orElseThrow();

        // Asserting the returned row's fields would only re-read this test's own stub.
        // What can actually fail is the set of repository calls: the ONLY mutation is
        // updateName, so nothing can move integration, is_default, the status or the
        // encrypted credential_data. In particular never save(), which re-encrypts
        // credential_data and rewrites every column.
        verify(credentialRepository, times(2)).findById(7L);
        verify(credentialRepository).findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "New label");
        verify(credentialRepository).updateName(7L, "org-1", "New label");
        verifyNoMoreInteractions(credentialRepository);
    }

    @Test
    @DisplayName("renameCredentialForScope trims surrounding whitespace before persisting")
    void renameCredentialForScopeTrimsName() {
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before), Optional.of(named(before, "Slack")));
        when(credentialRepository.updateName(7L, "org-1", "Slack")).thenReturn(1);

        service.renameCredentialForScope(7L, "tenant-1", "org-1", "  Slack  ");

        verify(credentialRepository).updateName(7L, "org-1", "Slack");
    }

    @Test
    @DisplayName("renameCredentialForScope rejects a blank name without writing")
    void renameCredentialForScopeRejectsBlankName() {
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope rejects a null name without writing")
    void renameCredentialForScopeRejectsNullName() {
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope rejects a name longer than the name column")
    void renameCredentialForScopeRejectsOverlongName() {
        String tooLong = "x".repeat(CredentialService.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(CredentialService.MAX_NAME_LENGTH));

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope accepts a name of exactly the maximum length")
    void renameCredentialForScopeAcceptsMaxLengthName() {
        String maxName = "x".repeat(CredentialService.MAX_NAME_LENGTH);
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before), Optional.of(named(before, maxName)));
        when(credentialRepository.updateName(7L, "org-1", maxName)).thenReturn(1);

        assertThat(service.renameCredentialForScope(7L, "tenant-1", "org-1", maxName)).isPresent();
    }

    @Test
    @DisplayName("renameCredentialForScope skips the write when the name is unchanged")
    void renameCredentialForScopeSkipsUnchangedName() {
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before));

        Optional<Credential> result = service.renameCredentialForScope(
                7L, "tenant-1", "org-1", "  " + before.name() + "  ");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo(before.name());
        // updated_at must not move for a no-op: it is the agent response cache key
        // (CredentialRepository.computeStateVersion).
        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope returns empty for a credential in another workspace")
    void renameCredentialForScopeRefusesCrossScopeRename() {
        Credential otherWorkspace = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(otherWorkspace));

        Optional<Credential> result = service.renameCredentialForScope(7L, "tenant-9", "org-OTHER", "Hijacked");

        assertThat(result).isEmpty();
        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope renames a workspace-shared credential owned by another member")
    void renameCredentialForScopeAllowsOrgSharedRename() {
        Credential ownedByAnotherMember = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(ownedByAnotherMember), Optional.of(named(ownedByAnotherMember, "Team Gmail")));
        when(credentialRepository.updateName(7L, "org-1", "Team Gmail")).thenReturn(1);

        // Same org, different member. What a mocked repository can prove is the
        // forwarding: the WRITE is scoped by the workspace, never by the caller's
        // tenant, which is what lets a member rename what the org shares. That the
        // SQL really behaves that way is pinned by CredentialRepositoryNameSqlTest.
        Optional<Credential> result = service.renameCredentialForScope(7L, "member-2", "org-1", "Team Gmail");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Team Gmail");
    }

    @Test
    @DisplayName("renameCredentialForScope returns empty when the credential does not exist")
    void renameCredentialForScopeReturnsEmptyForUnknownId() {
        when(credentialRepository.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.renameCredentialForScope(404L, "tenant-1", "org-1", "Whatever")).isEmpty();
        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope returns empty when the row is deleted between read and write")
    void renameCredentialForScopeReturnsEmptyOnLostRace() {
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before));
        when(credentialRepository.updateName(7L, "org-1", "Renamed")).thenReturn(0);

        assertThat(service.renameCredentialForScope(7L, "tenant-1", "org-1", "Renamed")).isEmpty();
    }

    @Test
    @DisplayName("renameCredentialForScope requires an organization scope")
    void renameCredentialForScopeRequiresOrgId() {
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", null, "Renamed"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope refuses a name already used by another credential of the workspace")
    void renameCredentialForScopeRefusesDuplicateName() {
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "gmail"))
                .thenReturn(List.of("gmail"));

        // Both rows answer for "gmail" - this one because it IS a gmail credential, the other
        // because it declares that integration too. findAllByTenantIdAndName, consulted before
        // the integration fallback, would then pick between two different keys on sort order.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "gmail"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .hasMessageContaining("gmail")
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.DUPLICATE_NAME);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope allows a name only used by the credential being renamed")
    void renameCredentialForScopeAllowsOwnNameVariant() {
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(before), Optional.of(named(before, "Gmail 2")));
        when(credentialRepository.updateName(7L, "org-1", "Gmail 2")).thenReturn(1);

        assertThat(service.renameCredentialForScope(7L, "tenant-1", "org-1", "Gmail 2")).isPresent();
    }

    @Test
    @DisplayName("renameCredentialForScope rejects control characters in the name")
    void renameCredentialForScopeRejectsControlCharacters() {
        // The raw name reaches agent-facing listings and every UI label; a newline
        // survives trim() and would forge line breaks there.
        assertThatThrownBy(() -> service.renameCredentialForScope(
                7L, "tenant-1", "org-1", "Gmail\nInjected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope returns empty when the row leaves the scope between write and re-read")
    void renameCredentialForScopeReturnsEmptyWhenRowLeavesScopeAfterWrite() {
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        Credential movedAway = new Credential(
                7L, "tenant-1", "org-OTHER", "Renamed", "gmail", CredentialType.OAuth2,
                CredentialEnvironment.Production, CredentialStatus.active, null, Map.of(),
                List.of(), List.of(), "tenant-1", null, false, null,
                Instant.parse("2026-05-04T10:00:00Z"), Instant.parse("2026-05-05T10:00:00Z"));
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before), Optional.of(movedAway));
        when(credentialRepository.updateName(7L, "org-1", "Renamed")).thenReturn(1);

        // Must not hand back a row (with its tenant/org ids) the caller can no longer see.
        assertThat(service.renameCredentialForScope(7L, "tenant-1", "org-1", "Renamed")).isEmpty();
    }

    @Test
    @DisplayName("renameCredentialForScope refuses a credential whose name is its only identity")
    void renameCredentialForScopeRefusesIntegrationLessCredential() {
        // A blank `integration` is how the workflow-native connectors (smtp, ssh,
        // database) identify themselves, and catalog-service admits a PINNED
        // credential of that shape only when the requirement matches its NAME. A
        // rename would detach every node pinning it and the run would silently fall
        // back to the account default, so the rename is refused instead.
        Credential integrationLess = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(integrationLess));

        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "Company SMTP"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.NAME_IS_IDENTITY);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope refuses a credential with a null integration too")
    void renameCredentialForScopeRefusesNullIntegrationCredential() {
        Credential integrationLess = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), null);
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(integrationLess));

        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "Company SMTP"))
                .isInstanceOf(CredentialRenameRefusedException.class);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope still accepts a no-op on a credential whose name is its identity")
    void renameCredentialForScopeAllowsNoOpOnIntegrationLessCredential() {
        Credential integrationLess = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(integrationLess));

        // Re-submitting the same name changes nothing, so there is nothing to refuse.
        assertThat(service.renameCredentialForScope(
                7L, "tenant-1", "org-1", integrationLess.name())).isPresent();
    }

    @Test
    @DisplayName("renameCredentialForScope probes duplicates against the credential OWNER, not the member renaming it")
    void renameCredentialForScopeProbesDuplicatesAgainstOwner() {
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(before), Optional.of(named(before, "Gmail 2")));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "gmail"))
                .thenReturn(List.of());
        when(credentialRepository.updateName(7L, "org-1", "gmail")).thenReturn(1);

        // A member of the same org, renaming a credential OWNED by tenant-1. The lookup
        // the guard protects (findAllByTenantIdAndName) keys on the OWNER's tenant, so
        // probing the caller's rows would miss the real collision and refuse harmless
        // names that merely clash with something of the caller's own.
        service.renameCredentialForScope(7L, "member-2", "org-1", "gmail");

        verify(credentialRepository).findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "gmail");
    }

    @Test
    @DisplayName("renameCredentialForScope refuses an identity change before it looks for duplicates")
    void renameCredentialForScopeRefusesIdentityChangeBeforeDuplicateCheck() {
        Credential integrationLess = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(integrationLess));

        // Both refusals could apply; the identity one is the accurate diagnosis, and the
        // UI shows a different remedy for each, so the order is part of the contract.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "Taken name"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.NAME_IS_IDENTITY);

        verify(credentialRepository, never())
                .findOtherIntegrationsWithNameForTenant(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope refuses even a case-only relabel of a name-identified credential")
    void renameCredentialForScopeRefusesCaseOnlyRenameOfNameIdentifiedCredential() {
        Credential smtp = new Credential(7L, "tenant-1", "org-1", "smtp", "", CredentialType.Basic_Auth,
                CredentialEnvironment.Production, CredentialStatus.active, null, Map.of(),
                List.of(), List.of(), "tenant-1", null, false, null,
                Instant.parse("2026-05-04T10:00:00Z"), Instant.parse("2026-05-04T10:00:00Z"));
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(smtp));

        // "smtp" -> "SMTP" survives the two readers that normalise (the pinned-credential
        // check and the picker) but NOT findAllByTenantIdAndName, whose SQL is an exact
        // `name = ?`: measured live, the rename emptied `data-map?name=smtp`. The refusal
        // has to be as wide as the strictest reader, not as the most forgiving one.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "SMTP"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.NAME_IS_IDENTITY);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope skips the duplicate probe on a no-op rename")
    void renameCredentialForScopeSkipsProbeOnNoOp() {
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(before));

        service.renameCredentialForScope(7L, "tenant-1", "org-1", before.name());

        verify(credentialRepository, never())
                .findOtherIntegrationsWithNameForTenant(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope trims before measuring the length")
    void renameCredentialForScopeTrimsBeforeLengthCheck() {
        String maxName = "x".repeat(CredentialService.MAX_NAME_LENGTH);
        Credential before = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(before), Optional.of(named(before, maxName)));
        when(credentialRepository.updateName(7L, "org-1", maxName)).thenReturn(1);

        // 261 characters in, 255 stored: the padding must not cost the user their name.
        assertThat(service.renameCredentialForScope(
                7L, "tenant-1", "org-1", "   " + maxName + "   ")).isPresent();
    }

    @Test
    @DisplayName("renameCredentialForScope allows an ordinary label already held by another provider's credential")
    void renameCredentialForScopeAllowsOrdinaryLabelHeldByAnotherProvider() {
        Credential xai = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "xai");
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(xai), Optional.of(named(xai, "Grok perso")));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "Grok perso"))
                .thenReturn(List.of("slack"));
        when(credentialRepository.updateName(7L, "org-1", "Grok perso")).thenReturn(1);

        // The reported bug, in its literal shape: a Slack credential of the same owner already
        // carries this exact label, in a workspace the user could not even open, and the rename
        // was refused over it. Neither reader can confuse the two: the name identifies neither
        // credential, and catalog's selector only ever compares labels WITHIN one integration.
        assertThat(service.renameCredentialForScope(7L, "tenant-1", "org-1", "Grok perso")).isPresent();
        verify(credentialRepository).updateName(7L, "org-1", "Grok perso");
    }

    @Test
    @DisplayName("renameCredentialForScope refuses an ordinary label already held by a credential of the SAME provider")
    void renameCredentialForScopeRefusesOrdinaryLabelHeldBySameProvider() {
        Credential xai = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "xai");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(xai));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "Grok perso"))
                .thenReturn(List.of("xai"));

        // No slug is involved and neither row answers for "Grok perso", so the auth resolver
        // sees nothing. Catalog's run-time selector does: it matches the typed LABEL among the
        // credentials of the endpoint's integration, so two xAI keys called "Grok perso" make it
        // report the choice ambiguous, resolve nothing, and either fail the step or silently run
        // on the account default. A guard derived from the auth resolver alone would allow this.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "Grok perso"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.DUPLICATE_NAME);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope allows a provider slug already held by a credential of ANOTHER provider")
    void renameCredentialForScopeAllowsHomonymOfAnotherProvider() {
        Credential xai = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "xai");
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(xai), Optional.of(named(xai, "xai")));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "xai"))
                .thenReturn(List.of("slack"));
        when(credentialRepository.updateName(7L, "org-1", "xai")).thenReturn(1);

        // The probe runs (the name DOES identify this credential) and finds a homonym that
        // cannot answer for "xai". Only one row can, so the resolver returns it whatever the
        // sort order. Refusing this was refusing a rename over a credential of an unrelated
        // API, frequently in a workspace the user cannot even see.
        assertThat(service.renameCredentialForScope(7L, "tenant-1", "org-1", "xai")).isPresent();
        verify(credentialRepository).updateName(7L, "org-1", "xai");
    }

    @Test
    @DisplayName("renameCredentialForScope refuses when a credential with NO integration already answers for the name")
    void renameCredentialForScopeRefusesNameOfIntegrationLessCredential() {
        Credential gmail = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(gmail));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "gmail"))
                .thenReturn(Collections.singletonList(null));

        // A row with no integration is matched BY its name, so it answers for "gmail" today, and
        // this credential would answer for it too once renamed: the lookup would pick between
        // them on sort order. Both refusing arms cover this shape at once (the label is this
        // provider's requirement AND both rows answer to it), and they cannot be separated here,
        // because the condition that makes a nameless contender a rival is the same one that
        // makes the label identify this row. What the shape does pin is that it takes BOTH sides:
        // renaming this same gmail credential to "smtp" against a nameless row named "smtp" is
        // harmless and allowed, because "smtp" never identifies a gmail credential.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "gmail"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.DUPLICATE_NAME);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope allows a nameless credential's name when it does not identify the renamed one")
    void renameCredentialForScopeAllowsNameOfIntegrationLessCredentialItCannotShadow() {
        Credential gmail = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(gmail), Optional.of(named(gmail, "smtp")));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "smtp"))
                .thenReturn(Collections.singletonList(null));
        when(credentialRepository.updateName(7L, "org-1", "smtp")).thenReturn(1);

        // The mirror of the case above, and the one an "is the other row nameless?" guard gets
        // wrong: an SMTP connector named "smtp" keeps answering for "smtp" alone, because a
        // gmail credential never identifies that name, and it is not a gmail credential either
        // so catalog's selector never offers the two side by side. Refusing here would have
        // reproduced the exact complaint this change fixes, over a different colliding row.
        assertThat(service.renameCredentialForScope(7L, "tenant-1", "org-1", "smtp")).isPresent();
        verify(credentialRepository).updateName(7L, "org-1", "smtp");
    }

    @Test
    @DisplayName("renameCredentialForScope passes the typed label through, so a case-only collision refuses")
    void renameCredentialForScopeRefusesCaseOnlyLabelCollision() {
        Credential xai = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "xai");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(xai));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "grok perso"))
                .thenReturn(List.of("xai"));

        // With the repository mocked, what this can prove is that the service hands the typed
        // label to the probe unchanged rather than normalising it first. That the SQL itself
        // matches trimmed and case-insensitively is pinned where it can actually be observed,
        // against real Postgres, in CredentialRepositoryNameSqlTest#detectsDuplicateNamesInScope.
        // Both halves are needed: catalog's selector compares labels with trim +
        // equalsIgnoreCase, so "Grok perso" and "grok perso" are one credential to it.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "grok perso"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.DUPLICATE_NAME);
    }

    @Test
    @DisplayName("renameCredentialForScope compares both integrations on the CANONICAL slug")
    void renameCredentialForScopeComparesIntegrationsCanonically() {
        Credential stability = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "stabilityai");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(stability));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "Design key"))
                .thenReturn(List.of("Stability-AI"));

        // The label identifies NEITHER row, so this can only be refused by the same-provider arm:
        // a label that identified them would make the reader-1 arm fire too and this would stay
        // green with a raw string comparison. "Stability-AI" and "stabilityai" are one provider,
        // and comparing the two integrations raw would call them different and let two keys of
        // one provider carry one label, which catalog's selector then refuses to choose between.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "Design key"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.DUPLICATE_NAME);
    }

    @Test
    @DisplayName("renameCredentialForScope refuses a typed label that collapses to this provider's requirement")
    void renameCredentialForScopeRefusesLabelCollapsingToTheRequirement() {
        Credential smtpKey = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "smtp");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(smtpKey));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "SMTP Credential"))
                .thenReturn(Collections.singletonList(null));

        // The contender declares NO integration, so there is nothing to compare it against, and
        // "SMTP Credential" does not identify an smtp credential either: it is not the slug, and
        // auth's resolver only strips a LITERAL "-credential". Both checks that look at the two
        // rows come out false, and the rename looks harmless.
        //
        // It is not. Catalog's selector offers a nameless row whenever its NAME collapses to the
        // requirement key, and normalizeForKey deletes the space and the hyphen alike, so
        // "SMTP Credential" and "smtp-credential" are one key. Under requirement
        // "smtp-credential" the endpoint would then be offered the connector AND this key, both
        // carrying the label, and it resolves neither: the step fails, or runs on the account
        // default. This is the arm a pairwise integration comparison structurally cannot see.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "SMTP Credential"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.DUPLICATE_NAME);

        verify(credentialRepository, never()).updateName(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("renameCredentialForScope treats an integration spelt with the -credential suffix as the same provider")
    void renameCredentialForScopeTreatsSuffixedIntegrationAsSameProvider() {
        Credential smtpKey = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "smtp");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(smtpKey));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "Team mailer"))
                .thenReturn(List.of("smtp-credential"));

        // The mirror of the case above on the integration column: catalog's selector admits a row
        // by its integration against EITHER the requirement or the requirement minus the suffix,
        // so "smtp" and "smtp-credential" land in front of one endpoint. Comparing the two
        // canonical slugs for plain equality would call them different providers and allow two
        // rows the selector then refuses to choose between.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "Team mailer"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.DUPLICATE_NAME);
    }

    @Test
    @DisplayName("renameCredentialForScope still allows a nameless credential's label that names ANOTHER provider")
    void renameCredentialForScopeAllowsNamelessLabelOfAnotherProvider() {
        Credential gmail = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(gmail), Optional.of(named(gmail, "SMTP Credential")));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "SMTP Credential"))
                .thenReturn(Collections.singletonList(null));
        when(credentialRepository.updateName(7L, "org-1", "SMTP Credential")).thenReturn(1);

        // The boundary of the arm above: the label collapses to a requirement, but not to THIS
        // credential's. A gmail key is never offered for "smtp-credential", so the connector
        // keeps that requirement to itself and the two are never presented together. Refusing
        // here would turn the fix into "no credential may ever be named after any connector".
        assertThat(service.renameCredentialForScope(7L, "tenant-1", "org-1", "SMTP Credential")).isPresent();
        verify(credentialRepository).updateName(7L, "org-1", "SMTP Credential");
    }

    @Test
    @DisplayName("renameCredentialForScope keeps the contending credential's integration out of the response")
    void renameCredentialForScopeDoesNotDiscloseTheContenderIntegration() {
        Credential smtp = withIntegration(credential(7L, false, "2026-05-04T10:00:00Z"), "smtp");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(smtp));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "Team mailer"))
                .thenReturn(List.of("SMTP"));

        // The contender is the same provider spelt differently, so it really is refused and its
        // integration really is a non-empty string: the assertion has something to
        // catch. The message travels to the caller in the HTTP body and the contending row can
        // live in a workspace they cannot open, so it names the rejected NAME, which they typed,
        // and nothing about the row behind it. The diagnosis goes to the log instead.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "Team mailer"))
                .hasMessageContaining("Team mailer")
                .hasMessageNotContaining("SMTP");
    }

    @Test
    @DisplayName("renameCredentialForScope walks past a harmless homonym to the contender behind it")
    void renameCredentialForScopeFindsCompetitorBehindHomonym() {
        Credential gmail = credential(7L, false, "2026-05-04T10:00:00Z");
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(gmail));
        when(credentialRepository.findOtherIntegrationsWithNameForTenant(7L, "tenant-1", "gmail"))
                .thenReturn(Arrays.asList("slack", null));

        // Checking only the first collision would let the real one through whenever an
        // unrelated provider happened to sort ahead of it.
        assertThatThrownBy(() -> service.renameCredentialForScope(7L, "tenant-1", "org-1", "gmail"))
                .isInstanceOf(CredentialRenameRefusedException.class)
                .extracting(e -> ((CredentialRenameRefusedException) e).reason())
                .isEqualTo(CredentialRenameRefusedException.Reason.DUPLICATE_NAME);
    }

    // ============ findByNameIdentifyingIntegration ============

    @Test
    @DisplayName("findByNameIdentifyingIntegration prefers the row the name identifies over one merely labelled with it")
    void nameLookupPrefersTheIdentifyingRowAmongDuplicates() {
        Credential slackKeyCalledElevenlabs = withIntegration(
                named(credential(7L, false, "2026-05-04T10:00:00Z"), "elevenlabs"), "slack");
        Credential realElevenlabs = withIntegration(
                named(credential(8L, false, "2026-05-04T10:00:00Z"), "elevenlabs"), "elevenlabs");
        when(credentialRepository.findAllByTenantIdAndName("tenant-1", "elevenlabs"))
                .thenReturn(List.of(slackKeyCalledElevenlabs, realElevenlabs));

        // Taking the first row and rejecting it would report "no match" for a name that
        // resolves perfectly well. The token path hides that behind its integration fallback,
        // but GET /scopes has none: it 404s and the catalog side fails open, so the scope
        // check silently stops running for a user who did nothing wrong.
        assertThat(service.findByNameIdentifyingIntegration("tenant-1", "elevenlabs"))
                .isPresent().get().extracting(c -> c.id()).isEqualTo(8L);
    }

    @Test
    @DisplayName("findByNameIdentifyingIntegration prefers a BLANK-integration row over one labelled with another provider")
    void nameLookupPrefersTheNamelessRowOverAMislabelledOne() {
        Credential slackKeyCalledElevenlabs = withIntegration(
                named(credential(7L, false, "2026-05-04T10:00:00Z"), "elevenlabs"), "slack");
        Credential nameless = withIntegration(
                named(credential(8L, false, "2026-05-04T10:00:00Z"), "elevenlabs"), "");
        Credential realElevenlabs = withIntegration(
                named(credential(9L, false, "2026-05-04T10:00:00Z"), "elevenlabs"), "elevenlabs");
        when(credentialRepository.findAllByTenantIdAndName("tenant-1", "elevenlabs"))
                .thenReturn(List.of(slackKeyCalledElevenlabs, nameless, realElevenlabs));

        // A blank integration identifies EVERY name, so the nameless row wins here and the real
        // ElevenLabs key never gets a turn. That is the declared contract (it is how smtp / ssh
        // / database connectors resolve at all), but it is worth pinning: before the resolver
        // walked the list, the mislabelled Slack row sorted first, was rejected, and the caller
        // fell back to the integration, which reached row 9. The nameless row was shielded by
        // accident, not by a rule, and the javadoc names this as the one shape of the change
        // that can still hand an unrelated secret to a provider.
        assertThat(service.findByNameIdentifyingIntegration("tenant-1", "elevenlabs"))
                .isPresent().get().extracting(c -> c.id()).isEqualTo(8L);
    }

    @Test
    @DisplayName("findByNameIdentifyingIntegration stays empty when NO row carrying the name identifies it")
    void nameLookupEmptyWhenNoDuplicateIdentifies() {
        Credential slack = withIntegration(
                named(credential(7L, false, "2026-05-04T10:00:00Z"), "elevenlabs"), "slack");
        Credential github = withIntegration(
                named(credential(8L, false, "2026-05-04T10:00:00Z"), "elevenlabs"), "github");
        when(credentialRepository.findAllByTenantIdAndName("tenant-1", "elevenlabs"))
                .thenReturn(List.of(slack, github));

        // Preferring an identifying row must not degrade into "return something": neither of
        // these may answer for ElevenLabs, and the caller resolves by integration instead.
        assertThat(service.findByNameIdentifyingIntegration("tenant-1", "elevenlabs")).isEmpty();
    }


    @Test
    @DisplayName("findByNameIdentifyingIntegration refuses a credential merely LABELLED with another provider's slug")
    void nameLookupRefusesCredentialOfAnotherProvider() {
        Credential slackKeyCalledElevenlabs = new Credential(
                7L, "tenant-1", "org-1", "elevenlabs", "slack", CredentialType.API_Key,
                CredentialEnvironment.Production, CredentialStatus.active, null,
                Map.of("api_key", "xoxb-slack"), List.of(), List.of(), "tenant-1", null,
                false, null, Instant.parse("2026-05-04T10:00:00Z"), Instant.parse("2026-05-04T10:00:00Z"));
        when(credentialRepository.findAllByTenantIdAndName("tenant-1", "elevenlabs"))
        .thenReturn(List.of(slackKeyCalledElevenlabs));

        // Accepting it would send the Slack key to ElevenLabs' endpoint. The caller then
        // falls back to resolving by integration, which is the correct row.
        assertThat(service.findByNameIdentifyingIntegration("tenant-1", "elevenlabs")).isEmpty();
    }

    @Test
    @DisplayName("findByNameIdentifyingIntegration accepts a credential that declares no integration")
    void nameLookupAcceptsIntegrationLessCredential() {
        Credential smtp = new Credential(
                7L, "tenant-1", "org-1", "smtp", "", CredentialType.Basic_Auth,
                CredentialEnvironment.Production, CredentialStatus.active, null, Map.of(),
                List.of(), List.of(), "tenant-1", null, false, null,
                Instant.parse("2026-05-04T10:00:00Z"), Instant.parse("2026-05-04T10:00:00Z"));
        when(credentialRepository.findAllByTenantIdAndName("tenant-1", "smtp"))
        .thenReturn(List.of(smtp));

        // The workflow-native connectors have nothing else to identify them, which is the
        // whole reason the name branch exists.
        assertThat(service.findByNameIdentifyingIntegration("tenant-1", "smtp")).isPresent();
    }

    @Test
    @DisplayName("findByNameIdentifyingIntegration accepts its own integration across slug spellings")
    void nameLookupAcceptsOwnIntegrationRegardlessOfSpelling() {
        Credential stability = new Credential(
                7L, "tenant-1", "org-1", "stability-ai", "stabilityai", CredentialType.API_Key,
                CredentialEnvironment.Production, CredentialStatus.active, null, Map.of(),
                List.of(), List.of(), "tenant-1", null, false, null,
                Instant.parse("2026-05-04T10:00:00Z"), Instant.parse("2026-05-04T10:00:00Z"));
        when(credentialRepository.findAllByTenantIdAndName("tenant-1", "stability-ai"))
        .thenReturn(List.of(stability));

        // Compared on the canonical slug, like catalog-service does on the pinned path.
        assertThat(service.findByNameIdentifyingIntegration("tenant-1", "stability-ai")).isPresent();
    }

    @Test
    @DisplayName("findByNameIdentifyingIntegration accepts a requirement carrying the -credential suffix")
    void nameLookupStripsTheCredentialSuffix() {
        // Requirements reach this lookup in both spellings: catalog emits "smtp-credential"
        // for some tools and "smtp" for others. Both of this rule's mirrors already strip the
        // suffix (InternalCredentialService derives its integration fallback that way, and
        // catalog's resolvePinnedCredentialOwnership compares against raw AND stripped), so a
        // narrower rule here would reject a credential the rest of the platform accepts.
        Credential smtp = new Credential(
                7L, "tenant-1", "org-1", "smtp-credential", "smtp", CredentialType.API_Key,
                CredentialEnvironment.Production, CredentialStatus.active, null, Map.of(),
                List.of(), List.of(), "tenant-1", null, false, null,
                Instant.parse("2026-05-04T10:00:00Z"), Instant.parse("2026-05-04T10:00:00Z"));
        when(credentialRepository.findAllByTenantIdAndName("tenant-1", "smtp-credential"))
        .thenReturn(List.of(smtp));

        // On the token path a rejection here only costs a WARN, because the integration
        // fallback recovers the row. On GET /scopes there IS no fallback: it would 404, the
        // catalog side fails open, and the scope check silently stops running.
        assertThat(service.findByNameIdentifyingIntegration("tenant-1", "smtp-credential"))
                .isPresent();
    }

    @Test
    @DisplayName("the -credential strip does not let one provider answer for another")
    void suffixStripDoesNotWidenToAnotherProvider() {
        // The strip is a spelling allowance, not a loosening: "slack-credential" must still
        // fail to identify an ElevenLabs key, or the suffix would become a way around the
        // whole check.
        Credential elevenlabs = new Credential(
                7L, "tenant-1", "org-1", "slack-credential", "elevenlabs", CredentialType.API_Key,
                CredentialEnvironment.Production, CredentialStatus.active, null, Map.of(),
                List.of(), List.of(), "tenant-1", null, false, null,
                Instant.parse("2026-05-04T10:00:00Z"), Instant.parse("2026-05-04T10:00:00Z"));
        when(credentialRepository.findAllByTenantIdAndName("tenant-1", "slack-credential"))
        .thenReturn(List.of(elevenlabs));

        assertThat(service.findByNameIdentifyingIntegration("tenant-1", "slack-credential"))
                .isEmpty();
    }

    // ============ createCredential name validation ============

    @Test
    @DisplayName("createCredential rejects a name too long for the column instead of failing in the database")
    void createCredentialRejectsOverlongName() {
        assertThatThrownBy(() -> service.createCredential(
                "tenant-1", "org-1", "x".repeat(CredentialService.MAX_NAME_LENGTH + 1), "gmail",
                CredentialType.API_Key, CredentialEnvironment.Production, null, Map.of(),
                List.of(), List.of(), "tenant-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(CredentialService.MAX_NAME_LENGTH));

        verify(credentialRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("createCredential rejects control characters in the name, exactly like a rename")
    void createCredentialRejectsControlCharacters() {
        assertThatThrownBy(() -> service.createCredential(
                "tenant-1", "org-1", "Gmail\nInjected", "gmail",
                CredentialType.API_Key, CredentialEnvironment.Production, null, Map.of(),
                List.of(), List.of(), "tenant-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");

        verify(credentialRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("createCredential stores the trimmed name")
    void createCredentialTrimsName() {
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of());
        when(credentialRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        Credential created = service.createCredential(
                "tenant-1", "org-1", "  Gmail  ", "gmail",
                CredentialType.API_Key, CredentialEnvironment.Production, null, Map.of(),
                List.of(), List.of(), "tenant-1", null);

        assertThat(created.name()).isEqualTo("Gmail");
    }

    private Credential withIntegration(Credential source, String integration) {
        return new Credential(
                source.id(), source.tenantId(), source.organizationId(), source.name(),
                integration, source.type(), source.environment(), source.status(),
                source.description(), source.credentialData(), source.scopes(), source.tags(),
                source.owner(), source.iconUrl(), source.isDefault(), source.lastUsed(),
                source.createdAt(), source.updatedAt());
    }

    private Credential named(Credential source, String name) {
        return new Credential(
                source.id(),
                source.tenantId(),
                source.organizationId(),
                name,
                source.integration(),
                source.type(),
                source.environment(),
                source.status(),
                source.description(),
                source.credentialData(),
                source.scopes(),
                source.tags(),
                source.owner(),
                source.iconUrl(),
                source.isDefault(),
                source.lastUsed(),
                source.createdAt(),
                Instant.parse("2026-05-05T10:00:00Z"));
    }

    private Credential credential(Long id, boolean isDefault, String createdAt) {
        Instant created = Instant.parse(createdAt);
        return new Credential(
                id,
                "tenant-1",
                "org-1",
                "Gmail " + id,
                "gmail",
                CredentialType.OAuth2,
                CredentialEnvironment.Production,
                CredentialStatus.active,
                "Test credential",
                Map.of("access_token", "enc-token-" + id),
                List.of("email"),
                List.of(),
                "tenant-1",
                "icon",
                isDefault,
                null,
                created,
                created);
    }
}
