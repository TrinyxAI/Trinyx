package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SubWorkflow node - Executes another workflow by firing its trigger (reusable run pattern).
 *
 * The node loads the target workflow, finds its active run (respecting pinned versions),
 * fires the trigger via ReusableTriggerService, and collects the epoch outputs.
 *
 * Anti-recursion: Tracks call depth via ExecutionContext global data.
 * If the depth exceeds the configured maxDepth, the node fails immediately.
 *
 * Usage:
 * - Compose workflows by calling reusable sub-workflows
 * - Target workflow must have an active run (start it first)
 * - Pass data in via inputMapping, receive results as output
 * - Timeout protection prevents runaway sub-workflows
 *
 * <p><b>Scope:</b> the target workflow id is resolvable at runtime (it goes through
 * {@code templateAdapter.resolveTemplates}, so it can come from an upstream node
 * output or the trigger payload). The node therefore enforces a cross-workspace
 * guard before touching the target: the caller must own it or share its
 * organization, or the call is refused as "not found". This is the same intent as
 * {@code WorkflowTriggerDispatchService} / {@code ErrorTriggerDispatchService} but a
 * deliberately more tolerant predicate, for the reason documented on
 * {@link #isSameWorkspace}.
 *
 * <h2>Known open defect, NOT fixed here</h2>
 *
 * A production report describes a core:loop calling the same sub-workflow N times where the chain
 * silently stops one iteration short: the node stays PENDING with no error until timeoutSeconds and
 * the external service called by the child receives N-1 requests. That stall did NOT reproduce in a
 * single-instance e2e stack, in three shapes (mock child, two epochs, http_request child), so its
 * cause is still unknown and nothing in this class fixes it. What this class does carry is the
 * marker block around the child fire below, which tells you WHERE a fire is parked.
 *
 * <p>The second reported symptom ("No active run found" while a live run exists) is diagnosed but
 * also not fixed: {@link #describeMissingActiveRun} explains why the lookup rejected the run it
 * found, it does not make that run eligible.
 */
public class SubWorkflowNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SubWorkflowNode.class);
    private static final int SUB_RUN_LOCK_STRIPES = 64;
    private static final Object[] SUB_RUN_LOCKS = createSubRunLocks();

    /** Global data key used to track sub-workflow recursion depth. */
    static final String DEPTH_KEY = ExecutionMetadataKeys.SUB_WORKFLOW_DEPTH;

    /** Global data key used to track sub-workflow workflow-id ancestry. */
    static final String ANCESTRY_KEY = ExecutionMetadataKeys.SUB_WORKFLOW_ANCESTRY;

    /**
     * Run statuses considered "active" for sub-workflow dispatch, unchanged from what
     * this node accepted before it adopted the resolver. Single source of truth lives
     * on {@code ProductionRunResolver} because the resolver documents why this lane is
     * narrower than the error lane's non-terminal set.
     */
    static final List<RunStatus> ACTIVE_STATUSES =
        com.apimarketplace.orchestrator.trigger.ProductionRunResolver.SUB_WORKFLOW_ACTIVE_STATUSES;

    /**
     * Every non-terminal status, derived from {@link RunStatus#isTerminal()} so it cannot drift when
     * a status is added. Used ONLY by {@link #describeMissingActiveRun} to explain a failed lookup;
     * it is deliberately wider than {@link #ACTIVE_STATUSES} (which omits {@code AWAITING_SIGNAL}
     * and {@code PENDING}) and must never be used to select a run to fire.
     */
    private static final List<RunStatus> NON_TERMINAL_STATUSES =
        com.apimarketplace.orchestrator.trigger.ProductionRunResolver.NON_TERMINAL_STATUSES;

    /** Trigger types that cannot be fired by sub-workflow node. */
    private static final Set<String> UNFIREABLE_TYPES = Set.of("workflow", "error");

    private final Core.SubWorkflowConfig config;

    // Services injected via setters or acceptServices
    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository workflowRunRepository;
    private ReusableTriggerService reusableTriggerService;
    /** Single production-identity rule; null only in plain unit constructions. */
    private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    private StepOutputService stepOutputService;
    private WorkflowStepDataRepository workflowStepDataRepository;
    /** F2.2 - optional; null in unit tests that don't wire Redis. */
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    public SubWorkflowNode(String nodeId, Core.SubWorkflowConfig config) {
        super(nodeId, NodeType.SUB_WORKFLOW);
        this.config = config;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("SubWorkflow node executing: nodeId={}, itemId={}", nodeId, context.itemId());

        // Build minimal resolved_params early so it is available in ALL failure paths.
        // Enriched with workflowId once resolved.
        String rawWorkflowId = config != null ? config.workflowId() : null;
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        if (rawWorkflowId != null) resolvedParams.put("workflowId", rawWorkflowId);
        if (config != null) {
            if (config.inputMapping() != null) resolvedParams.put("inputMapping", config.inputMapping());
            resolvedParams.put("timeoutSeconds", config.timeoutSeconds());
            resolvedParams.put("maxDepth", config.maxDepth());
            if (config.triggerId() != null) resolvedParams.put("triggerId", config.triggerId());
        }

        try {
            // 1. Anti-recursion depth guard
            int currentDepth = getCurrentDepth(context);
            int maxDepth = config != null ? config.maxDepth() : 5;
            if (currentDepth >= maxDepth) {
                String msg = String.format(
                    "Sub-workflow recursion depth %d exceeds maximum %d", currentDepth, maxDepth);
                logger.error("SubWorkflow recursion guard: nodeId={}, {}", nodeId, msg);
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, msg, failOutput, 0);
            }

            // 2. Resolve workflowId
            String workflowIdStr = resolveWorkflowId(context);
            if (workflowIdStr == null || workflowIdStr.isBlank()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "workflowId is required but was null or empty", failOutput, 0);
            }

            // Update resolvedParams with the resolved (possibly SpEL-evaluated) workflowId
            resolvedParams.put("workflowId", workflowIdStr);

            UUID workflowId;
            try {
                workflowId = UUID.fromString(workflowIdStr);
            } catch (IllegalArgumentException e) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "Invalid workflowId format: " + workflowIdStr, failOutput, 0);
            }

            List<String> currentAncestry = getCurrentAncestry(context);
            if (containsWorkflowId(currentAncestry, workflowId.toString())) {
                String msg = "Sub-workflow recursion cycle detected: workflow " + workflowId
                    + " is already in the call chain " + currentAncestry;
                logger.error("SubWorkflow cycle guard: nodeId={}, {}", nodeId, msg);
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, msg, failOutput, 0);
            }
            List<String> childAncestry = appendWorkflowId(currentAncestry, workflowId.toString());

            // 3. Load the target workflow
            if (workflowRepository == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "WorkflowRepository not injected", failOutput, 0);
            }
            Optional<WorkflowEntity> entityOpt = workflowRepository.findById(workflowId);
            if (entityOpt.isEmpty()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    notFoundMessage(workflowId), failOutput, 0);
            }

            WorkflowEntity entity = entityOpt.get();

            // Cross-workspace guard. workflowId is runtime-resolvable (see class
            // javadoc), so without this a template could point the node at any
            // workflow in the database: it would fire that workflow's run and read
            // its step outputs back into this run's output. Refuse rather than
            // re-bind, which is what the org re-scope below the fire used to do
            // implicitly. See isSameWorkspace for why the predicate is the tolerant
            // one and not the strict crossResourceMatches the dispatch services use.
            if (!isSameWorkspace(context, entity)) {
                // The refusal itself is logged inside isSameWorkspace, which owns the
                // scope decision (and is the only method here that reads both scope
                // getters - see the OrgScopePredicateInvariantTest ArchUnit rule).
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                // Same wording as the genuine not-found branch on purpose: the caller must
                // not be able to tell an out-of-scope workflow from a non-existent one.
                // The hint is safe because it is true in both cases.
                return NodeExecutionResult.failureWithOutput(nodeId,
                    notFoundMessage(workflowId), failOutput, 0);
            }

            Map<String, Object> planMap = entity.getPlan();
            if (planMap == null || planMap.isEmpty()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "Workflow has no plan: " + workflowId, failOutput, 0);
            }

            // 4. Find the run to fire, via the shared resolver
            if (workflowRunRepository == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "WorkflowRunRepository not injected", failOutput, 0);
            }
            if (productionRunResolver == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "ProductionRunResolver not injected", failOutput, 0);
            }

            WorkflowRunEntity run = findActiveRun(entity);
            if (run == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                // describeMissingActiveRun explains WHY the lookup rejected what it found
                // (parked on a signal, transient status, wrong plan version) and names the
                // action that clears each case - strictly more than a flat "start it first".
                return NodeExecutionResult.failureWithOutput(nodeId,
                    describeMissingActiveRun(entity, workflowId), failOutput, 0);
            }
            // No terminal-status re-check here: ACTIVE_STATUSES is applied inside the
            // resolver's query, so the run RESOLVED here is never terminal. Note the run
            // actually FIRED is re-read unfiltered inside the stripe lock below, so it can
            // have gone terminal in between - a pre-existing race this change does not
            // widen (the old check ran at the same point, before the same re-read).

            // 5. Parse plan → resolve trigger
            WorkflowPlan subPlan = WorkflowPlan.fromMap(planMap, workflowId.toString(), context.tenantId());
            String triggerId = resolveTriggerId(subPlan);
            if (triggerId == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "No fireable trigger found in workflow " + workflowId, failOutput, 0);
            }

            TriggerType triggerType = resolveTriggerType(subPlan, triggerId);

            // 6. Resolve input data.
            // Defense-in-depth: PLAN_FROM_PAYLOAD_MARKER is an internal control
            // signal set ONLY by TriggerController after a successful
            // updateRunPlan. A workflow author who pipes that key through a
            // Transform node into the sub-workflow input could otherwise forge
            // the marker and trick the sub-workflow's executeTriggerInternal
            // into skipping its workflow.plan refresh. Strip the key here so
            // the sub-workflow always runs with the proper passive-fire
            // semantics (its own run.plan was never written via updateRunPlan
            // by us - we only carry data, never plan-control intent).
            Map<String, Object> inputData = com.apimarketplace.orchestrator.trigger.ReusableTriggerService
                    .sanitizePlanMarker(resolveInputData(context));
            Map<String, Object> childGlobalData = buildChildSubWorkflowGlobalData(currentDepth, childAncestry);

            // 7. Fire trigger (bypass queue, force auto mode)
            if (reusableTriggerService == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "ReusableTriggerService not injected", failOutput, 0);
            }

            String subRunId = run.getRunIdPublic();
            logger.info("SubWorkflow firing trigger: nodeId={}, subRunId={}, triggerId={}, type={}",
                nodeId, subRunId, triggerId, triggerType);

            // F2.2 - register the parent→child link BEFORE firing so an in-flight
            // cancel on the parent run propagates downward. The engine's
            // isAgentCancelSignalSet walks workflow:parent:{childRunId} pointers
            // up to find a cancelled ancestor. Cleared in finally below.
            if (workflowRedisPublisher != null && context.runId() != null
                    && subRunId != null && !subRunId.equals(context.runId())) {
                workflowRedisPublisher.registerSubWorkflowParent(subRunId, context.runId());
            }

            int timeoutSeconds = config != null ? config.timeoutSeconds() : 300;
            TriggerExecutionResult triggerResult;
            // Between the "firing trigger" line above and the completion of the wait below, this
            // node used to emit nothing, so a fire that never returned was indistinguishable in the
            // logs from a fire that was never dispatched. The markers below close that gap: for a
            // given (subRunId, epoch, spawn, itemIndex) tuple, the last marker emitted names the
            // stage it is parked in.
            //
            // All four identifiers are needed. itemIndex separates concurrent split/fork branches;
            // spawn separates re-executions of the same node, which is what a loop iteration is, so
            // without it five sequential iterations emit byte-identical lines. epoch separates
            // trigger fires of the same run.
            //
            // LEVELS: the two that answer "was it dispatched, and did the worker start" are INFO so
            // they are usable on a default prod configuration (logging level for this package is
            // INFO). The two lock markers are DEBUG: they only matter when contention is suspected,
            // and reading them in prod needs a log-level change for this class.
            logger.debug("SubWorkflow awaiting sub-run lock: nodeId={}, parentRunId={}, subRunId={}, epoch={}, spawn={}, itemIndex={}",
                nodeId, context.runId(), subRunId, context.epoch(), context.spawn(), context.itemIndex());
            synchronized (lockForSubRun(subRunId)) {
                logger.debug("SubWorkflow holding sub-run lock: nodeId={}, parentRunId={}, subRunId={}, epoch={}, spawn={}, itemIndex={}",
                    nodeId, context.runId(), subRunId, context.epoch(), context.spawn(), context.itemIndex());
                // Re-read the freshest child run entity inside the stripe lock so
                // concurrent fires of the same sub-run serialize on a single,
                // up-to-date row instead of racing on a stale snapshot.
                WorkflowRunEntity runForFire = workflowRunRepository.findByRunIdPublic(subRunId)
                    .orElse(run);
                // HOTFIX-2 (2026-05-20) - sub-workflow trigger executes step nodes
                // that persist workflow_step_data + storage.storage; both are
                // OrgScopedEntity and would trip V261 NOT NULL on the FJP worker
                // without re-binding the org scope.
                final String orgIdForWorker = runForFire.getOrganizationId();
                logger.info("SubWorkflow awaiting child epoch: nodeId={}, subRunId={}, epoch={}, spawn={}, itemIndex={}, timeoutSeconds={}",
                    nodeId, subRunId, context.epoch(), context.spawn(), context.itemIndex(), timeoutSeconds);
                try {
                    triggerResult = CompletableFuture.supplyAsync(() -> {
                        // First statement on the worker: distinguishes "the task never got a
                        // thread" (this line absent) from "the worker ran and then parked inside
                        // the child DAG" (this line present, no completion line after it).
                        logger.info("SubWorkflow worker entered: nodeId={}, subRunId={}, epoch={}, spawn={}, itemIndex={}, thread={}",
                            nodeId, subRunId, context.epoch(), context.spawn(), context.itemIndex(),
                            Thread.currentThread().getName());
                        TriggerExecutionResult[] holder = new TriggerExecutionResult[1];
                        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () ->
                            holder[0] = reusableTriggerService.executeTriggerInternal(
                                runForFire, triggerId, triggerType, inputData, true, childGlobalData)
                        );
                        return holder[0];
                    }).get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    String msg = String.format(
                        "Sub-workflow timed out after %d seconds (runId=%s)", timeoutSeconds, subRunId);
                    logger.error("SubWorkflow timeout: nodeId={}, {}", nodeId, msg);
                    if (workflowRedisPublisher != null) {
                        workflowRedisPublisher.clearSubWorkflowParent(subRunId);
                    }
                    Map<String, Object> failOutput = new HashMap<>();
                    failOutput.put("resolved_params", resolvedParams);
                    return NodeExecutionResult.failureWithOutput(nodeId, msg, failOutput, 0);
                } finally {
                    // Best-effort cleanup on the success path too (TTL backstops if missed).
                    if (workflowRedisPublisher != null) {
                        workflowRedisPublisher.clearSubWorkflowParent(subRunId);
                    }
                }

                // 8. Check trigger result
                if (!triggerResult.success()) {
                    String errorMsg = triggerResult.message() != null
                        ? triggerResult.message()
                        : "Sub-workflow trigger failed";
                    logger.warn("SubWorkflow trigger failed: nodeId={}, subRunId={}, error={}",
                        nodeId, subRunId, errorMsg);
                    Map<String, Object> failOutput = new HashMap<>();
                    failOutput.put("resolved_params", resolvedParams);
                    return NodeExecutionResult.failureWithOutput(nodeId, errorMsg, failOutput, 0);
                }
            }

            // 9. Collect outputs from step data (epoch-scoped).
            //
            // Deliberately OUTSIDE the stripe lock. The invariant that makes this safe is epoch
            // monotonicity, not the monitor: the read is scoped to (subRunId, triggerResult.epoch()),
            // TriggerEpochManager only ever increments the epoch (under the run's advisory + row
            // lock), and nothing on the re-fire path deletes or re-keys the rows of a previous
            // epoch. So a concurrent fire of the same sub-run can only write epoch N+1 and cannot
            // contaminate this read.
            // The rows are insert-only: StepDataPersistenceService resolves the output storage id
            // first and writes one INSERT ... ON CONFLICT DO NOTHING with the terminal status
            // already set, and status is part of the row's uniqueness key, so there is no
            // in-place status transition to observe half-written.
            //
            // The monitor was never the guarantee in any case: it is a per-JVM `static` monitor, so
            // with more than one orchestrator replica two fires of the same subRunId on different
            // pods have always run this read unsynchronised.
            //
            // Pre-existing caveat, unchanged by this move and not introduced by it: when
            // ReusableTriggerService short-circuits with the child epoch still open (a blocking
            // signal or an in-flight async agent), it returns success while later signal resumes
            // keep inserting COMPLETED rows at this same epoch. Such a late row can be missed
            // here. The monitor never excluded that either, since the resume runs on its own path.
            logger.debug("SubWorkflow collecting epoch outputs: nodeId={}, subRunId={}, spawn={}, itemIndex={}, childEpoch={}",
                nodeId, subRunId, context.spawn(), context.itemIndex(), triggerResult.epoch());
            Map<String, Object> resultOutputs = collectEpochOutputs(
                subRunId, triggerResult.epoch(), context.tenantId());

            // 10. Build output (backward-compatible format)
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("result", resultOutputs);
            output.put("subWorkflowId", workflowId.toString());
            output.put("subRunId", subRunId);
            output.put("success", true);

            // MANDATORY metadata
            output.put("node_type", "SUB_WORKFLOW");
            output.put("item_index", context.itemIndex());
            output.put("itemIndex", context.itemIndex());
            output.put("item_id", context.itemId());
            output.put("resolved_params", resolvedParams);

            logger.info("SubWorkflow completed: nodeId={}, subRunId={}, epoch={}, outputKeys={}",
                nodeId, subRunId, triggerResult.epoch(), resultOutputs.keySet());
            return NodeExecutionResult.success(nodeId, output);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("SubWorkflow interrupted: nodeId={}", nodeId);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("resolved_params", resolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, "Sub-workflow execution interrupted", failOutput, 0);
        } catch (Exception e) {
            logger.error("SubWorkflow execution failed: nodeId={}, error={}",
                nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("resolved_params", resolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0);
        }
    }

    /**
     * The single wording used for BOTH "no such workflow" and "out of your workspace".
     * They must stay byte-identical: a different message would let a caller probe which
     * workflow ids exist in other tenants. Shared so the two call sites cannot drift.
     */
    private static String notFoundMessage(UUID workflowId) {
        return "Workflow not found: " + workflowId
            + ". Check the id with workflow(action='list'); a sub-workflow target must be "
            + "a workflow you own or that belongs to your organization.";
    }

    /**
     * True when the calling run and the target workflow live in the same workspace.
     *
     * <p>Same INTENT as the guards in {@code WorkflowTriggerDispatchService} and
     * {@code ErrorTriggerDispatchService}, but not the same predicate: those compare
     * two {@code workflows} rows with the strict
     * {@code ScopeGuard.crossResourceMatches}, and both sides are NOT NULL since V263.
     * The caller side here comes from the {@link ExecutionContext}, which can be null
     * even though {@code workflow_runs.organization_id} is NOT NULL, because several
     * paths degrade it on lookup failure ({@code WorkflowExecutionServiceV2},
     * {@code ExecutionCacheManager}, {@code V2StepByStepService}). A null caller org
     * therefore means a DEGRADED or legacy run, not a personal workspace.
     *
     * <p>So the predicate is chosen per case, never looser than the case needs:
     * <ul>
     *   <li><b>Caller org known</b> (the normal case): strict
     *       {@code crossResourceMatches}, identical to the dispatch services. Members
     *       of one organization reach each other's workflows, and nothing else. No
     *       owner fallback, because that would let a run in org A reach the caller's
     *       own workflow in org B.</li>
     *   <li><b>Caller org missing</b> (degraded run): {@code isInOwnerOrOrgScope}, which
     *       here reduces to ownership since there is no org to match on. That is the
     *       tolerance, and it exists only so a degraded run can still reach the
     *       workflows it owns instead of having every sub-workflow call refused with a
     *       misleading "Workflow not found".</li>
     * </ul>
     * Refused in both cases, and the reason this guard exists since the target id is
     * runtime-resolvable: a workflow the caller neither owns nor shares an organization
     * with. A crafted template cannot reach an unrelated tenant.
     *
     * <p><b>Known limit:</b> on a degraded run, a target owned by a DIFFERENT member of
     * the same org is refused, where it would have worked. That is availability lost on
     * an already-degraded run; the alternative, trusting an absent org, is worse.
     */
    @com.apimarketplace.common.scope.TolerantScope(reason =
        "Internal channel: authority is established upstream by the parent run, which the "
        + "engine only executes for an authorized owner. Tolerance is required because the "
        + "caller-side org comes from the ExecutionContext, which degrades to null on several "
        + "lookup-failure paths even though workflow_runs.organization_id is NOT NULL since "
        + "V263; strict isolation would refuse every sub-workflow call from such a run. The "
        + "ownerMatch branch is the tolerance that keeps those runs working. What stays refused "
        + "is the property this guard exists for: a runtime-resolved workflowId reaching a "
        + "workflow the caller neither owns nor shares an organization with.")
    private boolean isSameWorkspace(ExecutionContext context, WorkflowEntity target) {
        String callerOrg = context.organizationId();
        if (callerOrg == null || callerOrg.isBlank()) {
            // WARN: workflow_runs.organization_id is NOT NULL since V263, so a missing org
            // here is a real anomaly (a degraded lookup or a legacy run), not a routine
            // shape. It also marks the calls that fall back to owner-only matching.
            logger.warn("[SCOPE] Sub-workflow caller has no organization on its execution context "
                    + "(runId={}, nodeId={}) - matching on owner scope only", context.runId(), nodeId);
        }
        boolean callerHasOrg = callerOrg != null && !callerOrg.isBlank();
        boolean allowed = callerHasOrg
            // Org known: strict workspace match, same rule as the dispatch services. No
            // owner fallback here - it would let an org-A run reach the caller's own
            // workflow in org-B, which is the isolation isInStrictScope exists to keep.
            ? com.apimarketplace.common.scope.ScopeGuard.crossResourceMatches(
                callerOrg, target.getOrganizationId())
            // Org unknown (degraded run): fall back to ownership, the narrowest rule that
            // still lets that run reach its own workflows.
            : com.apimarketplace.common.scope.ScopeGuard.isInOwnerOrOrgScope(
                context.tenantId(), callerOrg, target.getTenantId(), target.getOrganizationId());
        if (!allowed) {
            logger.warn("[SCOPE] Cross-workspace sub-workflow blocked: nodeId={}, "
                    + "caller(tenant={}, org={}) -> target workflow {} (tenant={}, org={})",
                nodeId, context.tenantId(), callerOrg, target.getId(),
                target.getTenantId(), target.getOrganizationId());
        }
        return allowed;
    }

    /**
     * Finds the run the sub-workflow call should fire.
     *
     * <p>Delegates to
     * {@link com.apimarketplace.orchestrator.trigger.ProductionRunResolver#resolveActiveRun}
     * so this node inherits one shared rule instead of hand-rolling a lookup.
     *
     * <p>Selection is still "newest by {@code started_at}", scoped to the pinned
     * version when the target is pinned - identical to what this node did before.
     * The ONE behavioural addition is that showcase clones are excluded: a frozen
     * marketplace copy shares workflow_id, plan_version and status with the real run,
     * and firing it silently does nothing. {@code production_run_id} is deliberately
     * NOT consulted (see {@code resolveActiveRun}), so a child's editor run can still
     * win here, exactly as before. {@link #ACTIVE_STATUSES} is passed explicitly so this
     * node keeps the exact set it accepted before, and a pin is still not required.
     */
    private WorkflowRunEntity findActiveRun(WorkflowEntity target) {
        return productionRunResolver
            .resolveActiveRun(target, ACTIVE_STATUSES)
            .run()
            .orElse(null);
    }

    /**
     * Builds the failure message for "no eligible run". The single "Start the workflow first." text
     * used to collapse three genuinely different causes, and it was actively misleading in two of
     * them because the run it told the caller to start already existed.
     *
     * <p>The probe is deliberately WIDER than {@link #findActiveRun} on both axes: every
     * non-terminal status (not just {@link #ACTIVE_STATUSES}, which omits both
     * {@code AWAITING_SIGNAL} and {@code PENDING}) and every plan version. That width is what lets
     * it name the run the lookup could not use, and
     * it is safe precisely because it feeds a message and nothing else: it runs ONLY on the failure
     * path, after {@code findActiveRun} already returned null, and it never changes which run is
     * fired. A run refused by the lookup stays refused, it is just named and explained.
     *
     * <p>Deliberately NOT routed through {@code ProductionRunResolver}, despite the overlap: that
     * resolver refuses an unpinned workflow outright, while sub-workflow calls are supported on
     * unpinned workflows. Adopting it here would turn every unpinned sub-workflow into a hard
     * failure. If the two are ever unified, that is the constraint to solve first.
     *
     * <p>Caveat, accepted: the probe takes the newest non-terminal run and does not exclude
     * showcase clones, so on a workflow that has been published and cloned it can name a clone.
     * The alternative (the production-run finders, which do exclude clones) filters on the pin and
     * would therefore return nothing in exactly the version-mismatch case this exists to explain.
     */
    private String describeMissingActiveRun(WorkflowEntity entity, UUID workflowId) {
        // Showcase-safe probe: the selection this explains excludes frozen marketplace
        // clones, so the diagnostic must too. Reporting "run X is alive but ..." about an
        // inert clone would send the agent chasing a run it can never make eligible.
        Optional<WorkflowRunEntity> probe = workflowRunRepository
            .findFirstProductionRunByWorkflowIdAndStatusIn(workflowId, NON_TERMINAL_STATUSES);
        if (probe.isEmpty()) {
            return "No active run found for workflow " + workflowId + ". Start the workflow first.";
        }

        WorkflowRunEntity live = probe.get();
        String head = "No run of workflow " + workflowId + " is eligible for a sub-workflow call. Run "
            + live.getRunIdPublic() + " is alive with status " + live.getStatus() + " but ";

        // Cause 1: the status is non-terminal yet outside the set this node accepts. Two distinct
        // cases, and they need different advice, so they get different branches.
        if (live.getStatus() == RunStatus.AWAITING_SIGNAL) {
            // Parked on an approval, interface or wait node. Resolving the signal resumes the DAG,
            // which is what makes the run fireable again; it does not go straight back to
            // WAITING_TRIGGER, and RUNNING is accepted here anyway.
            return head + "it is parked on a blocking node and cannot accept a new fire until that"
                + " node is resolved. Resolve it with resolve_approval or continue_interface,"
                + " depending on which node is blocking, then call this sub-workflow again.";
        }
        if (!ACTIVE_STATUSES.contains(live.getStatus())) {
            // PENDING is the entity default, so this is typically a run created moments ago that has
            // not started yet. It is transient: retrying is the right move.
            return head + "a sub-workflow call only fires a run that is waiting for its trigger,"
                + " running, or paused. Status " + live.getStatus() + " is a transient state the run"
                + " has not left yet. Call again once it settles.";
        }

        // Cause 2: right status, wrong version. Only reachable when the workflow is pinned, because
        // the unpinned lookup does not filter on version at all.
        Integer pinnedVersion = entity.getPinnedVersion();
        Integer runVersion = live.getPlanVersion();
        if (pinnedVersion != null && !pinnedVersion.equals(runVersion)) {
            // Same remedy idiom the pin action itself emits when it needs a run at a given version
            // (see WorkflowCrudModule's NoSuccessfulRun branch), so the two stay consistent.
            String startAtPin = "Start a run with workflow(action='execute', id='" + workflowId
                + "', version=" + pinnedVersion + ").";
            if (runVersion == null) {
                return head + "carries no plan version, so it can never match the pinned version "
                    + pinnedVersion + ". " + startAtPin;
            }
            return head + "sits at plan version " + runVersion + " while the workflow is pinned to version "
                + pinnedVersion + ", and a sub-workflow call only fires the pinned version. " + startAtPin
                + " Re-pinning to version " + runVersion + " with workflow(action='pin', workflow_id='"
                + workflowId + "', version=" + runVersion + ") is the alternative, but only if that"
                + " version is the one you want in production: it redirects every production trigger of"
                + " this workflow (webhook, schedule, chained workflow) to it, and it fails outright if"
                + " this run is a clone of a published workflow, because clones cannot be pinned.";
        }

        // Cause 3: right status, and either unpinned or already at the pinned version, yet the lookup
        // still missed it. Only reachable when the two queries disagree, i.e. the run changed between
        // findActiveRun and this probe. Kept as an explicit branch rather than folded into the plain
        // message so the caller is told to retry rather than told to start a run that exists.
        return head + "the sub-workflow lookup did not select it, which means its state changed while"
            + " this node was resolving it. Call this sub-workflow again.";
    }

    /**
     * Resolves the trigger ID to fire on the sub-workflow.
     * Uses config.triggerId if set, otherwise finds the first fireable trigger.
     */
    private String resolveTriggerId(WorkflowPlan plan) {
        // If config has explicit triggerId, use it
        if (config != null && config.triggerId() != null && !config.triggerId().isBlank()) {
            return config.triggerId();
        }

        // Find first fireable trigger from the plan
        List<Trigger> triggers = plan.getTriggers();
        if (triggers == null || triggers.isEmpty()) {
            return null;
        }

        for (Trigger trigger : triggers) {
            String type = trigger.type();
            if (type != null && !UNFIREABLE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
                return trigger.getNormalizedKey();
            }
        }
        return null;
    }

    /**
     * Resolves the TriggerType for the given triggerId from the plan.
     */
    private TriggerType resolveTriggerType(WorkflowPlan plan, String triggerId) {
        List<Trigger> triggers = plan.getTriggers();
        if (triggers != null) {
            for (Trigger trigger : triggers) {
                if (trigger.getNormalizedKey().equals(triggerId)) {
                    try {
                        return TriggerType.fromString(trigger.type());
                    } catch (Exception e) {
                        // Fall through to default
                    }
                }
            }
        }
        return TriggerType.MANUAL;
    }

    /**
     * Collects outputs from step data for a specific epoch.
     * Loads raw output for each completed step in the epoch.
     */
    private Map<String, Object> collectEpochOutputs(String runId, int epoch, String tenantId) {
        Map<String, Object> outputs = new LinkedHashMap<>();

        if (workflowStepDataRepository == null || stepOutputService == null) {
            logger.warn("Cannot collect epoch outputs: step data repository or output service not injected");
            return outputs;
        }

        List<WorkflowStepDataRepository.EpochOutputProjection> outputRefs =
            workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(runId, epoch);
        for (WorkflowStepDataRepository.EpochOutputProjection outputRef : outputRefs) {
            try {
                Map<String, Object> stepOutput = stepOutputService.loadRawOutput(
                    outputRef.getOutputStorageId(), tenantId);
                if (stepOutput != null && !stepOutput.isEmpty()) {
                    outputs.put(outputRef.getStepAlias(), stepOutput);
                }
            } catch (Exception e) {
                logger.warn("Failed to load output for step {}: {}", outputRef.getStepAlias(), e.getMessage());
            }
        }
        return outputs;
    }

    /**
     * Gets the current sub-workflow recursion depth from context global data.
     */
    private int getCurrentDepth(ExecutionContext context) {
        return context.getGlobalData(DEPTH_KEY)
            .map(v -> {
                if (v instanceof Number) {
                    return ((Number) v).intValue();
                }
                return 0;
            })
            .orElse(0);
    }

    /**
     * Builds the workflow-id chain for cycle detection. The current workflow id
     * is added on first sub-workflow entry so direct self-calls fail fast.
     */
    private List<String> getCurrentAncestry(ExecutionContext context) {
        List<String> ancestry = new ArrayList<>();
        context.getGlobalData(ANCESTRY_KEY).ifPresent(raw -> {
            if (raw instanceof Collection<?> values) {
                for (Object value : values) {
                    addWorkflowIdIfPresent(ancestry, value);
                }
            } else {
                addWorkflowIdIfPresent(ancestry, raw);
            }
        });

        if (context.plan() != null) {
            addWorkflowIdIfPresent(ancestry, context.plan().getId());
        }
        return List.copyOf(ancestry);
    }

    private void addWorkflowIdIfPresent(List<String> ancestry, Object rawWorkflowId) {
        if (rawWorkflowId == null) {
            return;
        }
        String workflowId = String.valueOf(rawWorkflowId).trim();
        if (workflowId.isEmpty() || containsWorkflowId(ancestry, workflowId)) {
            return;
        }
        ancestry.add(workflowId);
    }

    private boolean containsWorkflowId(Collection<String> ancestry, String workflowId) {
        if (workflowId == null) {
            return false;
        }
        for (String ancestor : ancestry) {
            if (workflowId.equalsIgnoreCase(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private List<String> appendWorkflowId(List<String> ancestry, String workflowId) {
        List<String> childAncestry = new ArrayList<>(ancestry);
        addWorkflowIdIfPresent(childAncestry, workflowId);
        return List.copyOf(childAncestry);
    }

    private Map<String, Object> buildChildSubWorkflowGlobalData(int currentDepth, List<String> childAncestry) {
        Map<String, Object> globalData = new LinkedHashMap<>();
        globalData.put(DEPTH_KEY, currentDepth + 1);
        globalData.put(ANCESTRY_KEY, childAncestry);
        return globalData;
    }

    /**
     * Resolves the workflowId, which may be a SpEL expression.
     */
    private String resolveWorkflowId(ExecutionContext context) {
        String rawWorkflowId = config != null ? config.workflowId() : null;
        if (rawWorkflowId == null || rawWorkflowId.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__wfId__", rawWorkflowId);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__wfId__");
                return result != null ? String.valueOf(result) : rawWorkflowId;
            } catch (Exception e) {
                logger.warn("Failed to resolve workflowId expression '{}': {}",
                    rawWorkflowId, e.getMessage());
                return rawWorkflowId;
            }
        }

        return rawWorkflowId;
    }

    /**
     * Resolves the input data to pass to the sub-workflow.
     */
    private Map<String, Object> resolveInputData(ExecutionContext context) {
        String inputMapping = config != null ? config.inputMapping() : null;
        if (inputMapping == null || inputMapping.isBlank()) {
            // Default: pass trigger data as input
            return context.triggerData() != null ? new HashMap<>(context.triggerData()) : new HashMap<>();
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__input__", inputMapping);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__input__");
                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mapResult = (Map<String, Object>) result;
                    return new HashMap<>(mapResult);
                }
                // If resolved to a string or other type, wrap it
                Map<String, Object> wrapped = new HashMap<>();
                wrapped.put("data", result);
                return wrapped;
            } catch (Exception e) {
                logger.warn("Failed to resolve inputMapping '{}': {}", inputMapping, e.getMessage());
            }
        }

        // Fallback: pass trigger data
        return context.triggerData() != null ? new HashMap<>(context.triggerData()) : new HashMap<>();
    }

    private Map<String, Object> buildInputDataMap(String workflowId) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("workflowId", workflowId);
        if (config != null) {
            if (config.inputMapping() != null) inputData.put("inputMapping", config.inputMapping());
            inputData.put("timeoutSeconds", config.timeoutSeconds());
            inputData.put("maxDepth", config.maxDepth());
            if (config.triggerId() != null) inputData.put("triggerId", config.triggerId());
        }
        return inputData;
    }

    private static Object[] createSubRunLocks() {
        Object[] locks = new Object[SUB_RUN_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /**
     * Stripe lock for a sub-run id. Serializes concurrent fires of the SAME
     * sub-run (different threads, same id) so they don't race on its snapshot.
     *
     * <p>Edge case (accepted): a transitively-nested sub-workflow (recursion
     * capped at {@code config.maxDepth()}, default 5) could hash to the same
     * stripe (~1/64 per level) as a parent still inside its {@code synchronized}
     * block, parking the ForkJoinPool worker until the parent's
     * {@code get(timeoutSeconds)} returns. This is a timeout-bounded circular
     * wait - the parent holds the stripe monitor while blocked on its
     * {@code future.get()} - not a permanent deadlock; it self-heals at the
     * sub-workflow timeout (default 300s). The common case (same stripe,
     * different unrelated runs) is the intended serialization and is covered by
     * SubWorkflowNodeTest#serializesSameChildRunCallsAndReloadsRunBeforeFiring.
     */
    private static Object lockForSubRun(String subRunId) {
        int stripe = Math.floorMod(subRunId.hashCode(), SUB_RUN_LOCKS.length);
        return SUB_RUN_LOCKS[stripe];
    }

    // Getters
    public Core.SubWorkflowConfig getSubWorkflowConfig() { return config; }

    /**
     * Accepts services from the registry.
     * SubWorkflowNode needs WorkflowRepository, WorkflowRunRepository,
     * ReusableTriggerService, StepOutputService, and WorkflowStepDataRepository.
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.workflowRepository = registry.getWorkflowRepository();
        this.workflowRunRepository = registry.getWorkflowRunRepository();
        this.reusableTriggerService = registry.getReusableTriggerService();
        this.productionRunResolver = registry.getProductionRunResolver();
        this.stepOutputService = registry.getStepOutputService();
        this.workflowStepDataRepository = registry.getWorkflowStepDataRepository();
        this.workflowRedisPublisher = registry.getWorkflowRedisPublisher();
    }

    // ========================================================================
    // SERVICE INJECTION (via setters, like WaitNode pattern)
    // ========================================================================

    public void setWorkflowRepository(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    public void setWorkflowRunRepository(WorkflowRunRepository workflowRunRepository) {
        this.workflowRunRepository = workflowRunRepository;
    }

    public void setReusableTriggerService(ReusableTriggerService reusableTriggerService) {
        this.reusableTriggerService = reusableTriggerService;
    }

    public void setProductionRunResolver(
            com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver) {
        this.productionRunResolver = productionRunResolver;
    }

    public void setStepOutputService(StepOutputService stepOutputService) {
        this.stepOutputService = stepOutputService;
    }

    public void setWorkflowStepDataRepository(WorkflowStepDataRepository workflowStepDataRepository) {
        this.workflowStepDataRepository = workflowStepDataRepository;
    }

    // Package-private getters for testing
    WorkflowRepository getWorkflowRepository() { return workflowRepository; }
    WorkflowRunRepository getWorkflowRunRepository() { return workflowRunRepository; }
    ReusableTriggerService getReusableTriggerService() { return reusableTriggerService; }
    StepOutputService getStepOutputService() { return stepOutputService; }
    WorkflowStepDataRepository getWorkflowStepDataRepository() { return workflowStepDataRepository; }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder {
        private String nodeId;
        private Core.SubWorkflowConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder subWorkflowConfig(Core.SubWorkflowConfig config) { this.config = config; return this; }
        public SubWorkflowNode build() { return new SubWorkflowNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}
