package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for V2StepByStepContextManager.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V2StepByStepContextManager")
class V2StepByStepContextManagerTest {

    @Mock private ExecutionCacheManager mockCacheManager;
    @Mock private RunContextService mockRunContextService;
    @Mock private TriggerEpochManager mockEpochManager;
    @Mock private StateSnapshotService mockStateSnapshotService;
    @Mock private DAGIndependenceValidator mockDagValidator;
    @Mock private ExecutionTree mockTree;
    @Mock private WorkflowExecution mockExecution;

    private V2StepByStepContextManager contextManager;

    @BeforeEach
    void setUp() {
        contextManager = new V2StepByStepContextManager(
            mockCacheManager, mockRunContextService, mockEpochManager,
            mockStateSnapshotService, mockDagValidator
        );
    }

    @Nested
    @DisplayName("tree operations (delegates)")
    class TreeOperations {

        @Test
        @DisplayName("getTree should delegate to executionCacheManager")
        void getTreeShouldDelegate() {
            when(mockCacheManager.getTree("run-1")).thenReturn(mockTree);

            ExecutionTree result = contextManager.getTree("run-1");

            assertSame(mockTree, result);
            verify(mockCacheManager).getTree("run-1");
        }

        @Test
        @DisplayName("hasTree should delegate to executionCacheManager")
        void hasTreeShouldDelegate() {
            when(mockCacheManager.hasTree("run-1")).thenReturn(true);

            assertTrue(contextManager.hasTree("run-1"));
            verify(mockCacheManager).hasTree("run-1");
        }

        @Test
        @DisplayName("hasTree should return false when tree not present")
        void hasTreeShouldReturnFalse() {
            when(mockCacheManager.hasTree("run-1")).thenReturn(false);

            assertFalse(contextManager.hasTree("run-1"));
        }
    }

    @Nested
    @DisplayName("execution operations (delegates)")
    class ExecutionOperations {

        @Test
        @DisplayName("getExecution should delegate to executionCacheManager")
        void getExecutionShouldDelegate() {
            when(mockCacheManager.getExecution("run-1")).thenReturn(mockExecution);

            WorkflowExecution result = contextManager.getExecution("run-1");

            assertSame(mockExecution, result);
            verify(mockCacheManager).getExecution("run-1");
        }

        @Test
        @DisplayName("getExecution should return null when not found")
        void getExecutionShouldReturnNull() {
            when(mockCacheManager.getExecution("run-1")).thenReturn(null);

            assertNull(contextManager.getExecution("run-1"));
        }
    }

    @Nested
    @DisplayName("trigger items cache")
    class TriggerItemsCache {

        @Test
        @DisplayName("hasTriggerItems should return false when not cached")
        void shouldReturnFalseWhenNotCached() {
            assertFalse(contextManager.hasTriggerItems("run-1"));
        }

        @Test
        @DisplayName("should cache and retrieve trigger items")
        void shouldCacheAndRetrieve() {
            List<Map<String, Object>> items = List.of(
                Map.of("key1", "val1"),
                Map.of("key2", "val2")
            );

            contextManager.cacheTriggerItems("run-1", items);

            assertTrue(contextManager.hasTriggerItems("run-1"));
            List<Map<String, Object>> retrieved = contextManager.getTriggerItems("run-1");
            assertNotNull(retrieved);
            assertEquals(2, retrieved.size());
        }

        @Test
        @DisplayName("should return defensive copy of cached items")
        void shouldReturnDefensiveCopy() {
            List<Map<String, Object>> items = new ArrayList<>();
            items.add(new HashMap<>(Map.of("key", "val")));

            contextManager.cacheTriggerItems("run-1", items);

            // Modify original list
            items.clear();

            // Cached version should be unaffected
            List<Map<String, Object>> retrieved = contextManager.getTriggerItems("run-1");
            assertNotNull(retrieved);
            assertEquals(1, retrieved.size());
        }

        @Test
        @DisplayName("getTriggerItems should return null for unknown runId")
        void shouldReturnNullForUnknown() {
            assertNull(contextManager.getTriggerItems("unknown-run"));
        }
    }

    @Nested
    @DisplayName("getTriggerDataForItem")
    class GetTriggerDataForItem {

