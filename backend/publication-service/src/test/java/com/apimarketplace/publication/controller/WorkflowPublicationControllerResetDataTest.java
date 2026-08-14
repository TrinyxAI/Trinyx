package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.ApplicationTemplateResetService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gating + error mapping for {@code POST /publications/{id}/reset-data}.
 *
 * <p>The endpoint replaces the caller's own installed rows, so it is destructive: the tests
 * below pin that a read-only VIEWER and an org-restricted member are refused BEFORE anything
 * is touched, and that the two "cannot restore" reasons reach the caller as distinct statuses
 * (404 "you have not installed this" vs 409 "there is no template") rather than a blanket 500.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController - reset application data")
class WorkflowPublicationControllerResetDataTest {

    private static final UUID PUB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private PublicationListQueryService listQueryService;
    @Mock private PublicationReviewService reviewService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private ShowcaseSnapshotReader showcaseSnapshotReader;
    @Mock private ShowcaseFileRefRewriter fileRefRewriter;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private ApplicationTemplateResetService templateResetService;

    private WorkflowPublicationController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard, templateResetService);
    }

    private void publicationExists() {
        when(publicationService.getPublicationById(PUB_ID))
                .thenReturn(Optional.of(mock(WorkflowPublicationEntity.class)));
    }

    private void memberCanWrite() {
        when(orgAccessGuard.canWrite(eq("org-1"), anyString(), eq("application"), anyString(), anyString()))
                .thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    @DisplayName("Reports what was restored so the caller can tell a full reset from a partial one")
    void reportsWhatWasRestored() {
        publicationExists();
        memberCanWrite();
        when(templateResetService.resetTables(any(), eq("user-1"), eq("org-1")))
                .thenReturn(new ApplicationTemplateResetService.ResetResult(2, 40, List.of("Orphan")));

        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", "org-1", "MEMBER", PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response))
                .containsEntry("tablesReset", 2)
                .containsEntry("rowsRestored", 40)
                .containsEntry("tablesSkipped", List.of("Orphan"));
    }

    @Test
    @DisplayName("A read-only VIEWER is refused with 403 and nothing is reset")
    void viewerIsRefused() {
        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", "org-1", "VIEWER", PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(templateResetService, never()).resetTables(any(), anyString(), anyString());
        // The gate fires before the publication is even loaded.
        verify(publicationService, never()).getPublicationById(any());
    }

    @Test
    @DisplayName("An org member restricted on this application is refused with 403 and nothing is reset")
    void restrictedMemberIsRefused() {
        when(orgAccessGuard.canWrite("org-1", "user-1", "application", PUB_ID.toString(), "MEMBER"))
                .thenReturn(false);

        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", "org-1", "MEMBER", PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(templateResetService, never()).resetTables(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("An unknown publication is a 404, not a 500")
    void unknownPublicationIsNotFound() {
        memberCanWrite();
        when(publicationService.getPublicationById(PUB_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", "org-1", "MEMBER", PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(templateResetService, never()).resetTables(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("\"You have not installed this application\" surfaces as 404 with the reason")
    void notInstalledSurfacesAsNotFound() {
        publicationExists();
        memberCanWrite();
        when(templateResetService.resetTables(any(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("No acquired application for publication " + PUB_ID));

        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", "org-1", "MEMBER", PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(body(response).get("error").toString()).contains("No acquired application");
    }

    @Test
    @DisplayName("\"There is no template to restore\" surfaces as 409, distinct from the 404 above")
    void missingSnapshotSurfacesAsConflict() {
        publicationExists();
        memberCanWrite();
        when(templateResetService.resetTables(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Publication has no plan snapshot"));

        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", "org-1", "MEMBER", PUB_ID.toString());

        // 409 not 404: the publication exists and is installed, it simply carries nothing to
        // restore from. Collapsing both onto 404 would send the user hunting for a bad id.
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response).get("error").toString()).contains("no plan snapshot");
    }

    @Test
    @DisplayName("An unexpected failure is a 500 that does not leak the exception text")
    void unexpectedFailureIsMasked() {
        publicationExists();
        memberCanWrite();
        when(templateResetService.resetTables(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("datasource-service connection refused at 10.42.0.4"));

        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", "org-1", "MEMBER", PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(body(response).get("error").toString())
                .isEqualTo("Failed to reset application data")
                .doesNotContain("10.42.0.4");
    }

    @Test
    @DisplayName("Without an org header the controller consults no org guard and forwards the null scope verbatim")
    void withoutAnOrgHeaderNoOrgGuardIsConsulted() {
        // Scope of this test: the CONTROLLER's routing only. The org restriction guard is a
        // workspace concept and asking it about a blank workspace would deny every personal
        // caller. Note the service is mocked here: the real resolve path needs an
        // organizationId (the gateway injects one on every authenticated call), so this
        // asserts the guard is skipped and the scope is passed through untouched - NOT that
        // a reset succeeds with no workspace at all.
        when(publicationService.getPublicationById(PUB_ID))
                .thenReturn(Optional.of(mock(WorkflowPublicationEntity.class)));
        when(templateResetService.resetTables(any(), eq("user-1"), eq(null)))
                .thenReturn(new ApplicationTemplateResetService.ResetResult(1, 3, List.of()));

        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", null, null, PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(orgAccessGuard, never()).canWrite(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(templateResetService).resetTables(any(), eq("user-1"), eq(null));
    }

    @Test
    @DisplayName("A malformed publication id is a 400, not a 404 claiming the app is not installed")
    void malformedPublicationIdIsABadRequest() {
        ResponseEntity<?> response = controller.resetApplicationData(
                "user-1", "org-1", "MEMBER", "not-a-uuid");

        // UUID.fromString throws IllegalArgumentException, which is also the service's
        // "you have not installed this" signal. Letting it fall through would answer 404
        // with a raw "Invalid UUID string: not-a-uuid" and send the caller looking for a
        // missing install instead of a typo.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(body(response).get("error").toString()).doesNotContain("Invalid UUID string");
        verify(templateResetService, never()).resetTables(any(), anyString(), anyString());
    }
}
