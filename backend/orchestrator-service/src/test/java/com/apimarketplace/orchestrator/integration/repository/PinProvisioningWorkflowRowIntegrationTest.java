package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test pinning what a PROVISIONING pin is allowed to do to the
 * {@code workflows} row, against a REAL persistence context - the property mock-based
 * unit tests structurally cannot prove.
 *
 * <p>Why this test exists. {@code WorkflowPinService.provisionProductionRun} reuses
 * {@code WorkflowExecutionService.createExecution} rather than hand-rolling a run, which
 * is the right call - but creating a run is not read-only on the workflow. Down that
 * stack, {@link WorkflowEntityResolverService#resolveWorkflowEntity} re-loads the
 * workflow by id, which inside one persistence context returns the SAME managed
 * instance the pin is holding, and overwrites its {@code plan} and {@code dataInputs}
 * with the ones the run starts from. Under dirty checking that reaches the database with
 * no {@code save()} call anywhere in sight.
 *
 * <p>The damage is worst on the exact scenario provisioning exists for. Pinning an older
 * version - a rollback - is the most likely way to reach a pinned version with no run,
 * and it would have replaced the user's current draft in {@code workflows.plan} with the
 * older version's plan, silently. A unit test with a mocked execution service sees none
 * of this: the mock never touches the entity.
 *
 * <p>So this test wires the REAL clobbering code ({@code WorkflowEntityResolverService})
 * behind the execution-service seam and asserts the workflow row afterwards. Two
 * deliberate substitutions, both narrow:
 * <ul>
 *   <li>{@code createExecution} is overridden to do what matters here - run the real
 *       resolver, force the flush production's own queries would force, then persist a
 *       run row - instead of booting the whole execution stack (interface snapshots,
 *       markup pins, metrics).</li>
 *   <li>The {@code EntityManager} handed to the pin service is a mock, because its only
 *       use is {@code pg_advisory_xact_lock}, which does not exist on the H2 the
 *       integration profile runs. Every repository still goes through the real
 *       persistence context, which is where the behaviour under test lives.</li>
 * </ul>
 */
@SpringBootTest(classes = PinProvisioningWorkflowRowIntegrationTest.TestApp.class)
@ActiveProfiles("integration-test")
@DirtiesContext
@DisplayName("WorkflowPinService provisioning - what it may write to the workflows row")
class PinProvisioningWorkflowRowIntegrationTest {

    private static final String TENANT = "tenant-pin-provision";
    /** OrgScopedEntity refuses a persist with a null organizationId off a request thread. */
    private static final String ORG = "org-pin-provision";

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @EntityScan(basePackageClasses = WorkflowEntity.class)
    @EnableJpaRepositories(basePackageClasses = WorkflowRepository.class)
    static class TestApp {

        /** Lets the test read what the stand-in observed, without making it a bean. */
        @Bean
        ExecutionServiceHolder executionServiceHolder() {
            return new ExecutionServiceHolder();
        }

        @Bean
        TriggerTypeDetector triggerTypeDetector() {
            return new TriggerTypeDetector();
        }

        @Bean
        WorkflowEntityResolverService entityResolverService(WorkflowRepository workflowRepository,
                                                            WorkflowRunRepository runRepository) {
            return new WorkflowEntityResolverService(workflowRepository, runRepository);
        }

        @Bean
        WorkflowPlanVersionService planVersionService(WorkflowPlanVersionRepository versionRepository,
                                                      WorkflowRepository workflowRepository,
                                                      StorageBreakdownService breakdownService,
                                                      ObjectMapper objectMapper) {
            return new WorkflowPlanVersionService(versionRepository, workflowRepository,
                    breakdownService, objectMapper);
        }

        @Bean
        WorkflowPinService pinService(WorkflowRepository workflowRepository,
                                      WorkflowRunRepository runRepository,
                                      WorkflowPlanVersionService versionService,
                                      WorkflowEntityResolverService resolver,
                                      TriggerTypeDetector triggerTypeDetector,
                                      EntityManager sharedEntityManager,
                                      ExecutionServiceHolder holder) {
            // Built with `new`, never registered as a bean: WorkflowExecutionService
            // declares @Autowired(required=true) fields for the whole execution stack,
            // and a bean-managed stand-in would drag every one of them into this context.
            ResolverBackedExecutionService executionService =
                    new ResolverBackedExecutionService(resolver, runRepository, sharedEntityManager);
            holder.instance = executionService;

            // Mock EntityManager for the service itself: its only use there is the
            // Postgres advisory lock, which H2 does not have. Repositories keep the real
            // persistence context.
            EntityManager em = mock(EntityManager.class);
            jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
            when(em.createNativeQuery(anyString())).thenReturn(query);
            when(query.setParameter(anyString(), any())).thenReturn(query);
            when(query.getSingleResult()).thenReturn(0);
            return new WorkflowPinService(workflowRepository, runRepository, versionService,
                    em, executionService, triggerTypeDetector, null, null);
        }
    }

    static class ExecutionServiceHolder {
        ResolverBackedExecutionService instance;
    }

    /**
     * Stands in for the execution stack with the frames that matter to the workflow row:
     * the real entity resolver, and a flush. Nothing here executes the plan.
     *
     * <p><b>What it stands in for, and therefore what it cannot catch.</b> Production's
     * {@code createExecution} reaches these writers of the {@code workflows} row:
     * {@code WorkflowEntityResolverService.resolveWorkflowEntity} (plan + dataInputs,
     * replicated here), {@code WorkflowPersistenceService.updateWorkflowEntity}
     * (lastExecutedAt + updatedAt, lastExecutedAt replicated here), and
     * {@code ScheduleSyncService.syncFromPinnedVersion} (reads pinned_version, observed
     * here via {@link #pinnedVersionSeenDuringCreate}). It also runs
     * {@code autoArchiveExecutionPlan}, {@code snapshotInterfacesForRun} and
     * {@code createPinsForRun}, which write to other tables only.
     *
     * <p>If a future change makes one of those - or a new frame - write to the workflow
     * row, this test will NOT see it. The real-stack guard for that is
     * {@code frontend/e2e/ce/ce-pin-provisions-production-run.spec.ts} (CE-PIN-PROVISION-006),
     * which runs no CI job. Extend the list above when you extend the stack.
     */
    static class ResolverBackedExecutionService extends WorkflowExecutionService {
        private final WorkflowEntityResolverService resolver;
        private final WorkflowRunRepository runRepository;
        private final EntityManager entityManager;

        /** What pinned_version looked like while the inner schedule sync would have run. */
        Integer pinnedVersionSeenDuringCreate;

        /** Set to make the next createExecution blow up, the way a real failure would. */
        boolean failNextCreate;

        ResolverBackedExecutionService(WorkflowEntityResolverService resolver,
                                       WorkflowRunRepository runRepository,
                                       EntityManager entityManager) {
            this.resolver = resolver;
            this.runRepository = runRepository;
            this.entityManager = entityManager;
        }

        @Override
        public WorkflowExecution createExecution(WorkflowPlan plan, Map<String, Object> dataInputs,
                                                 Integer planVersion) {
            String runId = "run_pin_provision_" + UUID.randomUUID();
            WorkflowExecution execution = new WorkflowExecution(runId, plan, dataInputs);
            execution.setPlanVersion(planVersion);

            // The real frame under test - this is what overwrites plan + dataInputs on the
            // managed workflow entity.
            WorkflowEntity entity = resolver.resolveWorkflowEntity(execution).orElseThrow();

            // recordWorkflowStart's other workflow-row side effect.
            entity.setLastExecutedAt(Instant.now());

            // Record what the inner ScheduleSyncService would read. A NULL here is what
            // made it disable every schedule on the workflow mid-pin.
            pinnedVersionSeenDuringCreate = entity.getPinnedVersion();

            // Production issues queries between the clobber and the pin's finally
            // (autoArchiveExecutionPlan, the schedule sync), so Hibernate auto-flushes the
            // bad values to the database and the restore has to make them dirty AGAIN.
            // Without this flush the test would pass for a weaker reason - the values
            // never reaching the DB at all - and could not fail if that stopped holding.
            entityManager.flush();

            if (failNextCreate) {
                failNextCreate = false;
                throw new IllegalStateException("provisioning failed");
            }

            WorkflowRunEntity run = new WorkflowRunEntity(entity, entity.getTenantId(), runId,
                    new HashMap<>(), new HashMap<>(), null);
            // buildRunEntity copies the workflow's org onto the run; OrgScopedEntity
            // refuses a persist with a null org off a request thread.
            run.setOrganizationId(entity.getOrganizationId());
            run.setStatus(RunStatus.WAITING_TRIGGER);
            run.setPlanVersion(planVersion);
            run.setPlan(new HashMap<>(plan.getOriginalPlan()));
            runRepository.save(run);
            return execution;
        }
    }

    @Autowired private WorkflowPinService pinService;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowPlanVersionRepository versionRepository;
    @Autowired private ExecutionServiceHolder holder;
    @MockBean private StorageBreakdownService breakdownService;

    /**
     * The holder is a singleton across the whole class, so a latch left set by one test
     * could be read by the next. Reset rather than rely on every test writing before it
     * reads - that is one removed self-reset away from silent cross-test contamination.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetStandIn() {
        holder.instance.pinnedVersionSeenDuringCreate = null;
        holder.instance.failNextCreate = false;
    }

    private static Map<String, Object> planWithWebhook(String marker) {
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("type", "webhook");
        trigger.put("label", "Hook " + marker);
        Map<String, Object> plan = new HashMap<>();
        plan.put("name", "Pin provisioning " + marker);
        plan.put("marker", marker);
        plan.put("triggers", List.of(trigger));
        return plan;
    }

    /**
     * Seeds a workflow whose stored plan is the CURRENT draft, plus an older archived
     * version carrying a different plan. Pinning that older version is the rollback case.
     */
    private WorkflowEntity seedWorkflowWithOlderVersion(Map<String, Object> draftPlan,
                                                        Map<String, Object> olderPlan,
                                                        Map<String, Object> dataInputs) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setTenantId(TENANT);
        workflow.setOrganizationId(ORG);
        workflow.setName("Pin provisioning fixture");
        workflow.setPlan(draftPlan);
        workflow.setDataInputs(dataInputs);
        WorkflowEntity saved = workflowRepository.save(workflow);

        versionRepository.save(new WorkflowPlanVersionEntity(saved.getId(), 1, olderPlan, TENANT));
        return saved;
    }

    @Test
    @DisplayName("pinning an older, never-run version leaves the current draft plan and data inputs intact")
    void provisioningPinDoesNotClobberTheWorkflowRow() {
        Map<String, Object> draftPlan = planWithWebhook("draft-v2");
        Map<String, Object> olderPlan = planWithWebhook("archived-v1");
        Map<String, Object> dataInputs = new HashMap<>(Map.of("seed", "keep-me"));
        WorkflowEntity workflow = seedWorkflowWithOlderVersion(draftPlan, olderPlan, dataInputs);

        WorkflowPinService.PinResult result = pinService.pin(workflow.getId(), TENANT, ORG, 1);

        assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);

        WorkflowEntity reloaded = workflowRepository.findById(workflow.getId()).orElseThrow();
        // The whole point: a rollback pin must not rewrite the canvas the user is editing.
        assertThat(reloaded.getPlan())
                .as("workflows.plan must still hold the current draft, not the pinned older version")
                .containsEntry("marker", "draft-v2");
        assertThat(reloaded.getDataInputs())
                .as("workflows.data_inputs must survive - provisioning passes an empty map to createExecution")
                .containsEntry("seed", "keep-me");
        assertThat(reloaded.getLastExecutedAt())
                .as("pinning executes nothing, so it must not claim a last execution")
                .isNull();

        // And the pin did its actual job.
        assertThat(reloaded.getPinnedVersion()).isEqualTo(1);
        assertThat(reloaded.getProductionRunId()).isNotNull();
        assertThat(runRepository.findById(reloaded.getProductionRunId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.WAITING_TRIGGER);
    }

    @Test
    @DisplayName("the pinned version is already visible while the run is being created")
    void innerSyncSeesThePinnedVersionNotNull() {
        WorkflowEntity workflow = seedWorkflowWithOlderVersion(
                planWithWebhook("draft"), planWithWebhook("v1"), new HashMap<>());

        pinService.pin(workflow.getId(), TENANT, ORG, 1);

        // recordWorkflowStart re-syncs schedules whenever it creates a run for a plan that
        // has one, and ScheduleSyncService reads a NULL pinned_version as "disable every
        // schedule for this workflow". On a first-ever pin that would switch the schedules
        // OFF mid-pin, and the repair sync afterwards is best-effort. So the value visible
        // at that moment must already be the version being pinned.
        assertThat(holder.instance.pinnedVersionSeenDuringCreate)
                .as("pinned_version must be set BEFORE the run is created, not after")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a failed pin restores the version production was already serving")
    void failedRePinKeepsThePreviousPin() {
        WorkflowEntity workflow = seedWorkflowWithOlderVersion(
                planWithWebhook("draft"), planWithWebhook("v1"), new HashMap<>());
        // Production is already serving v1, pinned the normal way.
        pinService.pin(workflow.getId(), TENANT, ORG, 1);
        versionRepository.save(new WorkflowPlanVersionEntity(workflow.getId(), 2, planWithWebhook("v2"), TENANT));
        holder.instance.failNextCreate = true;

        WorkflowPinService.PinResult result = pinService.pin(workflow.getId(), TENANT, ORG, 2);

        assertThat(result).isInstanceOf(WorkflowPinService.PinResult.ProductionRunUnavailable.class);
        WorkflowEntity reloaded = workflowRepository.findById(workflow.getId()).orElseThrow();
        // The pre-set must roll back to the PREVIOUS pin - not to null, and above all not
        // stay on the version that failed, which would move production silently.
        assertThat(reloaded.getPinnedVersion())
                .as("a failed re-pin must leave production on the version it was serving")
                .isEqualTo(1);
        // The stand-in flushes the clobbered values before it throws, so the whole restore
        // is observable on the refusal path too, not just the happy one.
        assertThat(reloaded.getPlan())
                .as("a refused pin must not leave the older plan on the workflow row")
                .containsEntry("marker", "draft");
        assertThat(reloaded.getLastExecutedAt())
                .as("a refused pin executed even less than a successful one")
                .isNull();
    }

    @Test
    @DisplayName("a version whose stored plan is empty pins without a production run and without a run row")
    void emptyStoredPlanPinsWithoutProvisioning() {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setTenantId(TENANT);
        workflow.setOrganizationId(ORG);
        workflow.setName("Empty stored plan fixture");
        workflow.setPlan(planWithWebhook("draft"));
        WorkflowEntity saved = workflowRepository.save(workflow);
        // An archived version with no plan content - the null/empty half of the
        // provisioning guard, which the unit tests reach only via an empty trigger list.
        versionRepository.save(new WorkflowPlanVersionEntity(saved.getId(), 1, new HashMap<>(), TENANT));

        WorkflowPinService.PinResult result = pinService.pin(saved.getId(), TENANT, ORG, 1);

        assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
        assertThat(((WorkflowPinService.PinResult.Success) result).productionRunIdPublic()).isNull();

        WorkflowEntity reloaded = workflowRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPinnedVersion()).isEqualTo(1);
        assertThat(reloaded.getProductionRunId()).isNull();
        assertThat(runRepository.findAll())
                .as("nothing can fire an empty plan, so no run - in particular none stuck RUNNING")
                .noneMatch(r -> r.getWorkflow() != null && saved.getId().equals(r.getWorkflow().getId()));
    }
}
