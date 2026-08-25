package com.apimarketplace.auth.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class WorkspaceStorageErasureDispatcherTest {

    private final WorkspaceStorageErasureOutbox outbox =
            mock(WorkspaceStorageErasureOutbox.class);
    private final WorkspaceStorageObjectDeleter deleter =
            mock(WorkspaceStorageObjectDeleter.class);

    @Test
    void successfulOrAlreadyAbsentDeletionCompletesDurableRecord() {
        var erasure = erasure("tenant-1", "tenant-1/report.pdf");
        when(outbox.claimDue(25)).thenReturn(List.of(erasure));
        // S3-compatible DELETE is idempotent: true also covers an absent object.
        when(deleter.delete(erasure.tenantId(), erasure.storageKey()))
                .thenReturn(true);

        new WorkspaceStorageErasureDispatcher(outbox, deleter).dispatch();

        verify(outbox).delivered(erasure);
        verify(outbox, never()).failed(any(), any());
    }

    @Test
    void transientFailureSurvivesRestartAndIsRetriedIdempotently() {
        var firstClaim = erasure("tenant-1", "tenant-1/report.pdf");
        var retryClaim = new WorkspaceStorageErasureOutbox.Erasure(
                firstClaim.id(), firstClaim.organizationId(),
                firstClaim.tenantId(), firstClaim.storageKey(), 1,
                UUID.randomUUID());
        when(outbox.claimDue(25))
                .thenReturn(List.of(firstClaim))
                .thenReturn(List.of(retryClaim));
        when(deleter.delete("tenant-1", "tenant-1/report.pdf"))
                .thenReturn(false)
                .thenReturn(true);

        new WorkspaceStorageErasureDispatcher(outbox, deleter).dispatch();
        // A new component instance models an application restart; durable DB work
        // is reclaimed rather than relying on in-memory state.
        new WorkspaceStorageErasureDispatcher(outbox, deleter).dispatch();

        verify(outbox).failed(firstClaim, "storage deletion was not confirmed");
        verify(outbox).delivered(retryClaim);
        verify(deleter, times(2)).delete(
                "tenant-1", "tenant-1/report.pdf");
    }

    @Test
    void crossTenantRecordFailsClosedWithoutCallingStorage() {
        var erasure = erasure("tenant-2", "tenant-1/report.pdf");
        when(outbox.claimDue(25)).thenReturn(List.of(erasure));

        new WorkspaceStorageErasureDispatcher(outbox, deleter).dispatch();

        verifyNoInteractions(deleter);
        verify(outbox).failed(eq(erasure), contains("outside tenant ownership"));
        verify(outbox, never()).delivered(any());
    }

    private static WorkspaceStorageErasureOutbox.Erasure erasure(
            String tenant, String key) {
        return new WorkspaceStorageErasureOutbox.Erasure(
                UUID.randomUUID(), "org-1", tenant, key, 0,
                UUID.randomUUID());
    }
}