        @Test
        @DisplayName("should return empty map when no trigger items cached")
        void shouldReturnEmptyWhenNoCached() {
            Map<String, Object> result = contextManager.getTriggerDataForItem("run-1", 0);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty map for empty items list")
        void shouldReturnEmptyForEmptyList() {
            contextManager.cacheTriggerItems("run-1", List.of());

            Map<String, Object> result = contextManager.getTriggerDataForItem("run-1", 0);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return trigger data for valid index")
        void shouldReturnDataForValidIndex() {
            List<Map<String, Object>> items = List.of(
                Map.of("field", "first"),
                Map.of("field", "second")
            );
            contextManager.cacheTriggerItems("run-1", items);

            Map<String, Object> result = contextManager.getTriggerDataForItem("run-1", 1);
            assertEquals("second", result.get("field"));
        }

        @Test
        @DisplayName("should return empty map for negative index")
        void shouldReturnEmptyForNegativeIndex() {
            contextManager.cacheTriggerItems("run-1", List.of(Map.of("f", "v")));

            Map<String, Object> result = contextManager.getTriggerDataForItem("run-1", -1);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should fall back to single item when index out of bounds (split sub-item path)")
        void shouldFallBackToSingleItemForOutOfBounds() {
            // Rationale: on the async split path, SplitAwareNodeExecutor.enrichContextWithItem
            // calls context.withItemIndex(subItemIndex) which clobbers itemId/itemIndex with
            // the split sub-item index. When an async agent completion rebuilds context off
            // the sub-item id (e.g., "3"), parseItemIndex hands back 3 and we look up trigger
            // item 3 - which doesn't exist if the trigger only emitted one item. Trigger data
            // is per-firing and shared by all split sub-items, so items.get(0) is correct.
            contextManager.cacheTriggerItems("run-1", List.of(Map.of("f", "v")));

            Map<String, Object> result = contextManager.getTriggerDataForItem("run-1", 5);
            assertEquals("v", result.get("f"));
        }

        @Test
        @DisplayName("should return empty map for out-of-bounds on multi-item trigger (no parent tracking)")
        void shouldReturnEmptyForOutOfBoundsOnMultiItemTrigger() {
            // Multi-trigger-item + OOB is ambiguous - we can't guess which parent item the
            // sub-item belongs to, so return empty and let the caller detect the degraded
            // state. Parent tracking is not implemented for this scenario.
            contextManager.cacheTriggerItems("run-1", List.of(
                Map.of("f", "v1"),
                Map.of("f", "v2")
            ));

            Map<String, Object> result = contextManager.getTriggerDataForItem("run-1", 5);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return defensive copy")
        void shouldReturnDefensiveCopyOfTriggerData() {
            contextManager.cacheTriggerItems("run-1", List.of(Map.of("key", "value")));

            Map<String, Object> result1 = contextManager.getTriggerDataForItem("run-1", 0);
            result1.put("extra", "modified");

            Map<String, Object> result2 = contextManager.getTriggerDataForItem("run-1", 0);
            assertFalse(result2.containsKey("extra"));
        }
    }

    @Nested
    @DisplayName("cleanup")
    class Cleanup {

        @Test
        @DisplayName("should remove trigger items on cleanup")
        void shouldRemoveTriggerItems() {
            contextManager.cacheTriggerItems("run-1", List.of(Map.of("k", "v")));
            assertTrue(contextManager.hasTriggerItems("run-1"));

            contextManager.cleanup("run-1");

            assertFalse(contextManager.hasTriggerItems("run-1"));
        }

        @Test
        @DisplayName("should handle cleanup of non-existent run gracefully")
        void shouldHandleCleanupOfNonExistent() {
            // Should not throw
            contextManager.cleanup("non-existent");
        }
    }

    @Nested
    @DisplayName("RunScopedCache implementation")
    class RunScopedCacheImpl {

        @Test
        @DisplayName("cleanupRun should delegate to cleanup")
        void cleanupRunShouldDelegate() {
            contextManager.cacheTriggerItems("run-1", List.of(Map.of("k", "v")));

            contextManager.cleanupRun("run-1");

            assertFalse(contextManager.hasTriggerItems("run-1"));
        }

        @Test
        @DisplayName("getCacheName should return correct name")
        void getCacheNameShouldReturnCorrectName() {
            assertEquals("V2StepByStepContextCache", contextManager.getCacheName());
        }

        @Test
        @DisplayName("getDomain should return EXECUTION")
        void getDomainShouldReturnExecution() {
            assertEquals(RunScopedCache.CacheDomain.EXECUTION, contextManager.getDomain());
        }

        @Test
        @DisplayName("getCacheSize should return trigger items count")
        void getCacheSizeShouldReturnCount() {
            assertEquals(0, contextManager.getCacheSize());

            contextManager.cacheTriggerItems("run-1", List.of(Map.of("k", "v")));
            assertEquals(1, contextManager.getCacheSize());

            contextManager.cacheTriggerItems("run-2", List.of(Map.of("k", "v")));
            assertEquals(2, contextManager.getCacheSize());
        }

        @Test
        @DisplayName("getCacheSize should decrease after cleanup")
        void getCacheSizeShouldDecreaseAfterCleanup() {
            contextManager.cacheTriggerItems("run-1", List.of(Map.of("k", "v")));
            contextManager.cacheTriggerItems("run-2", List.of(Map.of("k", "v")));
            assertEquals(2, contextManager.getCacheSize());

            contextManager.cleanup("run-1");
            assertEquals(1, contextManager.getCacheSize());
        }
    }

    // =========================================================================
    // Multi-DAG Epoch Resolution Tests (FIX verification)
    // =========================================================================

    @Nested
    @DisplayName("Multi-DAG Epoch Resolution")
    class MultiDagEpochResolutionTests {

        private WorkflowPlan emptyPlan() {
            return new WorkflowPlan("test", "tenant-1",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        private WorkflowPlan multiDagPlan() {
            // 2 triggers = multi-DAG workflow
            Trigger dag1 = new Trigger("orders", "orders", "single", "webhook");
            Trigger dag2 = new Trigger("payments", "payments", "single", "webhook");
            return new WorkflowPlan("test", "tenant-1",
                List.of(dag1, dag2), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        private WorkflowPlan singleDagPlan() {
            Trigger t1 = new Trigger("start", "start", "single", "webhook");
            return new WorkflowPlan("test", "tenant-1",
                List.of(t1), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        @Test
        @DisplayName("FIX: multi-DAG uses per-DAG epoch for trigger node (not global)")
        void multiDagUsesPerDagEpochForTriggerNode() {
            String runId = "run-multi";
            String tenantId = "tenant-1";

            when(mockTree.runId()).thenReturn(runId);
            when(mockTree.tenantId()).thenReturn(tenantId);
            when(mockTree.workflowRunId()).thenReturn("wfr-1");
            when(mockTree.plan()).thenReturn(multiDagPlan());

            contextManager.cacheTriggerItems(runId, List.of(Map.of("source", "dag2_trigger")));

            // Global epoch assigned to trigger:payments' latest fire = 5 (not dagEpoch=1, not global max=3)
            when(mockEpochManager.getGlobalEpochForDag(runId, "trigger:payments")).thenReturn(5);

            // Per-item path: V2StepByStepContextManager passes itemIndex → loadRunContextForItem.
            when(mockRunContextService.loadRunContextForItem(eq(runId), eq(tenantId), anyInt(), anyInt(), anyInt()))
                .thenReturn(new HashMap<>());
            when(mockStateSnapshotService.getSnapshot(runId))
                .thenReturn(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty());

            // Act: create context for trigger:payments node (trigger node = its own owner)
            contextManager.getOrCreateContextWithTriggerData(
                "ctx-key", mockTree, "item-0", 0, "trigger:payments");

            // Verify: loadRunContextForItem called with the global epoch assigned to this DAG's fire
            verify(mockRunContextService).loadRunContextForItem(runId, tenantId, 5, 0, 0);
            // Global getCurrentEpoch(runId) should NOT be called
            verify(mockEpochManager, never()).getCurrentEpoch(runId);
        }

        @Test
        @DisplayName("FIX: multi-DAG uses per-DAG epoch for owned node via DAGIndependenceValidator")
        void multiDagUsesPerDagEpochForOwnedNode() {
            String runId = "run-multi";
            String tenantId = "tenant-1";

            when(mockTree.runId()).thenReturn(runId);
            when(mockTree.tenantId()).thenReturn(tenantId);
            when(mockTree.workflowRunId()).thenReturn("wfr-1");
            when(mockTree.plan()).thenReturn(multiDagPlan());

            contextManager.cacheTriggerItems(runId, List.of(Map.of("source", "dag2_trigger")));

            // DAGIndependenceValidator finds that mcp:process_payment belongs to trigger:payments
            when(mockDagValidator.findOwnerTrigger(any(WorkflowPlan.class), eq("mcp:process_payment")))
                .thenReturn(Optional.of("trigger:payments"));

            // Global epoch assigned to trigger:payments' latest fire = 5
            when(mockEpochManager.getGlobalEpochForDag(runId, "trigger:payments")).thenReturn(5);

            // Per-item path: V2StepByStepContextManager passes itemIndex → loadRunContextForItem.
            when(mockRunContextService.loadRunContextForItem(eq(runId), eq(tenantId), anyInt(), anyInt(), anyInt()))
                .thenReturn(new HashMap<>());
            when(mockStateSnapshotService.getSnapshot(runId))
                .thenReturn(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty());

            // Act: create context for mcp:process_payment (owned by trigger:payments)
            contextManager.getOrCreateContextWithTriggerData(
                "ctx-key", mockTree, "item-0", 0, "mcp:process_payment");

            // Verify: global epoch for this DAG's fire was used (per-item path with itemIndex=0)
            verify(mockRunContextService).loadRunContextForItem(runId, tenantId, 5, 0, 0);
            // Called twice: once for epoch resolution, once for dagTriggerId injection
            verify(mockDagValidator, times(2)).findOwnerTrigger(any(WorkflowPlan.class), eq("mcp:process_payment"));
        }

        @Test
        @DisplayName("FIX: null nodeId falls back to global epoch (getReadyNodes case)")
        void nullNodeIdFallsBackToGlobalEpoch() {
            String runId = "run-multi";
            String tenantId = "tenant-1";

            when(mockTree.runId()).thenReturn(runId);
            when(mockTree.tenantId()).thenReturn(tenantId);
            when(mockTree.workflowRunId()).thenReturn("wfr-1");
            when(mockTree.plan()).thenReturn(multiDagPlan());

            contextManager.cacheTriggerItems(runId, List.of(Map.of("data", "val")));

            // Global epoch = 3 (max of all DAGs)
            when(mockEpochManager.getCurrentEpoch(runId)).thenReturn(3);

            // Per-item path: V2StepByStepContextManager passes itemIndex → loadRunContextForItem.
            when(mockRunContextService.loadRunContextForItem(eq(runId), eq(tenantId), anyInt(), anyInt(), anyInt()))
                .thenReturn(new HashMap<>());
            when(mockStateSnapshotService.getSnapshot(runId))
                .thenReturn(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty());

            // Act: null nodeId (like getReadyNodes which calculates globally)
            contextManager.getOrCreateContextWithTriggerData(
                "ctx-key", mockTree, "item-0", 0, null);

            // Verify: global epoch=3 used (correct for global calculation, per-item path with itemIndex=0)
            verify(mockRunContextService).loadRunContextForItem(runId, tenantId, 3, 0, 0);
        }

        @Test
        @DisplayName("Single-DAG: uses global epoch regardless of nodeId")
        void singleDagUsesGlobalEpoch() {
            String runId = "run-single";
            String tenantId = "tenant-1";

            when(mockTree.runId()).thenReturn(runId);
            when(mockTree.tenantId()).thenReturn(tenantId);
            when(mockTree.workflowRunId()).thenReturn("wfr-1");
            when(mockTree.plan()).thenReturn(singleDagPlan());

            contextManager.cacheTriggerItems(runId, List.of(Map.of("data", "val")));

            // Single DAG: per-DAG epoch for the single trigger = 2
            when(mockEpochManager.getGlobalEpochForDag(runId, "trigger:start")).thenReturn(2);
            // Per-item path: V2StepByStepContextManager passes itemIndex → loadRunContextForItem.
            when(mockRunContextService.loadRunContextForItem(eq(runId), eq(tenantId), anyInt(), anyInt(), anyInt()))
                .thenReturn(new HashMap<>());
            when(mockStateSnapshotService.getSnapshot(runId))
                .thenReturn(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty());

            // Act: even with nodeId, single-DAG uses global epoch
            contextManager.getOrCreateContextWithTriggerData(
                "ctx-key", mockTree, "item-0", 0, "mcp:step1");

            // Verify: global epoch=2 (single trigger, no per-DAG resolution needed, per-item path with itemIndex=0)
            verify(mockRunContextService).loadRunContextForItem(runId, tenantId, 2, 0, 0);
        }

        @Test
        @DisplayName("FIX: orphan node (no owner trigger) falls back to global epoch")
        void orphanNodeFallsBackToGlobalEpoch() {
            String runId = "run-multi";
            String tenantId = "tenant-1";

            when(mockTree.runId()).thenReturn(runId);
            when(mockTree.tenantId()).thenReturn(tenantId);
            when(mockTree.workflowRunId()).thenReturn("wfr-1");
            when(mockTree.plan()).thenReturn(multiDagPlan());

            contextManager.cacheTriggerItems(runId, List.of(Map.of("data", "val")));

            // DAGIndependenceValidator cannot find owner for this orphan node
            when(mockDagValidator.findOwnerTrigger(any(WorkflowPlan.class), eq("mcp:orphan")))
                .thenReturn(Optional.empty());

            // Falls back to global epoch
            when(mockEpochManager.getCurrentEpoch(runId)).thenReturn(3);

            // Per-item path: V2StepByStepContextManager passes itemIndex → loadRunContextForItem.
            when(mockRunContextService.loadRunContextForItem(eq(runId), eq(tenantId), anyInt(), anyInt(), anyInt()))
                .thenReturn(new HashMap<>());
            when(mockStateSnapshotService.getSnapshot(runId))
                .thenReturn(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty());

            // Act
            contextManager.getOrCreateContextWithTriggerData(
                "ctx-key", mockTree, "item-0", 0, "mcp:orphan");

            // Verify: global epoch=3 used as fallback (per-item path with itemIndex=0)
            verify(mockRunContextService).loadRunContextForItem(runId, tenantId, 3, 0, 0);
        }
    }

    /**
     * Bare-alias companion regression for the loop-iteration-override path
     * (Daily Email Digest fix, 2026-05-09). Mirrors the SplitAwareNodeExecutor
     * `reapplyLoopCoreOverrides` test - both paths must write the override under
     * BOTH the full nodeId key AND the bare alias so that CodeNode's
     * {@code $input.<loop_alias>.iteration} surfaces the live counter rather
     * than the stale value the DB load planted under the alias.
     */
    @Nested
    @DisplayName("applyLoopCoreOutputOverrides() - bare-alias companion (Daily Email Digest regression)")
    class ApplyLoopCoreOutputOverridesBareAlias {

        @Test
        @DisplayName("Writes the live override under both core:my_loop AND my_loop so $input.my_loop.iteration sees the live counter")
        void writesUnderFullKeyAndBareAlias() {
            Map<String, Object> liveOverride = Map.of(
                "iteration", 5,
                "terminated", false,
                "output", Map.of("iteration", 5, "terminated", false));

            Map<String, Object> stepOutputs = new HashMap<>();
            // Outer DB load planted both keys at iteration=0 (the buggy stale view)
            stepOutputs.put("core:my_loop", Map.of("iteration", 0, "output", Map.of("iteration", 0)));
            stepOutputs.put("my_loop", Map.of("iteration", 0, "output", Map.of("iteration", 0)));

            Map<String, Object> cachedGlobalData = new HashMap<>();
            cachedGlobalData.put(
                com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY,
                Map.of("core:my_loop", liveOverride));

            contextManager.applyLoopCoreOutputOverrides(stepOutputs, cachedGlobalData);

            assertEquals(liveOverride, stepOutputs.get("core:my_loop"),
                "Full-key entry must carry the live override");
            assertEquals(liveOverride, stepOutputs.get("my_loop"),
                "Bare-alias entry must ALSO carry the live override - without this companion "
              + "write, $input.my_loop.iteration in CodeNode JS keeps reading the iter=0 "
              + "snapshot the DB load planted under the alias.");
        }

        @Test
        @DisplayName("No-prefix loop key (already an alias) - no redundant write, no NPE")
        void noPrefixKeyIsNoOp() {
            Map<String, Object> liveOverride = Map.of("iteration", 2);

            Map<String, Object> stepOutputs = new HashMap<>();
            Map<String, Object> cachedGlobalData = Map.of(
                com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY,
                Map.of("plain_loop", liveOverride));

            contextManager.applyLoopCoreOutputOverrides(stepOutputs, cachedGlobalData);

            assertEquals(liveOverride, stepOutputs.get("plain_loop"));
            assertEquals(1, stepOutputs.size(),
                "No-prefix key must not write a duplicate under the same name");
        }

        @Test
        @DisplayName("Empty / null overrides - early return, stepOutputs untouched")
        void emptyOrMissingOverrideIsNoOp() {
            Map<String, Object> stepOutputs = new HashMap<>();
            stepOutputs.put("core:my_loop", Map.of("iteration", 0));

            // Empty overrides
            contextManager.applyLoopCoreOutputOverrides(stepOutputs,
                Map.of(com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY,
                       Map.of()));
            assertEquals(1, stepOutputs.size());

            // Missing override key
            contextManager.applyLoopCoreOutputOverrides(stepOutputs, new HashMap<>());
            assertEquals(1, stepOutputs.size());
        }
    }

    /**
     * Regression: "epoch 2 truncates silently at the first signal-yielding wait"
     * (prod 2026-08-05, workflow "xAI Video Sequence": epoch 1 ran 36 nodes, epoch 2 ran 10 and
     * still reported COMPLETED).
     *
     * <p>{@code updateGlobalData(runId, epoch, …)} mirrors every entry into a run-level key, and
     * the epoch-scoped read fell back to that mirror whenever its own epoch had no entry yet.
     * A loop that terminated in epoch N therefore handed {@code back_edge_state{terminated=true}}
     * to epoch N+1; {@code BackEdgeHandler.executeBackEdgeIteration} took its
     * {@code if (state.terminated()) continue} branch, the loop neither iterated nor routed to its
     * exit, the signal resume found nothing ready, and the epoch closed COMPLETED with everything
     * downstream never executed.
     *
     * <p>The fallback still exists - it just refuses to serve data another epoch wrote.
     */
    @Nested
    @DisplayName("globalData cache - epoch isolation")
    class GlobalDataEpochIsolation {

        private static final String RUN = "run-epoch-isolation";
        private static final String BACK_EDGE_KEY = "back_edge_state:e1";

        @Test
        @DisplayName("A later epoch does not inherit an earlier epoch's back-edge state")
        void laterEpochDoesNotInheritEarlierEpochState() {
            contextManager.updateGlobalData(RUN, 1, Map.of(BACK_EDGE_KEY, "terminated-in-epoch-1"));

            Map<String, Object> epochTwo = contextManager.getCachedGlobalData(RUN, 2);

            assertFalse(epochTwo.containsKey(BACK_EDGE_KEY),
                "Epoch 2 must not see epoch 1's back-edge state. Inheriting a terminated loop makes "
              + "executeBackEdgeIteration skip the back-edge entirely: no iteration, no exit "
              + "routing, no ready nodes, and the epoch closes COMPLETED half-executed. Got: " + epochTwo);
        }

        @Test
        @DisplayName("The epoch that wrote the data still reads it back")
        void writingEpochStillReadsItsOwnData() {
            contextManager.updateGlobalData(RUN, 1, Map.of(BACK_EDGE_KEY, "state-1"));

            assertEquals("state-1", contextManager.getCachedGlobalData(RUN, 1).get(BACK_EDGE_KEY),
                "Epoch isolation must not cost an epoch its own data");
        }

        @Test
        @DisplayName("Each epoch keeps its own value for the same key")
        void epochsKeepSeparateValues() {
            contextManager.updateGlobalData(RUN, 1, Map.of(BACK_EDGE_KEY, "state-1"));
            contextManager.updateGlobalData(RUN, 2, Map.of(BACK_EDGE_KEY, "state-2"));

            assertEquals("state-1", contextManager.getCachedGlobalData(RUN, 1).get(BACK_EDGE_KEY));
            assertEquals("state-2", contextManager.getCachedGlobalData(RUN, 2).get(BACK_EDGE_KEY));
        }

        @Test
        @DisplayName("An epoch-less write stays visible to an epoch-scoped read")
        void epochLessWriteRemainsVisibleToEpochScopedRead() {
            // The fallback's original purpose: a writer that did not know its epoch. Only a
            // write that CLAIMS an epoch may be withheld from a different one.
            contextManager.updateGlobalData(RUN, Map.of("shared", "value"));

            assertEquals("value", contextManager.getCachedGlobalData(RUN, 7).get("shared"),
                "Un-attributed data must remain readable, otherwise the fix loses state within an epoch");
        }

        @Test
        @DisplayName("An epoch-less write un-attributes only the keys it wrote, not the whole mirror")
        void epochLessWriteUnattributesOnlyItsOwnKeys() {
            contextManager.updateGlobalData(RUN, 1, Map.of("a", "1"));
            contextManager.updateGlobalData(RUN, Map.of("b", "2"));

            Map<String, Object> epochTwo = contextManager.getCachedGlobalData(RUN, 2);

            assertEquals("2", epochTwo.get("b"),
                "A key written with no epoch belongs to no epoch and must stay readable");
            assertFalse(epochTwo.containsKey("a"),
                "Epoch 1's key must NOT ride along. Un-attributing the whole run on any epoch-less "
              + "write re-admits every entry an earlier epoch wrote - which is the cross-epoch leak "
              + "itself, reachable from a manual step on a live run. Got: " + epochTwo);
        }

        @Test
        @DisplayName("Re-writing a key without an epoch releases that key to every epoch")
        void epochLessRewriteReleasesThatKey() {
            contextManager.updateGlobalData(RUN, 1, Map.of("a", "epoch-1"));
            contextManager.updateGlobalData(RUN, Map.of("a", "un-attributed"));

            assertEquals("un-attributed", contextManager.getCachedGlobalData(RUN, 2).get("a"),
                "The last writer of 'a' claimed no epoch, so 'a' is no longer epoch 1's to withhold");
        }

        @Test
        @DisplayName("A mixed mirror serves the un-attributed keys and withholds the foreign ones")
        void mixedMirrorIsFilteredPerKey() {
            contextManager.updateGlobalData(RUN, 1, Map.of(BACK_EDGE_KEY, "terminated", "one", "1"));
            contextManager.updateGlobalData(RUN, Map.of("shared", "s"));
            contextManager.updateGlobalData(RUN, 2, Map.of("two", "2"));
            // Epoch 3 has no entry of its own, so it reads the mirror through the filter.
            Map<String, Object> epochThree = contextManager.getCachedGlobalData(RUN, 3);

            assertEquals(Set.of("shared"), epochThree.keySet(),
                "Only the un-attributed key may cross into a foreign epoch. Got: " + epochThree);
        }

        @Test
        @DisplayName("cleanup drops the epoch attribution with the data")
        void cleanupDropsTheAttribution() {
            contextManager.updateGlobalData(RUN, 1, Map.of("a", "1"));
            contextManager.cleanup(RUN);
            contextManager.updateGlobalData(RUN, Map.of("b", "2"));

            assertEquals("2", contextManager.getCachedGlobalData(RUN, 3).get("b"),
                "A stale attribution surviving cleanup would withhold data from every later epoch");
        }

        @Test
        @DisplayName("Reading an epoch that never wrote anything yields nothing, not the previous epoch")
        void unknownEpochReadsEmpty() {
            contextManager.updateGlobalData(RUN, 5, Map.of(BACK_EDGE_KEY, "state-5"));

            assertTrue(contextManager.getCachedGlobalData(RUN, 6).isEmpty(),
                "Epoch 6 wrote nothing and must start clean");
        }
    }
}
