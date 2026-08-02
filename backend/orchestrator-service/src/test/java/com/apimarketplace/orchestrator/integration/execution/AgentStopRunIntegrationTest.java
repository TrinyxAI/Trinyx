package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.AgentRunStopService;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard for the agent-facing {@code stop_run}, wired through the real
 * Spring context: tool module → {@code AgentRunStopService} → {@code WorkflowResumeService}
 * → the {@code workflow_runs} row.
 *
 * <p>The unit tests pin each collaborator in isolation; this one pins the thing that
 * only shows up when they are wired together and a database is involved: the run REALLY
 * ends CANCELLED, and the agent's reason REALLY lands on the row (it rides inside the
 * stop's own locked write, so a mistake there is invisible to mocks).
 *
 * <p>Verified against a live CE stack before being written down (stop with a reason,
 * cause read back by get_run, second stop idempotent, cross-tenant refusal).
 */
@IntegrationTest
@DisplayName("Agent stop_run - wired through to the run row")
class AgentStopRunIntegrationTest {

    private static final String TENANT = "tenant-stop-run";
    private static final String OTHER_TENANT = "tenant-someone-else";
    /** Org-scoped entities refuse a null organizationId on persist, so the fixture carries one. */
    private static final String ORG = UUID.randomUUID().toString();

    @Autowired
    private WorkflowCrudModule workflowCrudModule;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private com.apimarketplace.orchestrator.services.resume.WorkflowResumeService resumeService;

    @Autowired
    private com.apimarketplace.orchestrator.trigger.ReusableTriggerService reusableTriggerService;

    private WorkflowEntity workflow;

    @BeforeEach
    void setUp() {
        workflow = new WorkflowEntity();
        workflow.setName("Stop run integration");
        workflow.setTenantId(TENANT);
        workflow.setOrganizationId(ORG);
        workflow.setPlan(Map.of(
                "triggers", List.of(Map.of("id", "start", "label", "Start", "type", "manual")),
                "mcps", List.of(), "cores", List.of(), "edges", List.of()));
        workflow = workflowRepository.save(workflow);
    }

    private WorkflowRunEntity aliveRun(String tenantId) {
        return aliveRun(tenantId, ORG, workflow);
    }

    /** A run in ANOTHER organization: a different workspace, not just a different user. */
    private WorkflowRunEntity foreignRun() {
        String otherOrg = UUID.randomUUID().toString();
        WorkflowEntity otherWorkflow = new WorkflowEntity();
        otherWorkflow.setName("Someone else's workflow");
        otherWorkflow.setTenantId(OTHER_TENANT);
        otherWorkflow.setOrganizationId(otherOrg);
        otherWorkflow.setPlan(workflow.getPlan());
        otherWorkflow = workflowRepository.save(otherWorkflow);
        return aliveRun(OTHER_TENANT, otherOrg, otherWorkflow);
    }

