package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.PublicationReviewEntity;
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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * The review feed used to render every author as "Anonymous": the display name
 * was only ever read from an {@code X-User-Name} header that nothing injected,
 * so {@code reviewer_name} was null on every stored row. The read path now fills
 * a blank stored name from the live identity, which also repairs the rows
 * written before the fix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController review author identity")
class WorkflowPublicationControllerReviewIdentityTest {

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

    private WorkflowPublicationController controller;

    private static final UUID PUB_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String LEGACY_AUTHOR = "42";
    private static final String NAMED_AUTHOR = "43";

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard,
                Mockito.mock(ApplicationTemplateResetService.class));
    }

    private static PublicationReviewEntity review(String reviewerId, String storedName) {
        PublicationReviewEntity review = new PublicationReviewEntity();
        review.setId(UUID.randomUUID());
        review.setPublicationId(PUB_ID);
        review.setReviewerId(reviewerId);
        review.setReviewerName(storedName);
        review.setRating((short) 4);
        review.setComment("does the job");
        return review;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> reviewsOf(ResponseEntity<?> response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getBody()).get("reviews");
    }

    @Test
    @DisplayName("fills a blank stored name from the live identity instead of returning null")
    void fillsBlankNameFromLiveIdentity() {
        PublicationReviewEntity legacyRow = review(LEGACY_AUTHOR, null);
        when(reviewService.getReviews(any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(legacyRow), PageRequest.of(0, 20), 1));
        when(reviewService.resolveAuthorIdentities(any()))
                .thenReturn(Map.of(LEGACY_AUTHOR, new UserSummaryDto(LEGACY_AUTHOR, "Camille Roy", "https://cdn/c.png")));

        List<Map<String, Object>> reviews = reviewsOf(controller.getReviews(PUB_ID.toString(), 0, 20, false));

        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).get("reviewerName")).isEqualTo("Camille Roy");
        assertThat(reviews.get(0).get("reviewerAvatarUrl")).isEqualTo("https://cdn/c.png");
    }

    @Test
    @DisplayName("keeps the stored name when the row has one, so a rename does not rewrite old comments")
    void keepsStoredNameWhenPresent() {
        PublicationReviewEntity namedRow = review(NAMED_AUTHOR, "Sophie Mercier");
        when(reviewService.getReviews(any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(namedRow), PageRequest.of(0, 20), 1));
        when(reviewService.resolveAuthorIdentities(any()))
                .thenReturn(Map.of(NAMED_AUTHOR, new UserSummaryDto(NAMED_AUTHOR, "Sophie M. (renamed)", null)));

        List<Map<String, Object>> reviews = reviewsOf(controller.getReviews(PUB_ID.toString(), 0, 20, false));

        assertThat(reviews.get(0).get("reviewerName")).isEqualTo("Sophie Mercier");
    }

    @Test
    @DisplayName("still returns the feed when no identity can be resolved")
    void survivesUnresolvableIdentities() {
        PublicationReviewEntity legacyRow = review(LEGACY_AUTHOR, null);
        when(reviewService.getReviews(any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(legacyRow), PageRequest.of(0, 20), 1));
        when(reviewService.resolveAuthorIdentities(any())).thenReturn(Map.of());

        List<Map<String, Object>> reviews = reviewsOf(controller.getReviews(PUB_ID.toString(), 0, 20, false));

        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).get("reviewerName")).isNull();
        assertThat(reviews.get(0).get("comment")).isEqualTo("does the job");
    }

    @Test
    @DisplayName("resolves replies the same way as top-level reviews")
    void fillsReplyAuthorName() {
        PublicationReviewEntity reply = review(LEGACY_AUTHOR, null);
        reply.setParentId(UUID.randomUUID());
        reply.setRating(null);
        when(reviewService.getReplies(any())).thenReturn(List.of(reply));
        when(reviewService.resolveAuthorIdentities(any()))
                .thenReturn(Map.of(LEGACY_AUTHOR, new UserSummaryDto(LEGACY_AUTHOR, "Nora Ahmadi", null)));

        ResponseEntity<?> response =
                controller.getReplies(PUB_ID.toString(), reply.getParentId().toString());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> replies =
                (List<Map<String, Object>>) ((Map<String, Object>) response.getBody()).get("replies");
        assertThat(replies.get(0).get("reviewerName")).isEqualTo("Nora Ahmadi");
    }
}
