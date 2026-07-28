package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-row unit of work for {@link PublicationSlugBackfillRunner}.
 *
 * <p>Exists as its own bean solely so {@code @Transactional} is applied: calling
 * an annotated method from within the same class bypasses the Spring proxy, and
 * the backfill would then run outside any transaction.
 */
@Component
public class PublicationSlugBackfillWorker {

    private final WorkflowPublicationRepository repository;

    public PublicationSlugBackfillWorker(WorkflowPublicationRepository repository) {
        this.repository = repository;
    }

    /**
     * Give one publication a slug.
     *
     * <p>{@code REQUIRES_NEW} keeps each row independent: a constraint violation
     * on one row (a slug taken by a concurrent publish between the uniqueness
     * check and the insert) rolls back only that row.
     *
     * @return true when a slug was assigned, false when the row vanished or
     *         already had one
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean assignOne(UUID id) {
        WorkflowPublicationEntity publication = repository.findById(id).orElse(null);
        if (publication == null || publication.getPublicSlug() != null) {
            return false;
        }
        PublicationSlugAssigner.assignIfMissing(publication, repository);
        repository.save(publication);
        return true;
    }
}
