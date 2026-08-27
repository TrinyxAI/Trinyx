package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Creates the decoupled, freely-editable {@code WORKFLOW} copy of an acquired
 * marketplace APPLICATION - the copy that lands in {@code /app/workflows} while the
 * acquired application itself stays run-only.
 *
 * <p><b>On demand, never at install time (2026-08-14).</b> Acquiring an application used
 * to mint this copy automatically, from both the local and the CE-remote acquire paths.
 * Because the copy is a SECOND full clone of the same snapshot, every install created two
 * of everything the app carries: two interfaces, two tables, two agents - visibly stacked
 * in the user's lists, billed twice against the INTERFACE / DATA entitlements, and left
 * behind on uninstall (deleting a workflow deletes neither interfaces nor datasources).
 * The copy is now created only when the user explicitly asks for one.
 *
 * <p><b>Why the copy re-clones instead of sharing the application's resources.</b>
 * Pointing both workflows at the SAME interfaces / tables would look tidier and is
 * exactly what must not happen:
 * <ul>
 *   <li>a shared datasource means BOTH workflows subscribe to it, so one row insert
 *       fires the application AND the editable copy (double execution, double credits);</li>
 *   <li>the copy is meant to be experimented with - a destructive CRUD node there would
 *       hit the application's live data, and a column rename would silently make the
 *       application's textual {@code where} comparisons match nothing;</li>
 *   <li>deleting a shared interface strips its node from EVERY plan in the tenant
 *       (interface-service cascades through the orchestrator), mutilating the run-only
 *       application as a side effect.</li>
 * </ul>
 * Independent copies are the point of the word "decoupled"; the fix for the duplication
 * was to stop creating them behind the user's back, not to start sharing.
 */
@Service
public class EditableWorkflowTwinService {

    private static final Logger logger = LoggerFactory.getLogger(EditableWorkflowTwinService.class);

    /**
     * One lock per (organization, application), serialising check-then-create.
     *
     * <p>Without it the action is only SEQUENTIALLY idempotent: a double click, two tabs,
     * or a client retry over a slow first call all pass the "already have one?" check
     * together and clone two full resource sets - the very duplication this class exists
     * to prevent.
     *
     * <p>Per KEY rather than striped: with shared stripes, two unrelated applications
     * would serialise against each other and the loser would be told that a copy of
     * "this application" is in progress - a false statement about a different app. Entries
     * are removed once the last waiter is gone, so the map cannot grow without bound.
     *
     * <p>Scope is honest about its limit: this guards one JVM. It fully covers CE (single
     * instance) and the common double-submit on cloud; two requests landing on different
     * replicas in the same instant can still both create one (the row carries no unique
     * index - a decoupled copy is deliberately exempt from the V268 APPLICATION index).
     * That residual window needs a DB-level guard, which is a schema change on a shared
     * table, so it is deliberately not smuggled into this fix.
     */
    private static final Map<String, LockEntry> LOCKS = new ConcurrentHashMap<>();
    /** Long enough to absorb a double-click / client retry, short enough to answer inside a request. */
    private static final int LOCK_WAIT_SECONDS = 20;

    /** A lock plus the number of threads holding or waiting on it (drives eviction). */
    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    /**
     * Refusal when another request is already creating this exact copy. Distinct from a
     * failure: the copy IS being created, so the caller must be told to wait rather than
     * shown "could not be created" (and certainly not a 500).
     */
    public static class CopyInProgressException extends RuntimeException {
        public CopyInProgressException(String message) {
            super(message);
        }
    }

    private final SnapshotCloneService snapshotCloneService;
    private final OrchestratorInternalClient orchestratorClient;
    /**
     * Always present in a running service (auth-client's {@code AuthClientConfig} declares
     * it unconditionally and publication-service component-scans that package). Nullable
     * only so unit fixtures can construct this service without a guard.
     */
    private final EntitlementGuard entitlementGuard;

    public EditableWorkflowTwinService(SnapshotCloneService snapshotCloneService,
                                        OrchestratorInternalClient orchestratorClient,
                                        EntitlementGuard entitlementGuard) {
        this.snapshotCloneService = snapshotCloneService;
        this.orchestratorClient = orchestratorClient;
        this.entitlementGuard = entitlementGuard;
    }

