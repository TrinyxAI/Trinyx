package com.apimarketplace.orchestrator.execution.v2.adhoc;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.CoreNodeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionServiceInjector;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.credit.NodeCreditGate;
import com.apimarketplace.orchestrator.services.persistence.OutputSchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes ONE workflow node in isolation, from a configuration, with no persisted workflow
 * and no run.
 *
 * <p><b>Why this exists.</b> Before it, the smallest thing an agent could execute was a whole
 * workflow: build it, save it, fire a trigger, then read the run back. Trying a single node
 * (does this IMAP config actually read the mailbox? does this SQL return what I think?) meant
 * creating and later cleaning up a real workflow. This runs the node and hands the output back
 * in the same call.
 *
 * <p><b>What it deliberately does NOT do.</b> It does not go through
 * {@code UnifiedExecutionEngine.executeSingleNode}, because that resolves the node from the
 * tree's ROOTS and the roots are the triggers: reaching a lone node would mean synthesising a
 * trigger and an edge, which drags in {@code canExecute}, the merge/skip cascade and the
 * split machinery, none of which mean anything for one node. It builds the node with the same
 * {@link CoreNodeBuilder} the engine uses, injects the same services, and calls
 * {@code execute(context)} directly. What that gives up is stated on {@link #execute}.
 *
 * <p>Nothing is written to {@code workflow_runs}, {@code workflow_step_data} or the state
 * snapshot BY THIS PATH. The only trace is the tool result the caller persists, which is the
 * point: this is a probe, not an execution the platform is expected to remember. Node types that
 * would break that (today: {@code sub_workflow}, which fires a real child run) are refused by
 * {@link AdHocNodeTypeResolver} rather than quietly making the sentence above false.
 */
@Service
public class AdHocNodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AdHocNodeExecutionService.class);

    /**
     * Hard ceiling on one ad-hoc node. A chat turn is synchronous all the way to the model, so a
     * node that hangs would hang the conversation.
     *
     * <p>It bounds the WAIT, not the work. Blocking socket I/O (JDBC, SSH, SMTP, HTTP) does not
     * answer to interruption, so a node stuck in a read keeps its thread until the read returns.
     * The caller is released and told so; the pool is daemon and shut down with the service.
     */
    static final long TIMEOUT_SECONDS = 120L;

    /** Prefix that marks a run id as ad-hoc. Never a UUID, so it cannot be mistaken for a run. */
    static final String RUN_ID_PREFIX = "adhoc-";

    private final CoreNodeBuilder coreNodeBuilder;
    private final ExecutionServiceInjector serviceInjector;
    private final OutputSchemaMapper outputSchemaMapper;

    /**
     * The same per-node credit gate the engine applies to EVERY node, trigger nodes included.
     * Running outside the engine is not a reason to run for free: without this, an exhausted
     * workspace could keep spending on paid providers one probe at a time.
     */
    private final NodeCreditGate nodeCreditGate;

    /**
     * Single-thread-per-call executor so the timeout can actually interrupt. A node that
     * ignores interruption still leaks its thread, which is why the pool is cached and
     * daemon: a leaked probe must never keep the JVM alive.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "adhoc-node");
        t.setDaemon(true);
        return t;
    });

    public AdHocNodeExecutionService(CoreNodeBuilder coreNodeBuilder,
                                     ExecutionServiceInjector serviceInjector,
                                     OutputSchemaMapper outputSchemaMapper,
                                     NodeCreditGate nodeCreditGate) {
        this.coreNodeBuilder = coreNodeBuilder;
        this.serviceInjector = serviceInjector;
        this.outputSchemaMapper = outputSchemaMapper;
        this.nodeCreditGate = nodeCreditGate;
    }

    /**
     * Build and run one node.
     *
     * <p>Relative to a node running inside a real workflow this loses: the start/finish
     * events (nothing is streaming this), the split fan-out and the merge/skip cascade (there
     * is no DAG), the per-node retry / continueOnFailure policy (it belongs to a plan), and
     * any persistence of the output. Everything the node itself does - credentials, templates,
     * external calls, output shape - is identical.
     *
     * @param request what to run and with what
     * @return the outcome, never null; failures are values, not exceptions
     */
    public AdHocNodeResult execute(AdHocNodeRequest request) {
        long startedAt = System.currentTimeMillis();
        // Two identifiers on purpose. The PLAN id has to be a real UUID: WorkflowPlanParser
        // validates it and quietly substitutes a random one otherwise, so a prefixed value would
        // be thrown away without anyone noticing. The RUN id is free-form and carries the prefix,
        // which is what makes an ad-hoc execution recognisable wherever a run id surfaces.
        String planId = UUID.randomUUID().toString();
        String runId = RUN_ID_PREFIX + planId;

        final ExecutionNode node;
        try {
            node = buildNode(request, planId);
        } catch (Exception e) {
            log.warn("Ad-hoc node build failed for type={}: {}", request.nodeType(), e.toString());
            return AdHocNodeResult.buildFailure(request.nodeType(), messageOf(e),
                    System.currentTimeMillis() - startedAt);
        }

        // Credit gate BEFORE any side effect, exactly where the engine applies it.
        NodeExecutionResult denied = nodeCreditGate != null
                ? nodeCreditGate.denyOrNull(request.nodeKey(), request.tenantId())
                : null;
        if (denied != null) {
            return toResult(request, denied, System.currentTimeMillis() - startedAt);
        }

        ExecutionContext context = buildContext(request, runId, planId);

        // Re-bind the workspace scope INSIDE the worker. ToolsRegistrationService wraps the tool
        // call in runWithOrgScope precisely because every service-layer role gate reads it from
        // a thread-local and silently falls to its null-role branch otherwise; hopping to this
        // pool drops that binding, and withOrganization on the context only reaches code that
        // reads the context.
        Future<NodeExecutionResult> future = executor.submit((Callable<NodeExecutionResult>) () -> {
            java.util.concurrent.atomic.AtomicReference<NodeExecutionResult> holder =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            TenantResolver.runWithOrgScope(request.organizationId(), request.organizationRole(), () -> {
                try {
                    holder.set(node.execute(context));
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
            if (failure.get() != null) throw failure.get();
            return holder.get();
        });
        try {
            NodeExecutionResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return toResult(request, result, System.currentTimeMillis() - startedAt);
        } catch (TimeoutException te) {
            future.cancel(true);
            return AdHocNodeResult.timeout(request.nodeType(), TIMEOUT_SECONDS,
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return AdHocNodeResult.executionFailure(request.nodeType(), "Interrupted",
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Ad-hoc node {} threw: {}", request.nodeType(), cause.toString());
            return AdHocNodeResult.executionFailure(request.nodeType(), messageOf(cause),
                    System.currentTimeMillis() - startedAt);
        }
    }

    // ── building ────────────────────────────────────────────────────────────────────────

    /**
     * Build the single node through the engine's own builder, so an ad-hoc node is byte-for-byte
     * the node a workflow would have built from the same config. Reimplementing construction
     * here would let the two drift, which is exactly the class of bug this surface exists to
     * help find.
     */
    private ExecutionNode buildNode(AdHocNodeRequest request, String planId) {
        WorkflowPlan plan = syntheticPlan(request, planId);
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        coreNodeBuilder.createCoreNodes(nodeMap, plan, Map.of());
        serviceInjector.injectServices(nodeMap);

        if (nodeMap.isEmpty()) {
            throw new IllegalStateException(
                    "Node type '" + request.nodeType() + "' cannot run standalone: only core workflow nodes "
                            + "can. Agents, catalog tools (a UUID), triggers, interfaces and table operations "
                            + "have their own tools - use agent(action='execute'), catalog(action='execute'), "
                            + "or table(...) instead. If this IS a core node, its configuration produced nothing "
                            + "the engine could build.");
        }
        if (nodeMap.size() > 1) {
            // Defensive: the plan holds exactly one core, so more than one node means a builder
            // fanned out. Better to refuse than to pick one arbitrarily.
            throw new IllegalStateException(
                    "Node type '" + request.nodeType() + "' expanded into " + nodeMap.size() + " nodes; "
                            + "only single-node types can run standalone.");
        }
        return nodeMap.values().iterator().next();
    }

    /**
     * A plan holding exactly one core.
     *
     * <p>The plan is NOT optional and must never be null: several nodes dereference
     * {@code context.plan().getId()} to namespace the files they produce, some of them inside
     * a local try/catch, so a null plan does not fail loudly - it silently drops the file from
     * the output.
     */
    private WorkflowPlan syntheticPlan(AdHocNodeRequest request, String planId) {
        Map<String, Object> core = new LinkedHashMap<>();
        // Node config lives under the type's nested key, exactly where WorkflowPlanParser reads
        // it. Depositing it at the core's top level is the silent-empty-node failure mode.
        if (request.configKey() != null) {
            core.put(request.configKey(), new LinkedHashMap<>(request.config()));
        } else {
            core.putAll(request.config());
        }
        // Identity written LAST, so a config with its own `type`/`id`/`label` cannot decide what
        // the node is. For a type whose config lives at the core's top level this is the whole
        // defence: putAll would otherwise overwrite the type the gates were asked about.
        core.put("id", request.nodeKey());
        core.put("label", request.label());
        core.put("type", request.nodeType());

        Map<String, Object> planData = new LinkedHashMap<>();
        planData.put("id", planId);
        planData.put("tenant_id", request.tenantId());
        planData.put("name", "ad-hoc " + request.nodeType());
        planData.put("triggers", List.of());
        planData.put("steps", List.of());
        planData.put("cores", List.of(core));
        planData.put("edges", List.of());

        return WorkflowPlan.fromMap(planData, planId, request.tenantId());
    }

    /**
     * The execution context.
     *
     * <p>{@code workflowRunId} stays null on purpose. It is what downstream billing reads to
     * choose its scope: a synthetic value there would create pricing pins pointing at a run
     * that does not exist and that nothing will ever close.
     *
     * <p>{@code runInput} feeds BOTH the trigger data and the step outputs so that a template
     * written for a real workflow resolves the same way here - that is what makes a probe
     * faithful rather than merely green.
     */
    private ExecutionContext buildContext(AdHocNodeRequest request, String runId, String planId) {
        Map<String, Object> runInput = request.runInput() != null ? request.runInput() : Map.of();
        ExecutionContext context = ExecutionContext.create(
                runId,
                null,
                request.tenantId(),
                null,
                0,
                null,
                0,
                0,
                new LinkedHashMap<>(runInput),
                syntheticPlan(request, planId));

        for (Map.Entry<String, Object> e : runInput.entrySet()) {
            context.stepOutputs().put(e.getKey(), e.getValue());
        }
        return context.withOrganization(request.organizationId(), request.organizationRole());
    }

    // ── result shaping ──────────────────────────────────────────────────────────────────

    /**
     * Shape the output the way a real workflow would persist it, so a config validated here can
     * be pasted into {@code add_node} and reference the same field names. Without the mapper the
     * agent would learn the raw names and write templates that resolve to nothing at runtime.
     */
    private AdHocNodeResult toResult(AdHocNodeRequest request, NodeExecutionResult result, long durationMs) {
        Map<String, Object> raw = result.output() != null ? result.output() : Map.of();
        Map<String, Object> output;
        try {
            output = outputSchemaMapper.transformToDbSchema(raw, request.nodeType());
        } catch (Exception e) {
            log.debug("Output mapping failed for {}, returning raw output: {}", request.nodeType(), e.toString());
            output = new LinkedHashMap<>(raw);
        }
        return AdHocNodeResult.of(request.nodeType(), result, output, durationMs);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
