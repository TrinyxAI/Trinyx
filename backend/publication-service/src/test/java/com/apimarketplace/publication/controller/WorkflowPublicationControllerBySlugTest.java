package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.service.AgentPublicationService;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@code GET /api/publications/by-slug/{slug}} (V413), the crawlable
 * public read that backs {@code /marketplace/{slug}}.
 *
 * <p>The endpoint is intentionally thin: it resolves the slug then delegates to
 * the by-id handler so the visibility gate is shared rather than duplicated.
 * What is worth pinning is exactly that: an unknown slug must 404, and a
 * resolvable slug must go through the same gate as a UUID read.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController: public read by URL slug")
class WorkflowPublicationControllerBySlugTest {

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

    private static final UUID PUB_ID = UUID.fromString("0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8a9b");

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(publicationService, agentPublicationService,
                listQueryService, reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                onboardingCategoryMapper, orgAccessGuard);
    }

    @Test
    @DisplayName("Unknown slug → 404, and the publication lookup is never attempted")
    void unknownSlugReturnsNotFound() {
        when(publicationService.findIdByPublicSlug("no-such-app")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getPublicationBySlugPublic("no-such-app", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(publicationService, never()).getPublicationById(any());
    }

    @Test
    @DisplayName("Resolvable slug whose publication is ACTIVE+PUBLIC → 200 with the detail payload")
    void publicPublicationIsReadableAnonymously() {
        WorkflowPublicationEntity pub = anonymouslyReadablePublication();
        when(publicationService.findIdByPublicSlug("invoice-bot")).thenReturn(Optional.of(PUB_ID));
        when(publicationService.getPublicationById(PUB_ID)).thenReturn(Optional.of(pub));

        ResponseEntity<?> response = controller.getPublicationBySlugPublic("invoice-bot", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("The detail payload carries publicSlug and publisherHandle, like the list payload")
    void detailPayloadCarriesThePublicSeoFields() {
        WorkflowPublicationEntity pub = anonymouslyReadablePublication();
        pub.setPublisherHandle("john-doe");
        when(publicationService.findIdByPublicSlug("invoice-bot")).thenReturn(Optional.of(PUB_ID));
        when(publicationService.getPublicationById(PUB_ID)).thenReturn(Optional.of(pub));

        ResponseEntity<?> response = controller.getPublicationBySlugPublic("invoice-bot", null, null);

        // Found in production: these two fields were added to the LIST payload
        // only. The crawlable listing page reads the DETAIL payload, so without
        // publicSlug its indexability gate failed and every listing page shipped
        // noindex with no structured data. A green deploy hid it completely.
        java.util.Map<?, ?> body = (java.util.Map<?, ?>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("publicSlug")).isEqualTo("invoice-bot");
        assertThat(body.get("publisherHandle")).isEqualTo("john-doe");
    }

    @Test
    @DisplayName("Anonymous caller gets the publisher email stripped, exactly as on the by-id path")
    void anonymousCallerGetsEmailStripped() {
        WorkflowPublicationEntity pub = anonymouslyReadablePublication();
        pub.setPublisherEmail("publisher@example.com");
        when(publicationService.findIdByPublicSlug("invoice-bot")).thenReturn(Optional.of(PUB_ID));
        when(publicationService.getPublicationById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, null, null)).thenReturn(false);

        ResponseEntity<?> response = controller.getPublicationBySlugPublic("invoice-bot", null, null);

        // Delegation is the whole point: the PII scrub must apply here without
        // being re-implemented on the slug path.
        assertThat(response.getBody()).isInstanceOf(java.util.Map.class);
        java.util.Map<?, ?> body = (java.util.Map<?, ?>) response.getBody();
        assertThat(body.containsKey("publisherEmail")).isFalse();
    }

    @Test
    @DisplayName("Resolvable slug whose publication is PRIVATE → 404, same shape as an unknown slug")
    void privatePublicationIsIndistinguishableFromMissing() {
        WorkflowPublicationEntity pub = anonymouslyReadablePublication();
        pub.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PRIVATE);
        when(publicationService.findIdByPublicSlug("secret-app")).thenReturn(Optional.of(PUB_ID));
        when(publicationService.getPublicationById(PUB_ID)).thenReturn(Optional.of(pub));

        ResponseEntity<?> response = controller.getPublicationBySlugPublic("secret-app", null, null);

        // A probe must not be able to tell "no such page" from "exists but not
        // yours", otherwise the slug space becomes an enumeration oracle.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Resolvable slug whose publication is still PENDING_REVIEW → 404")
    void preModerationPublicationIsNotReadable() {
        WorkflowPublicationEntity pub = anonymouslyReadablePublication();
        pub.setStatus(WorkflowPublicationEntity.PublicationStatus.PENDING_REVIEW);
        when(publicationService.findIdByPublicSlug("invoice-bot")).thenReturn(Optional.of(PUB_ID));
        when(publicationService.getPublicationById(PUB_ID)).thenReturn(Optional.of(pub));

        ResponseEntity<?> response = controller.getPublicationBySlugPublic("invoice-bot", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static WorkflowPublicationEntity anonymouslyReadablePublication() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUB_ID);
        pub.setTitle("Invoice Bot");
        pub.setPublicSlug("invoice-bot");
        pub.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        pub.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        pub.setPublicationType(WorkflowPublicationEntity.PublicationType.WORKFLOW);
        return pub;
    }
}