    /**
     * Everything needed to clone a copy, resolved LAZILY: the CE-remote path pays a cloud
     * round-trip to fetch the snapshot, and must not pay it when the caller already has a
     * copy to hand back.
     */
    public record TwinSource(Map<String, Object> planSnapshot,
                             String title,
                             String description,
                             List<Map<String, Object>> nodeIcons) {
    }

    /**
     * Return the caller's editable copy of {@code applicationWorkflowId}, creating it only
     * if they have none. The single entry point both acquire paths (local + CE remote) use,
     * so the idempotence, the locking and the response shape cannot drift apart.
     *
     * @param fileNamespaceId the publication id backing the {@code _publications/{id}/}
     *        file namespace of the snapshot (never null, or the clone silently drops every
     *        embedded file)
     * @param existingTitle title to report when the copy already exists (no snapshot is
     *        fetched on that path)
     * @param source resolved ONLY when a copy actually has to be cloned
     * @return {@code {workflowId, title, created}} - {@code created=false} means the caller
     *         already had one
     * @throws com.apimarketplace.auth.client.entitlement.LimitExceededException when the
     *         workspace is at its WORKFLOW quota
     */
    public Map<String, Object> resolveOrCreate(UUID fileNamespaceId,
                                                String applicationWorkflowId,
                                                String tenantId,
                                                String organizationId,
                                                String existingTitle,
                                                Supplier<TwinSource> source) {
        return resolveOrCreateWithin(fileNamespaceId, applicationWorkflowId, tenantId, organizationId,
                existingTitle, source, LOCK_WAIT_SECONDS);
    }

    /**
     * {@link #resolveOrCreate} with an explicit patience for the per-application lock.
     * Package-private seam: tests drive the "another request is already creating this copy"
     * branch with 0 seconds instead of sitting through the production wait.
     */
    Map<String, Object> resolveOrCreateWithin(UUID fileNamespaceId,
                                               String applicationWorkflowId,
                                               String tenantId,
                                               String organizationId,
                                               String existingTitle,
                                               Supplier<TwinSource> source,
                                               int lockWaitSeconds) {
        String lockKey = organizationId + "|" + applicationWorkflowId;
        ReentrantLock lock = acquireLock(lockKey, lockWaitSeconds);
        try {
            String existing = findExistingTwinId(applicationWorkflowId, tenantId, organizationId, fileNamespaceId);
            if (existing != null) {
                logger.info("[EditableTwin] tenant {} application {} already has editable WORKFLOW {} - returning it",
                        tenantId, applicationWorkflowId, existing);
                return result(existing, existingTitle, false);
            }

            TwinSource resolved = source.get();
            String workflowId = createTwin(resolved.planSnapshot(), tenantId, organizationId, fileNamespaceId,
                    resolved.title(), resolved.description(), resolved.nodeIcons(), applicationWorkflowId);
            return result(workflowId, resolved.title(), true);
        } finally {
            releaseLock(lockKey, lock);
        }
    }

