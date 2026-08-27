package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.LimitExceededException;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The on-demand editable WORKFLOW copy of an acquired application.
 *
 * <p>Two properties matter and neither held while the copy was minted at install time:
 * the user's quota denial must REACH them (it used to be logged and swallowed, silently
 * producing no copy), and a failed clone must leave no half-created workflow rows behind.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EditableWorkflowTwinService - on-demand editable copy of an acquired application")
class EditableWorkflowTwinServiceTest {

    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private EntitlementGuard entitlementGuard;

    private EditableWorkflowTwinService service;

    private static final UUID PUB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String APP_CLONE = "22222222-2222-4222-8222-222222222222";
    private static final String COPY_ROW_1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String COPY_ROW_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String TENANT = "42";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        service = new EditableWorkflowTwinService(snapshotCloneService, orchestratorClient, entitlementGuard);
    }

    private Map<String, Object> plan() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of());
        return plan;
    }

    private String createTwin() {
        return service.createTwin(plan(), TENANT, ORG, PUB, "Cool App", "desc", null, APP_CLONE);
    }

    @Test
    @DisplayName("Clones the copy decoupled from the publication snapshot and returns its workflow id")
    void createsTheCopy() {
        when(snapshotCloneService.duplicateToEditableWorkflow(
                any(), eq(TENANT), eq(ORG), eq("Cool App"), eq("desc"), any(), eq(PUB), eq(APP_CLONE)))
                .thenReturn(Map.of("workflowId", COPY_ROW_1));

        assertThat(createTwin()).isEqualTo(COPY_ROW_1);
    }

    @Test
    @DisplayName("Bills WORKFLOW quota (never a second APPLICATION charge) and counts the workspace's workflows")
    void billsWorkflowQuota() {
        doAnswer(inv -> {
            ((LongSupplier) inv.getArgument(2)).getAsLong();
            return null;
        }).when(entitlementGuard).check(eq(TENANT), eq(ResourceType.WORKFLOW), any());
        when(orchestratorClient.countWorkflowsByOrg(ORG)).thenReturn(3L);
        when(snapshotCloneService.duplicateToEditableWorkflow(
                any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                .thenReturn(Map.of("workflowId", COPY_ROW_1));

        createTwin();

        verify(entitlementGuard).check(eq(TENANT), eq(ResourceType.WORKFLOW), any());
        verify(orchestratorClient).countWorkflowsByOrg(ORG);
    }

    @Test
    @DisplayName("Quota denial REACHES the caller (the install-time copy used to swallow it and silently create none)")
    void quotaDenialPropagates() {
        doThrow(new LimitExceededException(
                com.apimarketplace.auth.client.entitlement.LimitExceededError.of(
                        ResourceType.WORKFLOW, "FREE", 5, 5, "upgrade")))
                .when(entitlementGuard).check(eq(TENANT), eq(ResourceType.WORKFLOW), any());

        assertThatThrownBy(this::createTwin).isInstanceOf(LimitExceededException.class);

        verifyNoInteractions(snapshotCloneService);
    }

    @Test
    @DisplayName("A failed clone compensates EXACTLY the rows it created, then rethrows the original cause")
    void failedCloneCompensatesItsOwnRowsAndRethrows() {
        when(snapshotCloneService.duplicateToEditableWorkflow(
                any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                .thenThrow(new AcquireCloneFailedException(
                        Set.of(COPY_ROW_1, COPY_ROW_2), new IllegalStateException("clone died")));

        assertThatThrownBy(this::createTwin)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clone died");

        verify(orchestratorClient).deleteDecoupledDuplicateWorkflow(UUID.fromString(COPY_ROW_1), TENANT, ORG);
        verify(orchestratorClient).deleteDecoupledDuplicateWorkflow(UUID.fromString(COPY_ROW_2), TENANT, ORG);
    }

    @Test
    @DisplayName("findExistingTwinId returns the id the orchestrator reports for this application clone")
    void findsAnExistingCopy() {
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(APP_CLONE), TENANT, ORG, PUB))
                .thenReturn(Map.of("id", COPY_ROW_1));

        assertThat(service.findExistingTwinId(APP_CLONE, TENANT, ORG, PUB)).isEqualTo(COPY_ROW_1);
    }

    @Test
    @DisplayName("findExistingTwinId is null when none exists, so the caller clones one")
    void noExistingCopy() {
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(APP_CLONE), TENANT, ORG, PUB)).thenReturn(null);

        assertThat(service.findExistingTwinId(APP_CLONE, TENANT, ORG, PUB)).isNull();
    }

    @Test
    @DisplayName("findExistingTwinId tolerates a non-UUID application id without calling the orchestrator")
    void malformedApplicationIdIsNotAnExistingCopy() {
        assertThat(service.findExistingTwinId("not-a-uuid", TENANT, ORG, PUB)).isNull();

        verify(orchestratorClient, never()).findEditableDuplicate(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("A lookup FAILURE aborts instead of cloning: answering 'no copy' on a blip would duplicate the resource set")
    void lookupFailureAbortsRatherThanDuplicating() {
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(APP_CLONE), TENANT, ORG, PUB))
                .thenThrow(new IllegalStateException("orchestrator unreachable"));

        assertThatThrownBy(() -> service.resolveOrCreate(
                PUB, APP_CLONE, TENANT, ORG, "Cool App", this::source))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(snapshotCloneService);
    }

    @Test
    @DisplayName("A clone that returns no workflow id fails loudly instead of reporting a copy that does not exist")
    void missingWorkflowIdInCloneResultFails() {
        when(snapshotCloneService.duplicateToEditableWorkflow(
                any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                .thenReturn(Map.of("title", "Cool App"));

        assertThatThrownBy(this::createTwin)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no workflow id");
    }

    // ------------------------------------------------------------------
    // resolveOrCreate - the single entry point both acquire paths use
    // ------------------------------------------------------------------

    private EditableWorkflowTwinService.TwinSource source() {
        return new EditableWorkflowTwinService.TwinSource(plan(), "Cool App", "desc", null);
    }

    @Test
    @DisplayName("resolveOrCreate creates the copy and reports created=true when the caller has none")
    void resolveOrCreateClonesWhenAbsent() {
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(APP_CLONE), TENANT, ORG, PUB)).thenReturn(null);
        when(snapshotCloneService.duplicateToEditableWorkflow(
                any(), eq(TENANT), eq(ORG), eq("Cool App"), eq("desc"), any(), eq(PUB), eq(APP_CLONE)))
                .thenReturn(Map.of("workflowId", COPY_ROW_1));

        Map<String, Object> result = service.resolveOrCreate(PUB, APP_CLONE, TENANT, ORG, "Installed App", this::source);

        assertThat(result)
                .containsEntry("workflowId", COPY_ROW_1)
                .containsEntry("title", "Cool App")
                .containsEntry("created", true);
    }

    @Test
    @DisplayName("resolveOrCreate returns the existing copy WITHOUT resolving the source (the CE path pays a cloud round-trip there)")
    void resolveOrCreateSkipsTheSourceWhenACopyExists() {
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(APP_CLONE), TENANT, ORG, PUB))
                .thenReturn(Map.of("id", COPY_ROW_1));
        AtomicInteger sourceCalls = new AtomicInteger();

        Map<String, Object> result = service.resolveOrCreate(PUB, APP_CLONE, TENANT, ORG, "Installed App", () -> {
            sourceCalls.incrementAndGet();
            return source();
        });

        assertThat(result)
                .containsEntry("workflowId", COPY_ROW_1)
                .containsEntry("title", "Installed App")
                .containsEntry("created", false);
        assertThat(sourceCalls.get()).as("no snapshot fetch on the cheap path").isZero();
        verifyNoInteractions(snapshotCloneService);
    }

    @Test
    @DisplayName("The lookup carries the PUBLICATION id too, so a copy made before an uninstall/reinstall is still found")
    void looksUpByPublicationAsWellAsApplicationClone() {
        // After uninstall + reinstall the application clone has a NEW id, so keying only on
        // it would report "no copy" and clone a second full resource set for a user who
        // already owns one.
        String reinstalledCloneId = UUID.randomUUID().toString();
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(reinstalledCloneId), TENANT, ORG, PUB))
                .thenReturn(Map.of("id", COPY_ROW_1));

        Map<String, Object> result = service.resolveOrCreate(
                PUB, reinstalledCloneId, TENANT, ORG, "Cool App", this::source);

        assertThat(result).containsEntry("workflowId", COPY_ROW_1).containsEntry("created", false);
        verifyNoInteractions(snapshotCloneService);
    }

    @Test
    @DisplayName("A caller that cannot get the lock in time is told the copy is IN PROGRESS, not that it failed")
    void slowHolderMakesTheSecondCallerWaitThenRefuseExplicitly() throws Exception {
        // The refusal has to be its own signal: the copy IS being created, so reporting a
        // generic failure would have the user retry (or give up) on work that is succeeding.
        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(APP_CLONE), TENANT, ORG, PUB))
                .thenReturn(null);
        when(snapshotCloneService.duplicateToEditableWorkflow(
                any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                .thenAnswer(inv -> {
                    holderInside.countDown();
                    releaseHolder.await(10, TimeUnit.SECONDS);
                    return Map.of("workflowId", COPY_ROW_1);
                });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Map<String, Object>> holder = pool.submit(() ->
                service.resolveOrCreate(PUB, APP_CLONE, TENANT, ORG, "Cool App", this::source));
        assertThat(holderInside.await(10, TimeUnit.SECONDS)).isTrue();

        // 0-second patience for the test: the production wait is 20s, the branch is the same.
        assertThatThrownBy(() -> service.resolveOrCreateWithin(
                PUB, APP_CLONE, TENANT, ORG, "Cool App", this::source, 0))
                .isInstanceOf(EditableWorkflowTwinService.CopyInProgressException.class)
                .hasMessageContaining("already being created");

        releaseHolder.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).containsEntry("created", true);
        pool.shutdownNow();
    }

    @Test
    @DisplayName("A different application is never blocked by an in-progress copy of another one")
    void aBusyApplicationDoesNotBlockAnUnrelatedOne() throws Exception {
        String otherApp = UUID.randomUUID().toString();
        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(APP_CLONE), TENANT, ORG, PUB))
                .thenReturn(null);
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(otherApp), TENANT, ORG, PUB))
                .thenReturn(Map.of("id", COPY_ROW_2));
        when(snapshotCloneService.duplicateToEditableWorkflow(
                any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                .thenAnswer(inv -> {
                    holderInside.countDown();
                    releaseHolder.await(10, TimeUnit.SECONDS);
                    return Map.of("workflowId", COPY_ROW_1);
                });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Map<String, Object>> holder = pool.submit(() ->
                service.resolveOrCreate(PUB, APP_CLONE, TENANT, ORG, "Cool App", this::source));
        assertThat(holderInside.await(10, TimeUnit.SECONDS)).isTrue();

        // Answers immediately with ITS own copy - no waiting, no false "already in progress".
        Map<String, Object> other = service.resolveOrCreateWithin(
                PUB, otherApp, TENANT, ORG, "Other App", this::source, 0);
        assertThat(other).containsEntry("workflowId", COPY_ROW_2).containsEntry("created", false);

        releaseHolder.countDown();
        holder.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    @Test
    @DisplayName("Concurrent requests for the SAME application produce ONE copy, not two resource sets")
    void concurrentRequestsCreateASingleCopy() throws Exception {
        // The whole point of the change is that a copy is expensive (it re-clones every
        // interface, table and agent). Check-then-create without a lock lets a double
        // click or a client retry clone it twice, which is the duplication being removed.
        AtomicInteger clones = new AtomicInteger();
        List<String> created = Collections.synchronizedList(new ArrayList<>());
        when(orchestratorClient.findEditableDuplicate(UUID.fromString(APP_CLONE), TENANT, ORG, PUB))
                .thenAnswer(inv -> created.isEmpty() ? null : Map.of("id", created.get(0)));
        when(snapshotCloneService.duplicateToEditableWorkflow(
                any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                .thenAnswer(inv -> {
                    clones.incrementAndGet();
                    Thread.sleep(40); // widen the window a serial implementation would leave open
                    String id = UUID.randomUUID().toString();
                    created.add(id);
                    return Map.of("workflowId", id);
                });

        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                return service.resolveOrCreate(PUB, APP_CLONE, TENANT, ORG, "Installed App", this::source);
            }));
        }
        startTogether.countDown();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Future<Map<String, Object>> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(clones.get()).as("exactly one clone across concurrent callers").isEqualTo(1);
        assertThat(results).extracting(r -> r.get("workflowId")).containsOnly(created.get(0));
        assertThat(results).filteredOn(r -> Boolean.TRUE.equals(r.get("created"))).hasSize(1);
    }
}
