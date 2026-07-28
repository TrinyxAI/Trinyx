package com.apimarketplace.publication.service;

import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fills {@code public_slug} on publications created before V413.
 *
 * <p>V413 adds the column as NULLABLE rather than rewriting every row inside the
 * migration: slug generation needs the same de-accenting and de-duplication rules
 * as the publish path ({@link PublicationSlugAssigner}), which are not reasonably
 * expressible in SQL, and a long data rewrite inside Flyway would block startup
 * for every service.
 *
 * <p>Idempotent: it only ever selects rows whose slug is NULL, so a second boot
 * is a no-op and a partially completed run simply resumes. Rows it cannot slug
 * stay NULL and remain reachable by UUID; they are reported, not retried
 * forever.
 */
@Component
@ConditionalOnProperty(name = "publication.slug-backfill.enabled", havingValue = "true", matchIfMissing = true)
public class PublicationSlugBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PublicationSlugBackfillRunner.class);

    private static final int BATCH_SIZE = 200;

    /**
     * Hard stop on the drain loop. Batches are re-queried from offset 0 (rows
     * leave the result set as they get a slug), so a row that keeps failing would
     * otherwise be selected forever. Bounds the work at BATCH_SIZE * MAX_BATCHES
     * rows per boot; anything beyond that is picked up on the next start.
     */
    private static final int MAX_BATCHES = 500;

    private final WorkflowPublicationRepository repository;
    private final PublicationSlugBackfillWorker worker;

    public PublicationSlugBackfillRunner(WorkflowPublicationRepository repository,
                                         PublicationSlugBackfillWorker worker) {
        this.repository = repository;
        this.worker = worker;
    }

    @Override
    public void run(ApplicationArguments args) {
        int totalAssigned = 0;

        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<UUID> ids = repository.findIdsWithoutPublicSlug(PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }

            int assigned = assignBatch(ids);

            // No progress on a non-empty batch means every row in it failed.
            // Retrying would spin on the same rows, so stop and surface it.
            if (assigned == 0) {
                log.warn("Publication slug backfill stalled: {} rows still have no slug and none could be "
                        + "assigned. They stay reachable by UUID.", ids.size());
                break;
            }

            totalAssigned += assigned;
        }

        if (totalAssigned > 0) {
            log.info("Publication slug backfill assigned {} slug(s)", totalAssigned);
        }
    }

    /**
     * Assign slugs for one batch. Each row goes through {@link
     * PublicationSlugBackfillWorker} - a separate bean on purpose, so the
     * per-row {@code @Transactional} is honoured: a self-invocation would bypass
     * the Spring proxy and silently run without a transaction. One transaction
     * per row means a single bad row (or a unique-index race with a concurrent
     * publish) cannot roll back the whole batch.
     *
     * @return how many rows actually got a slug
     */
    private int assignBatch(List<UUID> ids) {
        int assigned = 0;
        for (UUID id : ids) {
            try {
                if (worker.assignOne(id)) {
                    assigned++;
                }
            } catch (Exception e) {
                log.warn("Could not assign a public slug to publication {}: {}", id, e.toString());
            }
        }
        return assigned;
    }
}
