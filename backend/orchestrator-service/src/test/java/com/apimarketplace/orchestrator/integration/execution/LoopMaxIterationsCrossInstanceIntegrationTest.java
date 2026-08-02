package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CROSS-INSTANCE LOOP CEILING - horizontal-scaling invariant test for {@code maxIterations}.
 *
 * <p><b>The prod incident this pins (2026-07-30, run_<id>).</b> A while loop
 * configured {@code maxIterations=60} executed 73+ body iterations. Its body holds a 15s Wait, so
 * every iteration yields a {@code WAIT_TIMER} signal and the back-edge is advanced from the
 * SIGNAL-RESUME path; resumes are dispatched cross-instance, and the iteration counter
 * ({@code BackEdgeState}) lived only in {@code V2StepByStepContextManager}'s per-JVM
 * {@code globalDataCache}. Each of the 2 replicas therefore counted only ITS OWN iterations and
 * enforced the ceiling against that, so the effective ceiling became
 * {@code replicas x maxIterations}. From the incident logs, same run, same edge, same instant:
 * pod A {@code iteration=39/60} while pod B reported {@code iteration=59/60}.
 *
 * <p><b>How the instance boundary is simulated (strongest feasible in one JVM).</b> Every advance
 * is driven with a FRESH {@link ExecutionContext} carrying no {@code back_edge_state:*} globalData
 * - which is exactly what a replica that has never advanced this loop reconstructs from the DB.
 * The only thing shared between advances is Redis, i.e. the infrastructure a real second pod
 * would also see. Pre-fix, every advance read {@code state == null}, restarted at iteration 0, and
 * the loop never terminated.
 *
 * <p><b>Why this also guards the "inert fix" failure mode.</b> The first version of the fix
 * carried {@code @ConditionalOnBean(StringRedisTemplate.class)} on a {@code @Component}. Component
 * scan is evaluated BEFORE auto-configured {@code @Bean} methods are registered, so the condition
 * silently never matched, the bean was never created, {@code BackEdgeHandler} injected null, and
 * every cross-replica guard short-circuited with no error and no log - the whole fix was a no-op
 * in production while all unit tests stayed green. Only a real Spring context can catch that,
 * which is what {@link #storeIsWiredIntoTheHandler()} asserts.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@DisplayName("Cross-instance loop ceiling - maxIterations over shared Redis")
@Testcontainers(disabledWithoutDocker = true)
class LoopMaxIterationsCrossInstanceIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final int MAX_ITERATIONS = 3;
    /** Well above replicas x maxIterations, so a runaway loop trips this instead of hanging CI. */
    private static final int RUNAWAY_GUARD = 40;

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired private BackEdgeHandler backEdgeHandler;
    @Autowired private RedisLoopIterationStore loopIterationStore;
    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    @Timeout(60)
    @DisplayName("the Redis loop store bean exists and IS injected into BackEdgeHandler")
    void storeIsWiredIntoTheHandler() throws Exception {
        assertThat(loopIterationStore)
            .as("the store must be a real bean in a real context - a @ConditionalOnBean here would "
                + "silently never match and make the entire cross-replica fix inert")
            .isNotNull();
        assertThat(loopIterationStore.isEnabled())
            .as("with Redis configured the store must be live, not degraded to per-JVM")
            .isTrue();

        Field field = BackEdgeHandler.class.getDeclaredField("loopIterationStore");
        field.setAccessible(true);
        assertThat(field.get(backEdgeHandler))
            .as("BackEdgeHandler takes the store through an optional constructor param - if Spring "
                + "cannot supply the bean it injects null and every guard short-circuits silently")
            .isSameAs(loopIterationStore);
    }

    /**
     * The regression itself. Pre-fix this loops until {@link #RUNAWAY_GUARD} and fails; post-fix it
     * terminates at exactly {@code maxIterations} body runs with {@code max_iterations_reached}.
     */
    @Test
    @Timeout(120)
    @DisplayName("regression: maxIterations holds when every advance lands on a different instance")
    void ceilingHoldsAcrossInstanceBoundaries() {
        String runId = "run-loop-xinstance-" + UUID.randomUUID();
        WorkflowPlan plan = loopPlan();
        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getRunId()).thenReturn(runId);
        when(execution.getPlan()).thenReturn(plan);

        ExecutionNode bodyTail = mock(ExecutionNode.class);
        NodeExecutionResult tailResult = NodeExecutionResult.success("mcp:check", Map.of());

        int bodyReEntries = 0;
        String exitTarget = null;
        for (int advance = 0; advance < RUNAWAY_GUARD; advance++) {
            // FRESH context every time: no back_edge_state globalData, exactly what a replica that
            // never advanced this loop reconstructs. Redis is the only carry-over.
            ExecutionContext ctx = ExecutionContext.create(
                runId, UUID.randomUUID().toString(), "tenant-1", "0", 0, "trigger:start", 1, 0,
                Map.of(), plan);

            StepByStepExecutionResult result = backEdgeHandler.executeBackEdgeIteration(
                bodyTail, "mcp:check", tailResult, ctx, execution, null,
                TriggerItem.create("0", 0, Map.of()), 0, Map.of());

            if (result.readyNodes().contains("mcp:poll")) {
                bodyReEntries++;
                continue;
            }
            if (result.readyNodes().contains("mcp:after")) {
                exitTarget = "mcp:after";
            }
            break;
        }

        assertThat(exitTarget)
            .as("the loop must terminate and hand over to its exit target - pre-fix each advance "
                + "restarted the count at 0 from the fresh context and the loop never ended")
            .isEqualTo("mcp:after");
        assertThat(bodyReEntries)
            .as("maxIterations=%d counts the loop's OWN initial body entry (iteration 0), so the "
                + "back-edge may re-enter the body exactly %d more times",
                MAX_ITERATIONS, MAX_ITERATIONS - 1)
            .isEqualTo(MAX_ITERATIONS - 1);

        String edgeId = "mcp:check->core:my_loop:iterate";
        RedisLoopIterationStore.LoopProgress shared = loopIterationStore.readProgress(runId, 1, edgeId);
        assertThat(shared).as("the ceiling must be enforced from SHARED state, not a JVM field").isNotNull();
        assertThat(shared.terminated()).isTrue();
        assertThat(shared.iteration()).isEqualTo(MAX_ITERATIONS - 1);
        assertThat(redisTemplate.hasKey(RedisLoopIterationStore.iterKey(runId, 1, edgeId)))
            .as("the counter genuinely lives in Redis where every replica reads it")
            .isTrue();
    }

    /**
     * trigger:start -> core:my_loop (loop, maxIterations=3)
     *   body: -> mcp:poll -> mcp:check -> back to core:my_loop (iterate)
     *   exit: -> mcp:after
     */
    private WorkflowPlan loopPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", UUID.randomUUID().toString());
        data.put("tenant_id", "tenant-1");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "start", "type", "manual", "strategy", "single")));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "poll", "type", "mcp"),
            Map.of("id", "s2", "label", "check", "type", "mcp"),
            Map.of("id", "s3", "label", "after", "type", "mcp")));
        data.put("cores", List.of(
            Map.of("id", "c1", "label", "my_loop", "type", "loop",
                   "loopCondition", "true", "maxIterations", MAX_ITERATIONS)));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "core:my_loop"),
            Map.of("from", "core:my_loop:body", "to", "mcp:poll"),
            Map.of("from", "mcp:poll", "to", "mcp:check"),
            Map.of("from", "mcp:check", "to", "core:my_loop:iterate"),
            Map.of("from", "core:my_loop:exit", "to", "mcp:after")));
        return WorkflowPlan.fromMap(data);
    }
}