    private static Map<String, Object> result(String workflowId, String title, boolean created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflowId);
        result.put("title", title);
        result.put("created", created);
        return result;
    }

    /**
     * Take this key's lock, but never wait indefinitely: the holder is running a full
     * clone (seconds), and a caller blocked past the gateway's request window would see a
     * timeout for work that is actually succeeding. Waiting long enough to cover a
     * double-click, then refusing EXPLICITLY with {@link CopyInProgressException}, is the
     * honest outcome.
     */
    private static ReentrantLock acquireLock(String lockKey, int lockWaitSeconds) {
        LockEntry entry = LOCKS.compute(lockKey, (key, existing) -> {
            LockEntry target = existing != null ? existing : new LockEntry();
            target.users++;
            return target;
        });
        boolean taken = false;
        try {
            taken = entry.lock.tryLock(lockWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dropUser(lockKey);
            throw new CopyInProgressException("Interrupted while waiting to create the editable copy");
        }
        if (!taken) {
            dropUser(lockKey);
            throw new CopyInProgressException(
                    "An editable copy of this application is already being created. "
                            + "Wait for it to finish, then open it from the application page.");
        }
        return entry.lock;
    }

    private static void releaseLock(String lockKey, ReentrantLock lock) {
        lock.unlock();
        dropUser(lockKey);
    }

    /** Drop this caller's claim and evict the entry once nobody holds or awaits it. */
    private static void dropUser(String lockKey) {
        LOCKS.computeIfPresent(lockKey, (key, entry) -> --entry.users <= 0 ? null : entry);
    }

    /**
     * The copy that already exists for this application, or {@code null}.
     *
     * <p>A lookup FAILURE propagates instead of answering {@code null}: pretending "no
     * copy" on a blip would clone a second full resource set behind the user's back.
     *
     * @param publicationId also matches a copy created BEFORE an uninstall/reinstall,
     *        which gives the application a new clone id while the user's copy is unchanged
     */
    public String findExistingTwinId(String applicationWorkflowId, String tenantId, String organizationId,
                                      UUID publicationId) {
        if (applicationWorkflowId == null || applicationWorkflowId.isBlank()) return null;
        UUID appId;
        try {
            appId = UUID.fromString(applicationWorkflowId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Map<String, Object> existing = orchestratorClient.findEditableDuplicate(
                appId, tenantId, organizationId, publicationId);
        return existing != null && existing.get("id") != null ? existing.get("id").toString() : null;
    }

    /**
     * Clone {@code planSnapshot} again as a decoupled editable {@code WORKFLOW}
     * ({@code source_publication_id = NULL}, lineage in
     * {@code metadata.duplicatedFromApplicationId}).
     *
     * <p>Unlike the old install-time call this one is NOT best-effort: the user asked for
     * the copy, so a quota denial or a clone failure must reach them instead of being
     * logged and swallowed. A failed clone still compensates its own partial workflow rows
     * before the exception propagates.
     *
     * @param fileNamespaceId the {@code _publications/{id}/} namespace the snapshot's file
     *        refs live under (the publication id) - never null, or the clone would silently
     *        drop every embedded file.
     * @return the new workflow id
     * @throws com.apimarketplace.auth.client.entitlement.LimitExceededException when the
     *         workspace is at its WORKFLOW quota
     */
    public String createTwin(Map<String, Object> planSnapshot,
                              String tenantId,
                              String organizationId,
                              UUID fileNamespaceId,
                              String title,
                              String description,
                              List<Map<String, Object>> nodeIcons,
                              String applicationWorkflowId) {
        // Quota: the copy bills WORKFLOW quota only, never a credit (the application
        // already billed APPLICATION). The internal create-application endpoint runs no
        // entitlement check, so it is enforced here.
        if (entitlementGuard != null && organizationId != null && !organizationId.isBlank()) {
            entitlementGuard.check(tenantId, ResourceType.WORKFLOW,
                    () -> orchestratorClient.countWorkflowsByOrg(organizationId));
        }

        try {
            Map<String, Object> duplicate = snapshotCloneService.duplicateToEditableWorkflow(
                    planSnapshot, tenantId, organizationId, title, description, nodeIcons,
                    fileNamespaceId, applicationWorkflowId);
            Object workflowId = duplicate != null ? duplicate.get("workflowId") : null;
            if (workflowId == null) {
                throw new IllegalStateException("Editable copy clone returned no workflow id");
            }
            logger.info("[EditableTwin] tenant {} pub {} -> editable WORKFLOW {} (decoupled from application {})",
                    tenantId, fileNamespaceId, workflowId, applicationWorkflowId);
            return workflowId.toString();
        } catch (AcquireCloneFailedException cloneFailure) {
            logger.warn("[EditableTwin] clone failed for tenant={} pub={}: {} - compensating its own rows",
                    tenantId, fileNamespaceId, cloneFailure.getMessage());
            compensate(cloneFailure.getCreatedWorkflowIds(), tenantId, organizationId);
            if (cloneFailure.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw cloneFailure;
        }
    }

    /**
     * Delete ONLY the workflow rows the failed clone created (root + any sub-workflows),
     * each a plain decoupled {@code WORKFLOW} the orchestrator guards as such. Cleanup
     * failures are swallowed so they never mask the real error on its way to the user.
     */
    private void compensate(Set<String> createdWorkflowIds, String tenantId, String organizationId) {
        if (createdWorkflowIds == null || createdWorkflowIds.isEmpty()) return;
        for (String idStr : createdWorkflowIds) {
            if (idStr == null) continue;
            try {
                orchestratorClient.deleteDecoupledDuplicateWorkflow(UUID.fromString(idStr), tenantId, organizationId);
            } catch (Exception e) {
                logger.warn("[EditableTwin/compensate] cleanup failed for {}: {}", idStr, e.getMessage());
            }
        }
    }
}