    private WorkflowRunEntity aliveRun(String tenantId, String orgId, WorkflowEntity owner) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic("run-" + UUID.randomUUID().toString().substring(0, 12));
        run.setTenantId(tenantId);
        run.setOrganizationId(orgId);
        run.setWorkflow(owner);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setPlanVersion(1);
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    /**
     * The module resolves the caller's org from the request context, which a test thread
     * does not have: bind it the way the async call sites do.
     */
    private ToolExecutionResult stopRun(Map<String, Object> params, String tenantId) {
        ToolExecutionContext context =
                new ToolExecutionContext(tenantId, Map.of(), Map.of(), Set.of(), null, null, ORG, null);
        ToolExecutionResult[] captured = new ToolExecutionResult[1];
        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(ORG, () -> {
            Optional<ToolExecutionResult> result =
                    workflowCrudModule.execute("stop_run", params, tenantId, context);
            assertThat(result).isPresent();
            captured[0] = result.get();
        });
        return captured[0];
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    @Test
    @DisplayName("stopping an alive run ends it CANCELLED and records the agent's reason on the row")
    void stopEndsTheRunAndRecordsTheReason() {
        WorkflowRunEntity run = aliveRun(TENANT);

        ToolExecutionResult result = stopRun(Map.of(
                "run_id", run.getRunIdPublic(),
                "reason", "the browser agent was stuck on a login wall"), TENANT);

        assertThat(result.success()).isTrue();
        assertThat(data(result))
                .containsEntry("status", "CANCELLED")
                .containsEntry("previous_status", "WAITING_TRIGGER")
                .containsEntry("stopped_by", "agent")
                .containsEntry("reason", "the browser agent was stuck on a login wall");

        WorkflowRunEntity stored = runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(stored.getMetadata())
                .containsEntry(AgentRunStopService.META_STOP_REASON,
                        "the browser agent was stuck on a login wall")
                .containsEntry(AgentRunStopService.META_STOPPED_BY, "agent")
                .containsKey(AgentRunStopService.META_STOPPED_AT);
    }

    @Test
    @DisplayName("stopping the same run twice is idempotent: already_terminal, and the first cause is kept")
    void secondStopChangesNothing() {
        WorkflowRunEntity run = aliveRun(TENANT);
        stopRun(Map.of("run_id", run.getRunIdPublic(), "reason", "first cause"), TENANT);

        ToolExecutionResult second = stopRun(Map.of(
                "run_id", run.getRunIdPublic(), "reason", "second cause"), TENANT);

        assertThat(data(second)).containsEntry("already_terminal", true);
        WorkflowRunEntity stored = runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow();
        assertThat(stored.getMetadata())
                .containsEntry(AgentRunStopService.META_STOP_REASON, "first cause");
    }

    @Test
    @DisplayName("a run of another organization is invisible and stays alive")
    void crossTenantRunIsNeverStopped() {
        WorkflowRunEntity foreign = foreignRun();

        ToolExecutionResult result = stopRun(Map.of("run_id", foreign.getRunIdPublic()), TENANT);

        assertThat(result.success()).isFalse();
        WorkflowRunEntity stored = runRepository.findByRunIdPublic(foreign.getRunIdPublic()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        assertThat(hasStopCause(stored)).isFalse();
    }

    /** True when the run carries an agent/user stop cause. Metadata is null on a fresh run. */
    private static boolean hasStopCause(WorkflowRunEntity run) {
        return run.getMetadata() != null
                && run.getMetadata().containsKey(AgentRunStopService.META_STOP_REASON);
    }

    /**
     * The mode the help pushes agents toward: end THIS execution without killing the
     * workflow. Only reachable on a run that is actually executing.
     */
    @Test
    @DisplayName("graceful stop returns a RUNNING run to WAITING_TRIGGER and records the cause")
    void gracefulStopKeepsTheRunAlive() {
        WorkflowRunEntity run = aliveRun(TENANT);
        run.setStatus(RunStatus.RUNNING);
        runRepository.save(run);

        ToolExecutionResult result = stopRun(Map.of(
                "run_id", run.getRunIdPublic(),
                "mode", "graceful",
                "reason", "ending this cycle only"), TENANT);

        assertThat(result.success()).isTrue();
        assertThat(data(result)).containsEntry("mode", "graceful");
        WorkflowRunEntity stored = runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        assertThat(stored.getMetadata())
                .containsEntry(AgentRunStopService.META_STOP_REASON, "ending this cycle only");
    }

    /**
     * The run is alive again after a reactivate, so the cause of the stop it just undid
     * must be gone: otherwise every later report shows "stopped because X" on a run that
     * is waiting to fire. Guards the production clear in {@code reactivateWorkflow}.
     */
    @Test
    @DisplayName("reactivating a stopped run drops the stop cause")
    void reactivateDropsTheStopCause() {
        WorkflowRunEntity run = aliveRun(TENANT);
        stopRun(Map.of("run_id", run.getRunIdPublic(), "reason", "stopped before reactivate"), TENANT);
        assertThat(hasStopCause(runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow())).isTrue();

        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(ORG,
                () -> resumeService.reactivateWorkflow(run.getRunIdPublic()));

        WorkflowRunEntity revived = runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow();
        assertThat(revived.getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        assertThat(hasStopCause(revived)).isFalse();
        assertThat(revived.getMetadata()).doesNotContainKey(AgentRunStopService.META_STOPPED_BY);
    }

    /**
     * The trigger-fire path is where a gracefully-stopped run comes back to life. It must
     * drop the cause there too, otherwise the FRESH epoch reports why the PREVIOUS one was
     * stopped. Guards the clear call in ReusableTriggerService, which no unit test reaches.
     */
    @Test
    @DisplayName("firing a stopped run again drops the stop cause before the new epoch")
    void refiringDropsTheStopCause() {
        WorkflowRunEntity run = aliveRun(TENANT);
        run.setStatus(RunStatus.RUNNING);
        runRepository.save(run);
        stopRun(Map.of("run_id", run.getRunIdPublic(), "mode", "graceful",
                "reason", "stopped before the next fire"), TENANT);
        assertThat(hasStopCause(runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow())).isTrue();

        WorkflowRunEntity parked = runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow();
        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(ORG, () ->
                reusableTriggerService.executeTrigger(parked, "trigger:start",
                        com.apimarketplace.orchestrator.trigger.TriggerType.MANUAL, Map.of()));

        WorkflowRunEntity refired = runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow();
        assertThat(hasStopCause(refired)).isFalse();
        assertThat(refired.getMetadata()).doesNotContainKey(AgentRunStopService.META_STOPPED_BY);
    }

    /**
     * Two stops in a row must not blend: the second one's attribution with the first
     * one's sentence would tell the reader the wrong person said the wrong thing.
     */
    @Test
    @DisplayName("a later stop with no reason clears the previous reason instead of inheriting it")
    void aReasonlessStopDoesNotInheritTheOldReason() {
        WorkflowRunEntity run = aliveRun(TENANT);
        run.setStatus(RunStatus.RUNNING);
        runRepository.save(run);
        stopRun(Map.of("run_id", run.getRunIdPublic(), "mode", "graceful", "reason", "budget blown"), TENANT);

        stopRun(Map.of("run_id", run.getRunIdPublic()), TENANT);

        WorkflowRunEntity stored = runRepository.findByRunIdPublic(run.getRunIdPublic()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(hasStopCause(stored)).isFalse();
        assertThat(stored.getMetadata()).containsEntry(AgentRunStopService.META_STOPPED_BY, "agent");
    }
}
