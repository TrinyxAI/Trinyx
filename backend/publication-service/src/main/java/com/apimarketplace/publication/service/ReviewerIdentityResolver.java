package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.publication.domain.PublicationReviewEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the public identity (display name + avatar) of a review author from
 * auth-service.
 *
 * <p>Why this exists: {@code publication_reviews} denormalises
 * {@code reviewer_name} / {@code reviewer_avatar_url}, and both used to be taken
 * from {@code X-User-Name} / {@code X-User-Avatar} request headers. Nothing ever
 * SET those headers - the gateway injects only {@code X-User-ID} - so every
 * review stored a null name and the UI rendered every author as "Anonymous".
 * Reading the identity server-side from the reviewer id fixes that at the source
 * and drops a spoofable input: the request could previously claim any name.
 *
 * <p>Fail-soft on purpose, unlike {@link PublisherProfileSnapshotter}: freezing a
 * publisher's identity is part of publishing and must fail loudly, but a reader
 * posting a comment must not lose it because auth-service hiccuped. An
 * unresolved id yields no entry, and callers keep whatever they already had.
 */
@Service
public class ReviewerIdentityResolver {

    private static final Logger logger = LoggerFactory.getLogger(ReviewerIdentityResolver.class);

    private final AuthClient authClient;

    public ReviewerIdentityResolver(AuthClient authClient) {
        this.authClient = authClient;
    }

    /**
     * Live identity of one review author, or {@code null} when auth-service
     * cannot supply one (unknown / deleted user, transport failure).
     */
    public UserSummaryDto resolve(String reviewerId) {
        return resolveAll(Set.of(reviewerId)).get(reviewerId);
    }

    /**
     * Live identities for a batch of review authors, in one cache-aware RPC.
     * Ids that cannot be resolved are simply absent from the returned map.
     */
    public Map<String, UserSummaryDto> resolveAll(Collection<String> reviewerIds) {
        if (reviewerIds == null || reviewerIds.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = reviewerIds.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, UserSummaryDto> resolved = authClient.batchResolveUsers(ids);
            return resolved != null ? resolved : Map.of();
        } catch (Exception e) {
            logger.warn("Failed to resolve reviewer identities for {} id(s): {}", ids.size(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * Identities needed to render {@code reviews} whose stored
     * {@code reviewer_name} is blank - i.e. rows written before the header-based
     * identity was replaced by this resolver. Rows that already carry a name cost
     * nothing: their author is not part of the lookup.
     */
    public Map<String, UserSummaryDto> resolveMissingFor(Collection<PublicationReviewEntity> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = reviews.stream()
                .filter(r -> !StringUtils.hasText(r.getReviewerName()))
                .map(PublicationReviewEntity::getReviewerId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        return resolveAll(ids);
    }
}
