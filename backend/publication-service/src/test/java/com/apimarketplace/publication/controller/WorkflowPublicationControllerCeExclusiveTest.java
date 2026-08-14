package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.ApplicationTemplateResetService;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.CeExclusiveAcquisitionGuard;
import com.apimarketplace.publication.service.CeExclusivePublicationException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The HTTP contract the marketplace UI keys on: a CE-exclusive refusal must
 * surface as 403 with {@code code=CE_EXCLUSIVE} and the feature list, on EVERY
 * acquire endpoint.
 *
 * <p>This matters because the frontend install store branches on
 * {@code status === 403 && code === 'CE_EXCLUSIVE'}. A 400 (the
 * IllegalArgumentException default) or a 500 (no catch at all) would render the
 * generic "install failed" screen with a retry button that can never succeed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController - CE-exclusive refusal contract")
class WorkflowPublicationControllerCeExclusiveTest {

    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private PublicationListQueryService listQueryService;
    @Mock private PublicationReviewService reviewService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private ShowcaseSnapshotReader showcaseSnapshotReader;
    @Mock private ShowcaseFileRefRewriter fileRefRewriter;
    @Mock private OnboardingCategoryMapper onboardingCategoryMapper;
    @Mock private OrgAccessGuard orgAccessGuard;

    private WorkflowPublicationController controller;

    private static final String PUB_ID = UUID.randomUUID().toString();

    private static CeExclusivePublicationException refusal() {
        return new CeExclusivePublicationException(
                CeExclusiveAcquisitionGuard.BLOCKED_MESSAGE, List.of("CLI_AGENT"));
    }

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(publicationService, agentPublicationService,
                listQueryService, reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                onboardingCategoryMapper, orgAccessGuard,
                org.mockito.Mockito.mock(ApplicationTemplateResetService.class));
    }

    @SuppressWarnings("unchecked")
    private static void assertCeExclusiveBody(ResponseEntity<?> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("code", "CE_EXCLUSIVE");
        assertThat(body).containsEntry("error", CeExclusiveAcquisitionGuard.BLOCKED_MESSAGE);
        assertThat(body).containsEntry("features", List.of("CLI_AGENT"));
    }

    @Test
    @DisplayName("workflow acquire → 403 CE_EXCLUSIVE with the feature list")
    void workflowAcquireReturns403() {
        when(publicationService.acquirePublication(any(UUID.class), anyString(), any()))
                .thenThrow(refusal());

        assertCeExclusiveBody(controller.acquirePublication("7", "org-1", "MEMBER", PUB_ID));
    }

    @Test
    @DisplayName("agent acquire → 403 CE_EXCLUSIVE (not the 400 of IllegalArgumentException)")
    void agentAcquireReturns403() {
        when(agentPublicationService.acquireAgentPublication(any(UUID.class), anyString(), any()))
                .thenThrow(refusal());

        assertCeExclusiveBody(controller.acquireAgent("7", "org-1", "MEMBER", PUB_ID));
    }

    @Test
    @DisplayName("resource acquire → 403 CE_EXCLUSIVE (not the 500 of the generic catch)")
    void resourceAcquireReturns403() {
        when(resourcePublicationService.acquireResource(any(UUID.class), anyString(), any()))
                .thenThrow(refusal());

        assertCeExclusiveBody(controller.acquireResource("7", "org-1", "MEMBER", PUB_ID));
    }

    @Test
    @DisplayName("the by-id detail response carries the flag - it is what the preview page and the chat install card read")
    void detailResponseCarriesTheFlag() {
        UUID pubId = UUID.randomUUID();
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity(
                UUID.randomUUID(), "RAG App", Map.of(), "publisher-1");
        publication.setId(pubId);
        publication.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        publication.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        publication.setCeExclusive(true);
        publication.setCeExclusiveFeatures(List.of("VECTOR_SEARCH"));
        when(publicationService.getPublicationById(pubId)).thenReturn(Optional.of(publication));

        ResponseEntity<?> response = controller.getPublicationByIdPublic(pubId.toString(), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("ceExclusive", true)
                .containsEntry("ceExclusiveFeatures", List.of("VECTOR_SEARCH"));
    }

    @Test
    @DisplayName("the detail response reports false for a normal publication (never an absent key)")
    void detailResponseReportsFalseForNormalPublication() {
        UUID pubId = UUID.randomUUID();
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity(
                UUID.randomUUID(), "Plain App", Map.of(), "publisher-1");
        publication.setId(pubId);
        publication.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        publication.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        when(publicationService.getPublicationById(pubId)).thenReturn(Optional.of(publication));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>)
                controller.getPublicationByIdPublic(pubId.toString(), null, null).getBody();

        // Unlike the internal agent projection, the UI payload is explicit: the
        // frontend must never have to treat an absent key as unknown.
        assertThat(body)
                .containsEntry("ceExclusive", false)
                .containsEntry("ceExclusiveFeatures", List.of());
    }

    @Test
    @DisplayName("a refusal with no features still returns a well-formed body")
    void emptyFeatureListStillWellFormed() {
        when(publicationService.acquirePublication(any(UUID.class), anyString(), any()))
                .thenThrow(new CeExclusivePublicationException(
                        CeExclusiveAcquisitionGuard.BLOCKED_MESSAGE, null));

        ResponseEntity<?> response = controller.acquirePublication("7", "org-1", "MEMBER", PUB_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("features", List.of());
    }
}
