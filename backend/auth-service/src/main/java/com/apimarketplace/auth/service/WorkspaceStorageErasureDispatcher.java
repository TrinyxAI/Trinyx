package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Executes durable workspace-object erasures. Claims and result writes are
 * short database transactions; object-store I/O happens between them.
 */
@Service
public class WorkspaceStorageErasureDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(WorkspaceStorageErasureDispatcher.class);

    private final WorkspaceStorageErasureOutbox outbox;
    private final WorkspaceStorageObjectDeleter deleter;

    public WorkspaceStorageErasureDispatcher(
            WorkspaceStorageErasureOutbox outbox,
            WorkspaceStorageObjectDeleter deleter) {
        this.outbox = outbox;
        this.deleter = deleter;
    }

    @Scheduled(fixedDelayString = "${storage.erasure.retry-ms:5000}")
    public void dispatch() {
        for (WorkspaceStorageErasureOutbox.Erasure erasure : outbox.claimDue(25)) {
            try {
                WorkspaceStorageErasureOutbox.validateOwnership(
                        erasure.organizationId(), erasure.tenantId(),
                        erasure.storageKey());
                if (deleter.delete(erasure.id(), erasure.organizationId(),
                        erasure.tenantId(), erasure.storageKey())) {
                    if (!outbox.delivered(erasure)) {
                        log.debug("Workspace erasure lease was reclaimed before completion id={}",
                                erasure.id());
                    }
                } else {
                    outbox.failed(erasure, "storage deletion was not confirmed");
                }
            } catch (RuntimeException failure) {
                outbox.failed(erasure, failure.getMessage());
                log.warn("Workspace storage erasure retry scheduled id={} tenant={} key={}: {}",
                        erasure.id(), erasure.tenantId(), erasure.storageKey(),
                        failure.getMessage());
            }
        }
    }
}
