package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.publication.domain.PublicationReviewEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewerIdentityResolver")
class ReviewerIdentityResolverTest {

    @Mock
    private AuthClient authClient;

    private static final String ALICE = "12";
    private static final String BOB = "34";

    private ReviewerIdentityResolver resolver() {
        return new ReviewerIdentityResolver(authClient);
    }

    private static PublicationReviewEntity review(String reviewerId, String storedName) {
        PublicationReviewEntity review = new PublicationReviewEntity();
        review.setId(UUID.randomUUID());
        review.setPublicationId(UUID.randomUUID());
        review.setReviewerId(reviewerId);
        review.setReviewerName(storedName);
        return review;
    }

    @Test
    @DisplayName("resolves a single reviewer through the batch endpoint")
    void resolvesSingleReviewer() {
        when(authClient.batchResolveUsers(Set.of(ALICE)))
                .thenReturn(Map.of(ALICE, new UserSummaryDto(ALICE, "Alice", "https://cdn/a.png")));

        UserSummaryDto identity = resolver().resolve(ALICE);

        assertThat(identity).isNotNull();
        assertThat(identity.displayName()).isEqualTo("Alice");
        assertThat(identity.avatarUrl()).isEqualTo("https://cdn/a.png");
    }

    @Test
    @DisplayName("returns null for an id auth-service does not know")
    void returnsNullForUnknownId() {
        when(authClient.batchResolveUsers(Set.of(ALICE))).thenReturn(Map.of());

        assertThat(resolver().resolve(ALICE)).isNull();
    }

    @Test
    @DisplayName("swallows an auth-service failure instead of breaking the review feed")
    void swallowsAuthFailure() {
        when(authClient.batchResolveUsers(anySet())).thenThrow(new RuntimeException("connection refused"));

        assertThat(resolver().resolveAll(List.of(ALICE, BOB))).isEmpty();
    }

    @Test
    @DisplayName("only looks up the authors of rows whose stored name is blank")
    void looksUpOnlyRowsMissingAName() {
        when(authClient.batchResolveUsers(Set.of(BOB)))
                .thenReturn(Map.of(BOB, new UserSummaryDto(BOB, "Bob", null)));

        Map<String, UserSummaryDto> identities = resolver().resolveMissingFor(List.of(
                review(ALICE, "Alice"),   // already carries a name: not looked up
                review(BOB, null),        // pre-fix row
                review(BOB, "   ")));     // blank counts as missing

        assertThat(identities).containsOnlyKeys(BOB);
    }

    @Test
    @DisplayName("skips the RPC entirely when every row already carries a name")
    void skipsRpcWhenNothingMissing() {
        Map<String, UserSummaryDto> identities =
                resolver().resolveMissingFor(List.of(review(ALICE, "Alice"), review(BOB, "Bob")));

        assertThat(identities).isEmpty();
        verify(authClient, never()).batchResolveUsers(any());
    }

    @Test
    @DisplayName("returns an empty map for an empty input rather than calling auth-service")
    void handlesEmptyInput() {
        assertThat(resolver().resolveAll(List.of())).isEmpty();
        assertThat(resolver().resolveMissingFor(List.of())).isEmpty();
        verify(authClient, never()).batchResolveUsers(any());
    }
}
